package net.findmybook.repository;

import net.findmybook.support.sitemap.SitemapBookLastModifiedSqlSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private static final String AUTHOR_UPDATED_AT_ALIAS = "author_updated_at";
    private static final String AUTHOR_PAGE_NUMBER_ALIAS = "author_page_number";
    private static final String SQL_EPOCH_TIMESTAMP = "TIMESTAMP 'epoch'";
    private static final String DISABLE_PARALLEL_QUERY_FOR_TRANSACTION =
            "SET LOCAL max_parallel_workers_per_gather = 0";
    private static final String BOOK_CHANGE_EVENTS_CTE =
            SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte(BOOK_UPDATED_AT_ALIAS);
    private static final String BOOK_FINGERPRINT_QUERY =
            SitemapBookLastModifiedSqlSupport.bookFingerprintQuery(BOOK_UPDATED_AT_ALIAS);

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
        String sql = SitemapBookLastModifiedSqlSupport.pagedBookLastModifiedQuery(BOOK_UPDATED_AT_ALIAS);
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
        String resolvedSql = SitemapBookLastModifiedSqlSupport.scopedAuthorBookLastModifiedQuery(
                placeholders,
                BOOK_UPDATED_AT_ALIAS
        );
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

    /**
     * Computes XML sitemap metadata for all book listing pages in one database round trip.
     *
     * <p>The read transaction disables PostgreSQL gather workers before this global aggregate
     * to avoid dynamic shared-memory exhaustion in constrained database containers.</p>
     *
     * @param pageSize number of books in one XML sitemap page
     * @return page metadata ordered by XML sitemap page number
     */
    @Transactional(readOnly = true)
    public List<PageMetadata> fetchBookPageMetadata(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("Page size must be positive, got: " + pageSize);
        }
        String sql = BOOK_CHANGE_EVENTS_CTE +
                ", ordered AS (" +
                "    SELECT " + BOOK_UPDATED_AT_ALIAS + "," +
                "           row_number() OVER (ORDER BY lower(title) ASC NULLS LAST, " +
                "                                       slug ASC NULLS LAST, " +
                "                                       id ASC) AS rn" +
                "    FROM book_last_modified" +
                ") " +
                "SELECT CAST(FLOOR((rn - 1) / ?::numeric) AS bigint) + 1 AS page_number, " +
                "       MAX(" + BOOK_UPDATED_AT_ALIAS + ") AS last_modified " +
                "FROM ordered GROUP BY page_number ORDER BY page_number";
        disableParallelWorkersForTransaction();
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PageMetadata(
                rs.getInt("page_number"),
                rs.getTimestamp("last_modified").toInstant()
        ), pageSize);
    }

    /**
     * Computes the canonical ordered inventory of HTML author listing pages in one database read.
     *
     * <p>Each entry combines its route coordinates with the latest author or canonical-book
     * change represented by that HTML page. XML sitemap shards and their aggregate metadata can
     * therefore project the same inventory without loading the authors and books for every
     * listing page. Parallel gather workers are disabled for constrained database containers.</p>
     *
     * @param htmlPageSize number of authors in one HTML listing page
     * @return listing metadata ordered by route bucket and HTML page number
     */
    @Transactional(readOnly = true)
    public List<AuthorListingMetadata> fetchAuthorListingMetadata(int htmlPageSize) {
        if (htmlPageSize <= 0) {
            throw new IllegalArgumentException("HTML page size must be positive, got: " + htmlPageSize);
        }
        String authorBucketExpression = LETTER_BUCKET_EXPRESSION.formatted(
                "COALESCE(a.normalized_name, a.name)",
                "COALESCE(a.normalized_name, a.name)"
        );
        String sql = BOOK_CHANGE_EVENTS_CTE + """
                , ranked_authors AS (
                    SELECT a.id,
                           %s AS bucket,
                           COALESCE(a.updated_at, a.created_at, NOW()) AS %s,
                           CAST(FLOOR((ROW_NUMBER() OVER (
                               PARTITION BY %s
                               ORDER BY lower(COALESCE(a.name, '')) NULLS LAST, a.id ASC
                           ) - 1) / ?::numeric) AS bigint) + 1 AS %s
                    FROM authors a
                ),
                author_listing_pages AS (
                    SELECT ranked_authors.bucket,
                           ranked_authors.%s,
                           MAX(GREATEST(
                               ranked_authors.%s,
                               COALESCE(book_last_modified.%s, %s)
                           )) AS last_modified
                    FROM ranked_authors
                    LEFT JOIN book_authors_join ON book_authors_join.author_id = ranked_authors.id
                    LEFT JOIN book_last_modified ON book_last_modified.id = book_authors_join.book_id
                    GROUP BY ranked_authors.bucket, ranked_authors.%s
                )
                SELECT bucket, %s, last_modified
                FROM author_listing_pages
                ORDER BY CASE bucket
                    WHEN '0-9' THEN 27
                    ELSE ASCII(bucket) - ASCII('a') + 1
                END,
                %s
                """.formatted(
                authorBucketExpression,
                AUTHOR_UPDATED_AT_ALIAS,
                authorBucketExpression,
                AUTHOR_PAGE_NUMBER_ALIAS,
                AUTHOR_PAGE_NUMBER_ALIAS,
                AUTHOR_UPDATED_AT_ALIAS,
                BOOK_UPDATED_AT_ALIAS,
                SQL_EPOCH_TIMESTAMP,
                AUTHOR_PAGE_NUMBER_ALIAS,
                AUTHOR_PAGE_NUMBER_ALIAS,
                AUTHOR_PAGE_NUMBER_ALIAS
        );
        disableParallelWorkersForTransaction();
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp lastModified = rs.getTimestamp("last_modified");
            return new AuthorListingMetadata(
                    rs.getString("bucket").toUpperCase(Locale.ROOT),
                    rs.getInt(AUTHOR_PAGE_NUMBER_ALIAS),
                    Optional.ofNullable(lastModified).map(Timestamp::toInstant)
            );
        }, htmlPageSize);
    }

    private void disableParallelWorkersForTransaction() {
        jdbcTemplate.execute(DISABLE_PARALLEL_QUERY_FOR_TRANSACTION);
    }

    /**
     * Reads the hourly book fingerprint without parallel workers so constrained
     * PostgreSQL containers never need dynamic shared memory for this maintenance query.
     *
     * @return current sitemap book count and latest related-data timestamp
     */
    @Transactional(readOnly = true)
    public DatasetFingerprint fetchBookFingerprint() {
        disableParallelWorkersForTransaction();
        return jdbcTemplate.queryForObject(BOOK_FINGERPRINT_QUERY, (rs, rowNum) -> new DatasetFingerprint(
                rs.getInt("total_records"),
                rs.getTimestamp(BOOK_UPDATED_AT_ALIAS).toInstant()
        ));
    }

    /**
     * Reads the hourly author fingerprint without parallel workers so constrained
     * PostgreSQL containers never need dynamic shared memory for this maintenance query.
     *
     * @return current sitemap author count and latest related-data timestamp
     */
    @Transactional(readOnly = true)
    public DatasetFingerprint fetchAuthorFingerprint() {
        String sql = "SELECT COUNT(DISTINCT a.id) AS total_records, " +
                "GREATEST(" +
                "    COALESCE(MAX(a.updated_at), MAX(a.created_at), TIMESTAMP 'epoch')," +
                "    COALESCE(MAX(b.updated_at), MAX(b.created_at), TIMESTAMP 'epoch')" +
                ") AS last_modified " +
                "FROM authors a " +
                "LEFT JOIN book_authors_join baj ON baj.author_id = a.id " +
                "LEFT JOIN books b ON b.id = baj.book_id AND b.slug IS NOT NULL";
        disableParallelWorkersForTransaction();
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new DatasetFingerprint(
                rs.getInt("total_records"),
                rs.getTimestamp("last_modified").toInstant()
        ));
    }

    public record BookRow(String bookId, String slug, String title, Instant updatedAt) {}

    public record AuthorRow(String id, String name, Instant updatedAt) {}

    public record PageMetadata(int pageNumber, Instant lastModified) {}

    /**
     * Canonical route coordinates and freshness for one HTML author listing page.
     *
     * @param bucket route bucket in public URL form
     * @param htmlPage HTML listing page number within the bucket
     * @param lastModified latest known author or canonical-book change when available
     */
    public record AuthorListingMetadata(String bucket, int htmlPage, Optional<Instant> lastModified) {
        public AuthorListingMetadata {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(lastModified, "lastModified");
        }
    }

    public record DatasetFingerprint(int totalRecords, Instant lastModified) {}
}
