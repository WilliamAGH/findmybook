package net.findmybook.scheduler;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to maintain work_clusters for edition grouping.
 * Runs clustering functions periodically to group books into edition families.
 * 
 * @author William Callahan
 */
@Component
@ConditionalOnBean(JdbcTemplate.class)
public class WorkClusterScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkClusterScheduler.class);
    private static final String ISBN_CLUSTER_SQL = "SELECT * FROM cluster_books_by_isbn()";
    private static final String GOOGLE_CLUSTER_SQL = "SELECT * FROM cluster_books_by_google_canonical()";
    private static final String GOOGLE_CLUSTER_FUNCTION_NAME = "cluster_books_by_google_canonical";
    private static final String MEMBER_COUNT_CONSTRAINT_NAME = "check_reasonable_member_count";
    private static final Integer[] ZERO_CLUSTER_RESULTS = new Integer[] {0, 0};
    private static final String DB_MIGRATION_COMMAND = "make db-migrate";
    
    private final JdbcTemplate jdbcTemplate;

    public WorkClusterScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs work clustering every 6 hours to group newly added books into edition families.
     * Clusters books by:
     * 1. ISBN prefix (same publisher/work)
     * 2. Google Books canonical volume ID
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 5 * 60 * 1000) // Every 6 hours, start after 5 min
    public void clusterBooks() {
        LOGGER.info("Starting scheduled work clustering...");

        Integer[] isbnResults = runRequiredClustering(ISBN_CLUSTER_SQL, "ISBN");
        Integer[] googleResults = runGoogleCanonicalClustering();

        if (isbnResults != null && googleResults != null) {
            int totalClusters = isbnResults[0] + googleResults[0];
            int totalBooks = isbnResults[1] + googleResults[1];
            LOGGER.info(
                "Work clustering completed: {} clusters created/updated, {} books grouped into editions",
                totalClusters,
                totalBooks
            );
        }
    }

    private Integer[] runRequiredClustering(String clusteringSql, String clusteringName) {
        try {
            return jdbcTemplate.queryForObject(
                clusteringSql,
                (rs, rowNum) -> new Integer[]{rs.getInt("clusters_created"), rs.getInt("books_clustered")}
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                "Failed to run " + clusteringName + " work clustering",
                exception
            );
        }
    }

    private Integer[] runGoogleCanonicalClustering() {
        try {
            return jdbcTemplate.queryForObject(
                GOOGLE_CLUSTER_SQL,
                (rs, rowNum) -> new Integer[]{rs.getInt("clusters_created"), rs.getInt("books_clustered")}
            );
        } catch (DataAccessException exception) {
            if (isKnownGoogleCanonicalConstraintFailure(exception)) {
                LOGGER.error(
                    "Skipping Google canonical clustering for this scheduler cycle due to stale database "
                        + "function definition. Apply the latest schema with '{}' to repair "
                        + "cluster_books_by_google_canonical() and rerun clustering.",
                    DB_MIGRATION_COMMAND,
                    exception
                );
                return ZERO_CLUSTER_RESULTS;
            }
            throw new IllegalStateException("Failed to run Google canonical work clustering", exception);
        }
    }

    private boolean isKnownGoogleCanonicalConstraintFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                boolean hasConstraint = normalized.contains(MEMBER_COUNT_CONSTRAINT_NAME);
                boolean hasFunction = normalized.contains(GOOGLE_CLUSTER_FUNCTION_NAME);
                if (hasConstraint && hasFunction) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Get current clustering statistics on demand.
     */
    public void logClusteringStats() {
        try {
            jdbcTemplate.query(
                "SELECT * FROM get_clustering_stats()",
                rs -> {
                    LOGGER.info("Clustering stats: {} total books, {} clustered, {} unclustered, {} total clusters, avg {}/cluster",
                        rs.getLong("total_books"),
                        rs.getLong("clustered_books"),
                        rs.getLong("unclustered_books"),
                        rs.getLong("total_clusters"),
                        rs.getBigDecimal("avg_cluster_size")
                    );
                }
            );
        } catch (DataAccessException e) {
            throw new IllegalStateException("Failed to fetch clustering stats", e);
        }
    }
}
