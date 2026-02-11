package net.findmybook.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repository responsible for Postgres backed sitemap queries.
 */
@Repository
public class SitemapRepository {

    private static final String LETTER_BUCKET_EXPRESSION =
            "CASE " +
            "WHEN substring(lower(trim(%s)), 1, 1) BETWEEN 'a' AND 'z' THEN substring(lower(trim(%s)), 1, 1) " +
            "ELSE '0-9' END";

    private static final String BOOK_UPDATED_AT_ALIAS = "book_updated_at";
    private static final String EPOCH_TIMESTAMP_LITERAL = "TIMESTAMP 'epoch'";
    private static final String BOOK_CHANGE_EVENTS_CTE = """
            WITH change_events AS (
                SELECT b.id AS book_id,
                       GREATEST(
                           COALESCE(b.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(b.created_at, TIMESTAMP 'epoch')
                       ) AS changed_at
                FROM books b
                WHERE b.slug IS NOT NULL
                UNION ALL
                SELECT be.book_id,
                       GREATEST(
                           COALESCE(be.last_updated, TIMESTAMP 'epoch'),
                           COALESCE(be.created_at, TIMESTAMP 'epoch')
                       )
                FROM book_external_ids be
                UNION ALL
                SELECT br.book_id,
                       GREATEST(
                           COALESCE(br.fetched_at, TIMESTAMP 'epoch'),
                           COALESCE(br.contributed_at, TIMESTAMP 'epoch'),
                           COALESCE(br.created_at, TIMESTAMP 'epoch')
                       )
                FROM book_raw_data br
                UNION ALL
                SELECT bil.book_id,
                       GREATEST(
                           COALESCE(bil.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(bil.s3_uploaded_at, TIMESTAMP 'epoch'),
                           COALESCE(bil.created_at, TIMESTAMP 'epoch')
                       )
                FROM book_image_links bil
                UNION ALL
                SELECT bd.book_id,
                       GREATEST(
                           COALESCE(bd.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(bd.created_at, TIMESTAMP 'epoch')
                       )
                FROM book_dimensions bd
                UNION ALL
                SELECT bta.book_id,
                       GREATEST(
                           COALESCE(bta.created_at, TIMESTAMP 'epoch'),
                           COALESCE(bt.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(bt.created_at, TIMESTAMP 'epoch')
                       )
                FROM book_tag_assignments bta
                JOIN book_tags bt ON bt.id = bta.tag_id
                UNION ALL
                SELECT baj.book_id,
                       GREATEST(
                           COALESCE(baj.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(baj.created_at, TIMESTAMP 'epoch'),
                           COALESCE(a.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(a.created_at, TIMESTAMP 'epoch')
                       )
                FROM book_authors_join baj
                JOIN authors a ON a.id = baj.author_id
                UNION ALL
                SELECT bcj.book_id,
                       GREATEST(
                           COALESCE(bcj.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(bcj.created_at, TIMESTAMP 'epoch'),
                           COALESCE(bcj.added_at, TIMESTAMP 'epoch'),
                           COALESCE(bc.updated_at, TIMESTAMP 'epoch'),
                           COALESCE(bc.created_at, TIMESTAMP 'epoch')
                       )
                FROM book_collections_join bcj
                JOIN book_collections bc ON bc.id = bcj.collection_id
                UNION ALL
                SELECT bac.book_id,
                       COALESCE(bac.created_at, TIMESTAMP 'epoch')
                FROM book_ai_content bac
                UNION ALL
                SELECT bsm.book_id,
                       COALESCE(bsm.created_at, TIMESTAMP 'epoch')
                FROM book_seo_metadata bsm
                UNION ALL
                SELECT bsr.book_id,
                       COALESCE(bsr.created_at, TIMESTAMP 'epoch')
                FROM book_slug_redirect bsr
            ),
            book_last_modified AS (
                SELECT b.id,
                       b.slug,
                       b.title,
                       MAX(change_events.changed_at) AS %s
                FROM books b
                LEFT JOIN change_events ON change_events.book_id = b.id
                WHERE b.slug IS NOT NULL
                GROUP BY b.id, b.slug, b.title
            )
            """.formatted(BOOK_UPDATED_AT_ALIAS);

