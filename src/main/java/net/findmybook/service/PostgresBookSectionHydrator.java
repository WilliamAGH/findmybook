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
 * Enriches a {@link Book} with supplemental section data from independent Postgres queries.
 *
 * <p><strong>Graceful degradation contract:</strong> Each hydration method independently queries a
 * supplemental section (authors, categories, collections, etc.). If a section query fails, the book
 * is returned with an empty/default value for that section rather than aborting the entire book
 * lookup. This is intentional non-critical graceful degradation — failures are logged at WARN level
 * so operators can detect persistent data access issues without degrading the user experience for
 * the primary book record.</p>
 */
final class PostgresBookSectionHydrator {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresBookSectionHydrator.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

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
                ORDER BY COALESCE(baj.position, 2147483647), lower(a.name)
                """;
        try {
            List<String> authors = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString("name"));
            book.setAuthors(authors == null || authors.isEmpty() ? List.of() : authors);
        } catch (DataAccessException ex) {
            LOG.warn("Non-critical: failed to hydrate authors for {}: {}", canonicalId, ex.getMessage());
            book.setAuthors(List.of());
        }
    }

    void hydrateCategories(Book book, UUID canonicalId) {
        String sql = """
                SELECT bc.display_name
                FROM book_collections_join bcj
                JOIN book_collections bc ON bc.id = bcj.collection_id
                WHERE bcj.book_id = ?::uuid
                  AND bc.collection_type = 'CATEGORY'
                ORDER BY COALESCE(bcj.position, 9999), lower(bc.display_name)
                """;
        try {
            List<String> categories = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString("display_name"));
            book.setCategories(categories == null || categories.isEmpty() ? List.of() : categories);
        } catch (DataAccessException ex) {
            LOG.warn("Non-critical: failed to hydrate categories for {}: {}", canonicalId, ex.getMessage());
            book.setCategories(List.of());
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
                         COALESCE(bcj.position, 2147483647),
                         lower(bc.display_name)
                """;
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
            LOG.warn("Non-critical: failed to hydrate collections for {}: {}", canonicalId, ex.getMessage());
            book.setCollections(List.of());
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
                if (!rs.next()) {
                    return null;
                }
                book.setHeightCm(toDouble(rs.getBigDecimal("height")));
                book.setWidthCm(toDouble(rs.getBigDecimal("width")));
                book.setThicknessCm(toDouble(rs.getBigDecimal("thickness")));
                book.setWeightGrams(toDouble(rs.getBigDecimal("weight_grams")));
                return null;
            });
        } catch (DataAccessException ex) {
            LOG.warn("Non-critical: failed to hydrate dimensions for {}: {}", canonicalId, ex.getMessage());
            book.setHeightCm(null);
            book.setWidthCm(null);
            book.setThicknessCm(null);
            book.setWeightGrams(null);
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
            String rawJson = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), rs -> rs.next() ? rs.getString(1) : null);
            if (rawJson != null && !rawJson.isBlank()) {
                book.setRawJsonResponse(rawJson);
            }
        } catch (DataAccessException ex) {
            LOG.warn("Non-critical: failed to hydrate raw payload for {}: {}", canonicalId, ex.getMessage());
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
                    Map<String, Object> metadata = parseJsonAttributes(rs.getObject("metadata"));
                    if (!metadata.isEmpty()) {
                        attributes.put("metadata", metadata);
                    }
                    result.put(camelCaseKey, attributes);
                }
                return result;
            });
            book.setQualifiers(tags);
        } catch (DataAccessException ex) {
            LOG.warn("Non-critical: failed to hydrate tags for {}: {}", canonicalId, ex.getMessage());
            book.setQualifiers(Map.of());
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
        if (value == null) {
            return Map.of();
        }

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
                LOG.warn("Non-critical: failed to parse tag metadata JSON: {}", ex.getMessage());
                return Map.of();
            }
        }

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copy = new LinkedHashMap<>();
            mapValue.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of();
    }

    private Double toDouble(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
