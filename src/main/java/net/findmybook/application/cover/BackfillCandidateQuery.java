package net.findmybook.application.cover;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Queries candidate books for cover backfill based on the requested mode.
 *
 * <p>Extracted from {@link CoverBackfillService} to keep SQL query construction
 * isolated from batch-processing orchestration.</p>
 */
@Component
class BackfillCandidateQuery {

    private final JdbcTemplate jdbcTemplate;

    BackfillCandidateQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns candidate books that need cover backfill in the given mode.
     *
     * @param mode  which books to target (missing, grayscale, or rejected covers)
     * @param limit maximum candidates to return
     * @return ordered candidate list, newest first
     */
    List<BackfillCandidate> queryCandidates(BackfillMode mode, int limit) {
        String sql = switch (mode) {
            case MISSING -> """
                SELECT b.id, b.title, b.isbn13, b.isbn10
                FROM books b
                WHERE (b.isbn13 IS NOT NULL OR b.isbn10 IS NOT NULL)
                  AND NOT EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NULL
                        AND ((bil.url IS NOT NULL AND bil.url <> '')
                             OR (bil.s3_image_path IS NOT NULL AND bil.s3_image_path <> ''))
                  )
                ORDER BY b.created_at DESC
                LIMIT ?
                """;
            case GRAYSCALE -> """
                SELECT b.id, b.title, b.isbn13, b.isbn10
                FROM books b
                WHERE (b.isbn13 IS NOT NULL OR b.isbn10 IS NOT NULL)
                  AND EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id AND bil.is_grayscale = true
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NULL
                        AND COALESCE(bil.is_grayscale, false) = false
                        AND ((bil.url IS NOT NULL AND bil.url <> '')
                             OR (bil.s3_image_path IS NOT NULL AND bil.s3_image_path <> ''))
                  )
                ORDER BY b.created_at DESC
                LIMIT ?
                """;
            case REJECTED -> """
                SELECT b.id, b.title, b.isbn13, b.isbn10
                FROM books b
                WHERE (b.isbn13 IS NOT NULL OR b.isbn10 IS NOT NULL)
                  AND EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NOT NULL
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NULL
                        AND bil.s3_image_path IS NOT NULL
                        AND bil.s3_image_path <> ''
                  )
                ORDER BY b.created_at DESC
                LIMIT ?
                """;
        };

        return jdbcTemplate.query(sql, (rs, rowNum) -> new BackfillCandidate(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("isbn13"),
            rs.getString("isbn10")
        ), limit);
    }
}