    private static final RowMapper<BookRow> BOOK_ROW_MAPPER = (rs, rowNum) -> new BookRow(
            rs.getString("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getTimestamp(BOOK_UPDATED_AT_ALIAS).toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public SitemapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int countAllBooks() {
        String sql = "SELECT COUNT(*) FROM books WHERE slug IS NOT NULL";
        return Objects.requireNonNullElse(jdbcTemplate.queryForObject(sql, Integer.class), 0);
    }

    public Map<String, Integer> countBooksByBucket() {
        String expr = LETTER_BUCKET_EXPRESSION.formatted("title", "title");
        String sql = "SELECT " + expr + " AS bucket, COUNT(*) AS total FROM books " +
                     "WHERE slug IS NOT NULL GROUP BY bucket";
        return jdbcTemplate.query(sql, rs -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString("bucket").toUpperCase(Locale.ROOT), rs.getInt("total"));
            }
            return counts;
        });
    }

    public int countBooksForBucket(String bucket) {
        String expr = LETTER_BUCKET_EXPRESSION.formatted("title", "title");
        String sql = "SELECT COUNT(*) FROM books WHERE slug IS NOT NULL AND " + expr + " = ?";
        return Objects.requireNonNullElse(jdbcTemplate.queryForObject(sql, Integer.class, bucket.toLowerCase(Locale.ROOT)), 0);
    }

    public List<BookRow> fetchBooksForBucket(String bucket, int limit, int offset) {
        String expr = LETTER_BUCKET_EXPRESSION.formatted("title", "title");
        String sql = "SELECT id, slug, title, COALESCE(updated_at, created_at, NOW()) AS " + BOOK_UPDATED_AT_ALIAS +
                     " FROM books WHERE slug IS NOT NULL AND " + expr + " = ? " +
                     "ORDER BY lower(title) NULLS LAST, slug NULLS LAST, id ASC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, BOOK_ROW_MAPPER, bucket.toLowerCase(Locale.ROOT), limit, offset);
    }

    public List<BookRow> fetchBooksForXml(int limit, int offset) {
        String sql = BOOK_CHANGE_EVENTS_CTE +
                     "SELECT id, slug, title, " + BOOK_UPDATED_AT_ALIAS + " " +
                     "FROM book_last_modified " +
                     "ORDER BY " + BOOK_UPDATED_AT_ALIAS + " ASC NULLS LAST, " +
                     "         lower(title) ASC NULLS LAST, " +
                     "         slug ASC NULLS LAST, " +
                     "         id ASC " +
                     "LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, BOOK_ROW_MAPPER, limit, offset);
    }

    public Map<String, Integer> countAuthorsByBucket() {
        String expr = LETTER_BUCKET_EXPRESSION.formatted("COALESCE(normalized_name, name)", "COALESCE(normalized_name, name)");
        String sql = "SELECT " + expr + " AS bucket, COUNT(*) AS total FROM authors GROUP BY bucket";
        return jdbcTemplate.query(sql, rs -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString("bucket").toUpperCase(Locale.ROOT), rs.getInt("total"));
            }
            return counts;
        });
    }

    public int countAuthorsForBucket(String bucket) {
        String expr = LETTER_BUCKET_EXPRESSION.formatted("COALESCE(normalized_name, name)", "COALESCE(normalized_name, name)");
        String sql = "SELECT COUNT(*) FROM authors WHERE " + expr + " = ?";
        return Objects.requireNonNullElse(jdbcTemplate.queryForObject(sql, Integer.class, bucket.toLowerCase(Locale.ROOT)), 0);
    }

    public List<AuthorRow> fetchAuthorsForBucket(String bucket, int limit, int offset) {
        String expr = LETTER_BUCKET_EXPRESSION.formatted("COALESCE(normalized_name, name)", "COALESCE(normalized_name, name)");
        String sql = "SELECT id, name, COALESCE(updated_at, created_at, NOW()) AS author_updated_at " +
                     "FROM authors WHERE " + expr + " = ? " +
                     "ORDER BY lower(COALESCE(name, '')) NULLS LAST, id ASC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AuthorRow(
                rs.getString("id"),
                rs.getString("name"),
                rs.getTimestamp("author_updated_at").toInstant()
        ), bucket.toLowerCase(Locale.ROOT), limit, offset);
    }

    public Map<String, List<BookRow>> fetchBooksForAuthors(Set<String> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = authorIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
                WITH requested_authors AS (
                    SELECT baj.author_id
                    FROM book_authors_join baj
                    WHERE baj.author_id IN (%s)
                    GROUP BY baj.author_id
                ),
                requested_books AS (
                    SELECT DISTINCT baj.book_id
                    FROM book_authors_join baj
                    JOIN requested_authors ra ON ra.author_id = baj.author_id
                ),
                change_events AS (
                    SELECT b.id AS book_id,
                           GREATEST(
                               COALESCE(b.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(b.created_at, TIMESTAMP 'epoch')
                           ) AS changed_at
                    FROM books b
                    JOIN requested_books rb ON rb.book_id = b.id
                    WHERE b.slug IS NOT NULL
                    UNION ALL
                    SELECT be.book_id,
                           GREATEST(
                               COALESCE(be.last_updated, TIMESTAMP 'epoch'),
                               COALESCE(be.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_external_ids be
                    JOIN requested_books rb ON rb.book_id = be.book_id
                    UNION ALL
                    SELECT br.book_id,
                           GREATEST(
                               COALESCE(br.fetched_at, TIMESTAMP 'epoch'),
                               COALESCE(br.contributed_at, TIMESTAMP 'epoch'),
                               COALESCE(br.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_raw_data br
                    JOIN requested_books rb ON rb.book_id = br.book_id
                    UNION ALL
                    SELECT bil.book_id,
                           GREATEST(
                               COALESCE(bil.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bil.s3_uploaded_at, TIMESTAMP 'epoch'),
                               COALESCE(bil.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_image_links bil
                    JOIN requested_books rb ON rb.book_id = bil.book_id
                    UNION ALL
                    SELECT bd.book_id,
                           GREATEST(
                               COALESCE(bd.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bd.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_dimensions bd
                    JOIN requested_books rb ON rb.book_id = bd.book_id
                    UNION ALL
                    SELECT bta.book_id,
                           GREATEST(
                               COALESCE(bta.created_at, TIMESTAMP 'epoch'),
                               COALESCE(bt.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bt.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_tag_assignments bta
                    JOIN requested_books rb ON rb.book_id = bta.book_id
                    JOIN book_tags bt ON bt.id = bta.tag_id
                    UNION ALL
                    SELECT baj.book_id,
                           GREATEST(
                               COALESCE(baj.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(baj.created_at, TIMESTAMP 'epoch'),
                               COALESCE(a.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(a.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_authors_join baj
                    JOIN requested_books rb ON rb.book_id = baj.book_id
                    JOIN authors a ON a.id = baj.author_id
                    UNION ALL
                    SELECT bcj.book_id,
                           GREATEST(
                               COALESCE(bcj.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bcj.created_at, TIMESTAMP 'epoch'),
                               COALESCE(bcj.added_at, TIMESTAMP 'epoch'),
                               COALESCE(bc.updated_at, TIMESTAMP 'epoch'),
                               COALESCE(bc.created_at, TIMESTAMP 'epoch')
                           )
                    FROM book_collections_join bcj
                    JOIN requested_books rb ON rb.book_id = bcj.book_id
                    JOIN book_collections bc ON bc.id = bcj.collection_id
                    UNION ALL
                    SELECT bac.book_id,
                           COALESCE(bac.created_at, TIMESTAMP 'epoch')
                    FROM book_ai_content bac
                    JOIN requested_books rb ON rb.book_id = bac.book_id
                    UNION ALL
                    SELECT bsm.book_id,
                           COALESCE(bsm.created_at, TIMESTAMP 'epoch')
                    FROM book_seo_metadata bsm
                    JOIN requested_books rb ON rb.book_id = bsm.book_id
                    UNION ALL
                    SELECT bsr.book_id,
                           COALESCE(bsr.created_at, TIMESTAMP 'epoch')
                    FROM book_slug_redirect bsr
                    JOIN requested_books rb ON rb.book_id = bsr.book_id
                ),
                book_last_modified AS (
                    SELECT b.id,
                           b.slug,
                           b.title,
                           MAX(change_events.changed_at) AS book_updated_at
                    FROM books b
                    JOIN requested_books rb ON rb.book_id = b.id
                    LEFT JOIN change_events ON change_events.book_id = b.id
                    WHERE b.slug IS NOT NULL
                    GROUP BY b.id, b.slug, b.title
                )
                SELECT baj.author_id, blm.id, blm.slug, blm.title, blm.book_updated_at
                FROM book_authors_join baj
                JOIN requested_authors ra ON ra.author_id = baj.author_id
                JOIN book_last_modified blm ON blm.id = baj.book_id
                ORDER BY baj.author_id, lower(blm.title), blm.slug
                """;
        String resolvedSql = sql.formatted(placeholders);
        Object[] params = authorIds.toArray();
        return jdbcTemplate.query(resolvedSql, rs -> {
            Map<String, List<BookRow>> results = new LinkedHashMap<>();
            while (rs.next()) {
                String authorId = rs.getString("author_id");
                BookRow row = new BookRow(
                        rs.getString("id"),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getTimestamp(BOOK_UPDATED_AT_ALIAS).toInstant()
                );
                results.computeIfAbsent(authorId, key -> new ArrayList<>()).add(row);
            }
            return results;
        }, params);
    }

    public List<PageMetadata> fetchBookPageMetadata(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("Page size must be positive, got: " + pageSize);
        }
        String sql = BOOK_CHANGE_EVENTS_CTE +
                ", ordered AS (" +
                "    SELECT " + BOOK_UPDATED_AT_ALIAS + "," +
                "           row_number() OVER (ORDER BY " + BOOK_UPDATED_AT_ALIAS + " ASC NULLS LAST, " +
                "                                       lower(title) ASC NULLS LAST, " +
                "                                       slug ASC NULLS LAST, " +
                "                                       id ASC) AS rn" +
                "    FROM book_last_modified" +
                ") " +
                "SELECT CAST(FLOOR((rn - 1) / ?::numeric) AS bigint) + 1 AS page_number, " +
                "       MAX(" + BOOK_UPDATED_AT_ALIAS + ") AS last_modified " +
                "FROM ordered GROUP BY page_number ORDER BY page_number";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PageMetadata(
                rs.getInt("page_number"),
                rs.getTimestamp("last_modified").toInstant()
        ), pageSize);
    }

    public DatasetFingerprint fetchBookFingerprint() {
        String sql = BOOK_CHANGE_EVENTS_CTE +
                "SELECT COUNT(*) AS total_records, " +
                "COALESCE(MAX(" + BOOK_UPDATED_AT_ALIAS + "), " + EPOCH_TIMESTAMP_LITERAL + ") AS last_modified " +
                "FROM book_last_modified";
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new DatasetFingerprint(
                rs.getInt("total_records"),
                rs.getTimestamp("last_modified").toInstant()
        ));
    }

    public DatasetFingerprint fetchAuthorFingerprint() {
        String sql = "SELECT COUNT(DISTINCT a.id) AS total_records, " +
                "GREATEST(" +
                "    COALESCE(MAX(a.updated_at), MAX(a.created_at), TIMESTAMP 'epoch')," +
                "    COALESCE(MAX(b.updated_at), MAX(b.created_at), TIMESTAMP 'epoch')" +
                ") AS last_modified " +
                "FROM authors a " +
                "LEFT JOIN book_authors_join baj ON baj.author_id = a.id " +
                "LEFT JOIN books b ON b.id = baj.book_id AND b.slug IS NOT NULL";
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new DatasetFingerprint(
                rs.getInt("total_records"),
                rs.getTimestamp("last_modified").toInstant()
        ));
    }

    public record BookRow(String bookId, String slug, String title, Instant updatedAt) {}

    public record AuthorRow(String id, String name, Instant updatedAt) {}

    public record PageMetadata(int pageNumber, Instant lastModified) {}

    public record DatasetFingerprint(int totalRecords, Instant lastModified) {}
}
