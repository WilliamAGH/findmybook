package net.findmybook.application.book;

import net.findmybook.adapters.persistence.RecommendationMaintenanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application use case that refreshes persisted recommendation cache expirations.
 *
 * <p>This workflow is intentionally persistence-agnostic at the caller boundary
 * and returns a typed summary for scheduler/admin diagnostics.</p>
 */
@Service
public class RecommendationCacheRefreshUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCacheRefreshUseCase.class);

    private final RecommendationMaintenanceRepository recommendationMaintenanceRepository;
    private final int ttlDays;

    public RecommendationCacheRefreshUseCase(RecommendationMaintenanceRepository recommendationMaintenanceRepository,
                                             @Value("${app.recommendations.refresh.ttl-days:30}") int ttlDays) {
        this.recommendationMaintenanceRepository = recommendationMaintenanceRepository;
        if (ttlDays < 1) {
            throw new IllegalArgumentException("app.recommendations.refresh.ttl-days must be >= 1");
        }
        this.ttlDays = ttlDays;
    }

    /**
     * Executes a full recommendation-cache expiry refresh.
     *
     * @return summary of row counts before and after refresh execution
     */
    @Transactional
    public RefreshSummary refreshAllRecommendations() {
        long totalRows = recommendationMaintenanceRepository.countRecommendationRows();
        long activeRowsBefore = recommendationMaintenanceRepository.countActiveRecommendationRows();
        int refreshedRows = recommendationMaintenanceRepository.refreshAllRecommendationExpirations(ttlDays);
        long activeRowsAfter = recommendationMaintenanceRepository.countActiveRecommendationRows();

        RefreshSummary summary = new RefreshSummary(
            totalRows,
            activeRowsBefore,
            refreshedRows,
            activeRowsAfter,
            ttlDays
        );
        log.info(
            "Recommendation cache refresh completed: totalRows={}, activeBefore={}, refreshedRows={}, activeAfter={}, ttlDays={}",
            summary.totalRows(),
            summary.activeRowsBefore(),
            summary.refreshedRows(),
            summary.activeRowsAfter(),
            summary.ttlDays()
        );
        return summary;
    }

    /**
     * Immutable diagnostic payload for a recommendation refresh execution.
     *
     * @param totalRows number of persisted recommendation rows before refresh
     * @param activeRowsBefore active recommendation rows before refresh
     * @param refreshedRows number of rows touched by the refresh update
     * @param activeRowsAfter active recommendation rows after refresh
     * @param ttlDays TTL window applied during refresh
     */
    public record RefreshSummary(long totalRows,
                                 long activeRowsBefore,
                                 int refreshedRows,
                                 long activeRowsAfter,
                                 int ttlDays) {
    }
}
