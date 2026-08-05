package net.findmybook.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.CategoryNormalizer;
import net.findmybook.util.IdGenerator;
import net.findmybook.util.JdbcUtils;
import net.findmybook.util.ValidationUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BookSupplementalPersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BookCollectionPersistenceService collectionPersistenceService;

    public BookSupplementalPersistenceService(JdbcTemplate jdbcTemplate,
                                              ObjectMapper objectMapper,
                                              BookCollectionPersistenceService collectionPersistenceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.collectionPersistenceService = collectionPersistenceService;
    }

    /**
     * Persists categories for a book with normalization and deduplication.
     *
     * <p>Uses {@link CategoryNormalizer#normalizeAndDeduplicate(List)} to:
     * <ul>
     *   <li>Split compound categories (e.g., "Fiction / Science Fiction")</li>
     *   <li>Remove duplicates (case-insensitive)</li>
     *   <li>Filter out invalid/empty entries</li>
     * </ul>
     *
     * @param bookId Book UUID
     * @param categories Raw category list from external source
     * @see CategoryNormalizer#normalizeAndDeduplicate(List)
     */
    public void persistCategories(String bookId, List<String> categories) {
        if (!StringUtils.hasText(bookId) || ValidationUtils.isNullOrEmpty(categories)) {
            return;
        }

        // Normalize, split compound categories, and deduplicate before persistence (DRY principle)
        List<String> normalizedCategories = CategoryNormalizer.normalizeAndDeduplicate(categories);

        for (String category : normalizedCategories) {
            collectionPersistenceService.upsertCategory(category)
                .ifPresent(collectionId -> collectionPersistenceService.addBookToCategory(collectionId, bookId));
        }
    }

    public void assignQualifierTags(String bookId, Map<String, Serializable> qualifiers) {
        if (!StringUtils.hasText(bookId) || ValidationUtils.isNullOrEmpty(qualifiers)) {
            return;
        }

        qualifiers.forEach((key, value) -> {
            if (!StringUtils.hasText(key)) {
                return;
            }
            Double confidence = (value instanceof Boolean && (Boolean) value) ? 1.0 : null;
            assignTagWithSerializedMetadata(
                bookId,
                key,
                key,
                ApplicationConstants.Tag.QUALIFIER,
                ApplicationConstants.Tag.QUALIFIER,
                confidence,
                serializeQualifierMetadata(value)
            );
        });
    }

    public void assignTag(String bookId,
                           String key,
                           String displayName,
                           String source,
                           Double confidence,
                           Map<String, Serializable> metadata) {
        if (!StringUtils.hasText(bookId) || !StringUtils.hasText(key)) {
            return;
        }
        String resolvedDisplayName = displayName != null ? displayName : key;
        Map<String, Serializable> metadataMap = !ValidationUtils.isNullOrEmpty(metadata)
            ? metadata
            : Map.of("value", resolvedDisplayName);
        String metadataJson = serializeMetadata(metadataMap);
        assignTagWithSerializedMetadata(bookId, key, resolvedDisplayName, ApplicationConstants.Tag.QUALIFIER, source, confidence, metadataJson);
    }

    private void assignTagInternal(String bookId,
                                   String tagId,
                                   String source,
                                   Double confidence,
                                   String metadataJson) {
        if (jdbcTemplate == null) {
            return;
        }

        JdbcUtils.executeUpdate(
            jdbcTemplate,
            "INSERT INTO book_tag_assignments (id, book_id, tag_id, source, confidence, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, NOW()) " +
            "ON CONFLICT (book_id, tag_id) DO UPDATE SET source = EXCLUDED.source, metadata = EXCLUDED.metadata, confidence = COALESCE(EXCLUDED.confidence, book_tag_assignments.confidence)",
            IdGenerator.generateLong(),
            JdbcUtils.toUuid(bookId),

            tagId,
            source,
            confidence,
            metadataJson
        );
    }

    private String upsertTag(String key, String displayName, String tagType) {
        try {
            return jdbcTemplate.queryForObject(
                "INSERT INTO book_tags (id, key, display_name, tag_type, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW()) " +
                "ON CONFLICT (key) DO UPDATE SET display_name = CASE WHEN EXCLUDED.display_name IS NOT NULL AND btrim(EXCLUDED.display_name) <> '' THEN EXCLUDED.display_name ELSE book_tags.display_name END, updated_at = NOW() RETURNING id",
                (rs, rowNum) -> rs.getString("id"),
                IdGenerator.generate(), key, displayName, tagType
            );
        } catch (DataAccessException ex) {
            return JdbcUtils.optionalString(jdbcTemplate, "SELECT id FROM book_tags WHERE key = ?", key).orElse(null);
        }
    }

    private String serializeQualifierMetadata(Serializable value) {
        try {
            // Map.of doesn't allow null values, handle null explicitly
            if (value == null) {
                return "{\"value\":null}";
            }
            return objectMapper.writeValueAsString(Map.of("value", value));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize qualifier metadata for persistence", ex);
        }
    }

    private String serializeMetadata(Map<String, Serializable> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize tag metadata for persistence", ex);
        }
    }

    private void assignTagWithSerializedMetadata(String bookId,
                                                 String key,
                                                 String displayName,
                                                 String tagType,
                                                 String source,
                                                 Double confidence,
                                                 String metadataJson) {
        if (!StringUtils.hasText(bookId) || !StringUtils.hasText(key)) {
            return;
        }

        String canonicalKey = normalizeTagKey(key);
        if (canonicalKey.isEmpty()) {
            return;
        }

        String tagId = upsertTag(canonicalKey, displayName != null ? displayName : key, tagType);
        String resolvedSource = normalizeTagSource(source, canonicalKey);
        if (resolvedSource.isEmpty()) {
            return;
        }
        assignTagInternal(bookId, tagId, resolvedSource, confidence, metadataJson);
    }

    private String normalizeTagKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String compactWhitespace = key.trim().replaceAll("\\s+", "_");
        return compactWhitespace.toLowerCase(Locale.ROOT);
    }

    private String normalizeTagSource(String source, String fallbackSource) {
        if (StringUtils.hasText(source)) {
            return source.trim();
        }
        return normalizeTagKey(fallbackSource);
    }

}
