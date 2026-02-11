package net.findmybook.adapters.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres adapter for recommendation-cache maintenance operations.
 *
 * <p>Owns SQL required by scheduled refresh workflows so application-layer
 * orchestration does not depend on raw query text.</p>
 */
@Repository
public class RecommendationMaintenanceRepository {

    private static final Logger log = LoggerFactory.getLogger(RecommendationMaintenanceRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public RecommendationMaintenanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Counts all recommendation-cache rows.
     *
     * @return total persisted recommendation rows
     */
    @Transactional(readOnly = true)
    public long countRecommendationRows() {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM book_recommendations",
            Long.class
        );
        return count == null ? 0L : count;
    }

    /**
     * Counts recommendation rows that are currently active.
     *
     * @return number of rows where {@code expires_at} is absent or in the future
     */
    @Transactional(readOnly = true)
    public long countActiveRecommendationRows() {
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM book_recommendations
            WHERE expires_at IS NULL OR expires_at > NOW()
            """,
            Long.class
        );
        return count == null ? 0L : count;
    }

    /**
     * Refreshes expiry timestamps for every cached recommendation row.
     *
     * @param ttlDays number of days recommendations remain active
     * @return number of updated rows
     */
    @Transactional
    public int refreshAllRecommendationExpirations(int ttlDays) {
        if (ttlDays < 1) {
            throw new IllegalArgumentException("ttlDays must be greater than or equal to 1");
        }
        try {
            return jdbcTemplate.update(
                """
                UPDATE book_recommendations
                SET expires_at = NOW() + (? * INTERVAL '1 day')
                """,
                ttlDays
            );
        } catch (RuntimeException exception) {
            log.error("Failed to refresh recommendation expirations for ttlDays={}", ttlDays, exception);
            throw new IllegalStateException(
                "Failed to refresh recommendation expirations for ttlDays=" + ttlDays,
                exception
            );
        }
    }
}
