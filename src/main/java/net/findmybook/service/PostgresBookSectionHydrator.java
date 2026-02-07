package net.findmybook.service;

import net.findmybook.model.Book;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enriches a {@link Book} with supplemental section data from Postgres.
 */
final class PostgresBookSectionHydrator {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresBookSectionHydrator.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /** Sentinel value for COALESCE in ORDER BY so rows without a position sort last. */
    private static final int POSITION_SORT_LAST = Integer.MAX_VALUE;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    PostgresBookSectionHydrator(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void hydrateAuthors(Book book, UUID canonicalId) {
        String sql = """
                SELECT a.name
                FROM book_authors_join baj
                JOIN authors a ON a.id = baj.author_id
                WHERE baj.book_id = ?::uuid
                ORDER BY COALESCE(baj.position, %d), lower(a.name)
                """.formatted(POSITION_SORT_LAST);
        try {
            List<String> authors = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString("name"));
            book.setAuthors(authors == null || authors.isEmpty() ? List.of() : authors);
        } catch (DataAccessException ex) {
            throw hydrationFailure("authors", canonicalId, ex);
        }
    }

    void hydrateCategories(Book book, UUID canonicalId) {
        String sql = """
                SELECT bc.display_name
                FROM book_collections_join bcj
                JOIN book_collections bc ON bc.id = bcj.collection_id
                WHERE bcj.book_id = ?::uuid
                  AND bc.collection_type = 'CATEGORY'
                ORDER BY COALESCE(bcj.position, %d), lower(bc.display_name)
                """.formatted(POSITION_SORT_LAST);
        try {
            List<String> categories = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString("display_name"));
            book.setCategories(categories == null || categories.isEmpty() ? List.of() : categories);
        } catch (DataAccessException ex) {
            throw hydrationFailure("categories", canonicalId, ex);
        }
    }

    void hydrateCollections(Book book, UUID canonicalId) {
        String sql = """
                SELECT bc.id,
                       bc.display_name,
                       bc.collection_type,
                       bc.source,
                       bcj.position
                FROM book_collections_join bcj
                JOIN book_collections bc ON bc.id = bcj.collection_id
                WHERE bcj.book_id = ?::uuid
                ORDER BY CASE WHEN bc.collection_type = 'CATEGORY' THEN 0 ELSE 1 END,
                         COALESCE(bcj.position, %d),
                         lower(bc.display_name)
                """.formatted(POSITION_SORT_LAST);
        try {
            List<Book.CollectionAssignment> assignments = jdbcTemplate.query(
                sql,
                ps -> ps.setObject(1, canonicalId),
                (rs, rowNum) -> {
                    Book.CollectionAssignment assignment = new Book.CollectionAssignment();
                    assignment.setCollectionId(rs.getString("id"));
                    assignment.setName(rs.getString("display_name"));
                    assignment.setCollectionType(rs.getString("collection_type"));
                    Integer rank = (Integer) rs.getObject("position");
                    assignment.setRank(rank);
                    assignment.setSource(rs.getString("source"));
                    return assignment;
                }
            );
            book.setCollections(assignments);
        } catch (DataAccessException ex) {
            throw hydrationFailure("collections", canonicalId, ex);
        }
    }

    void hydrateDimensions(Book book, UUID canonicalId) {
        String sql = """
                SELECT height, width, thickness, weight_grams
                FROM book_dimensions
                WHERE book_id = ?::uuid
                LIMIT 1
                """;
        try {
            jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), rs -> {
                book.setHeightCm(toDouble(rs.getBigDecimal("height")));
                book.setWidthCm(toDouble(rs.getBigDecimal("width")));
                book.setThicknessCm(toDouble(rs.getBigDecimal("thickness")));
                book.setWeightGrams(toDouble(rs.getBigDecimal("weight_grams")));
            });
        } catch (DataAccessException ex) {
            throw hydrationFailure("dimensions", canonicalId, ex);
        }
    }

    void hydrateRawPayload(Book book, UUID canonicalId) {
        String sql = """
                SELECT raw_json_response::text
                FROM book_raw_data
                WHERE book_id = ?::uuid
                ORDER BY contributed_at DESC
                LIMIT 1
                """;
        try {
            List<String> payloads = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString(1));
            if (!payloads.isEmpty() && payloads.getFirst() != null && !payloads.getFirst().isBlank()) {
                book.setRawJsonResponse(payloads.getFirst());
            }
        } catch (DataAccessException ex) {
            throw hydrationFailure("raw payload", canonicalId, ex);
        }
    }

    void hydrateTags(Book book, UUID canonicalId) {
        String sql = """
                SELECT bt.key, bt.display_name, bta.source, bta.confidence, bta.metadata
                FROM book_tag_assignments bta
                JOIN book_tags bt ON bt.id = bta.tag_id
                WHERE bta.book_id = ?::uuid
                """;
        try {
            Map<String, Object> tags = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), rs -> {
                Map<String, Object> result = new LinkedHashMap<>();
                while (rs.next()) {
                    String key = rs.getString("key");
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    String camelCaseKey = snakeToCamelCase(key);

                    Map<String, Object> attributes = new LinkedHashMap<>();
                    String displayName = rs.getString("display_name");
                    if (displayName != null && !displayName.isBlank()) {
                        attributes.put("displayName", displayName);
                    }
                    String source = rs.getString("source");
                    if (source != null && !source.isBlank()) {
                        attributes.put("source", source);
                    }
                    Double confidence = toDouble(rs.getBigDecimal("confidence"));
                    if (confidence != null) {
                        attributes.put("confidence", confidence);
                    }
                    Object metadataValue = rs.getObject("metadata");
                    if (metadataValue != null) {
                        Map<String, Object> metadata = parseJsonAttributes(metadataValue);
                        if (!metadata.isEmpty()) {
                            attributes.put("metadata", metadata);
                        }
                    }
                    result.put(camelCaseKey, attributes);
                }
                return result;
            });
            book.setQualifiers(tags);
        } catch (DataAccessException ex) {
            throw hydrationFailure("tags", canonicalId, ex);
        }
    }

    private String snakeToCamelCase(String snakeCase) {
        if (snakeCase == null || snakeCase.isBlank()) {
            return snakeCase;
        }
        String[] parts = snakeCase.split("_");
        if (parts.length == 1) {
            return snakeCase;
        }
        StringBuilder camelCase = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (!part.isEmpty()) {
                camelCase.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    camelCase.append(part.substring(1).toLowerCase());
                }
            }
        }
        return camelCase.toString();
    }

    private Map<String, Object> parseJsonAttributes(Object value) {
        String json = null;
        if (value instanceof org.postgresql.util.PGobject pgObject) {
            json = pgObject.getValue();
        } else if (value instanceof String str) {
            json = str;
        }

        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, MAP_TYPE);
            } catch (tools.jackson.core.JacksonException ex) {
                throw new IllegalStateException("Failed to parse tag metadata JSON", ex);
            }
        }

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copy = new LinkedHashMap<>();
            mapValue.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        throw new IllegalStateException("Unsupported or empty tag metadata: " + value.getClass().getName());
    }

    private Double toDouble(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private IllegalStateException hydrationFailure(String section, UUID canonicalId, DataAccessException ex) {
        LOG.error("Failed to hydrate {} for book {}: {}", section, canonicalId, ex.getMessage(), ex);
        return new IllegalStateException("Failed to hydrate " + section + " for book " + canonicalId, ex);
    }
}
