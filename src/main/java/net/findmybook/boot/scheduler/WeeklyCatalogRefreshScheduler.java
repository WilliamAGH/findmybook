package net.findmybook.boot.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.findmybook.application.book.RecommendationCacheRefreshUseCase;
import net.findmybook.scheduler.NewYorkTimesBestsellerScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly orchestration scheduler for catalog freshness workflows.
 *
 * <p>Runs NYT ingest and recommendation-cache refresh in one cycle so operational
 * visibility and failure handling stay centralized.</p>
 */
@Component
public class WeeklyCatalogRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyCatalogRefreshScheduler.class);

    private final NewYorkTimesBestsellerScheduler newYorkTimesBestsellerScheduler;
    private final RecommendationCacheRefreshUseCase recommendationCacheRefreshUseCase;
    private final boolean schedulerEnabled;
    private final boolean nytPhaseEnabled;
    private final boolean recommendationPhaseEnabled;

    public WeeklyCatalogRefreshScheduler(NewYorkTimesBestsellerScheduler newYorkTimesBestsellerScheduler,
                                         RecommendationCacheRefreshUseCase recommendationCacheRefreshUseCase,
                                         SchedulerConfiguration config) {
        this.newYorkTimesBestsellerScheduler = newYorkTimesBestsellerScheduler;
        this.recommendationCacheRefreshUseCase = recommendationCacheRefreshUseCase;
        this.schedulerEnabled = config.schedulerEnabled();
        this.nytPhaseEnabled = config.nytPhaseEnabled();
        this.recommendationPhaseEnabled = config.recommendationPhaseEnabled();
    }

    @Component
    public static class ConfigLoader {
        @Bean
        public SchedulerConfiguration schedulerConfiguration(
            @Value("${app.weekly-refresh.enabled:true}") boolean schedulerEnabled,
            @Value("${app.weekly-refresh.nyt-phase-enabled:true}") boolean nytPhaseEnabled,
            @Value("${app.weekly-refresh.recommendation-phase-enabled:true}") boolean recommendationPhaseEnabled
        ) {
            return new SchedulerConfiguration(schedulerEnabled, nytPhaseEnabled, recommendationPhaseEnabled);
        }
    }

    public record SchedulerConfiguration(boolean schedulerEnabled, boolean nytPhaseEnabled, boolean recommendationPhaseEnabled) {}

    /**
     * Executes the weekly refresh cycle on schedule.
     *
     * <p>Failures are logged at {@code ERROR} and surfaced via an exception so
     * scheduler telemetry clearly marks the run as failed.</p>
     */
    @Scheduled(cron = "${app.weekly-refresh.cron:0 0 4 * * SUN}")
    public void runWeeklyRefreshCycle() {
        runWeeklyRefreshCycle(false);
    }

    /**
     * Forces an immediate weekly refresh cycle regardless of scheduler-enabled flags.
     *
     * @return summary of phase execution outcomes
     */
    public WeeklyRefreshSummary forceRunWeeklyRefreshCycle() {
        return runWeeklyRefreshCycle(true);
    }

    private WeeklyRefreshSummary runWeeklyRefreshCycle(boolean forceExecution) {
        if (!forceExecution && !schedulerEnabled) {
            log.info("Weekly catalog refresh scheduler is disabled via configuration.");
            return new WeeklyRefreshSummary(false, false, Optional.empty(), List.of());
        }

        boolean nytTriggered = false;
        boolean recommendationTriggered = false;
        Optional<RecommendationCacheRefreshUseCase.RefreshSummary> recommendationSummary = Optional.empty();
        List<String> failures = new ArrayList<>();

        if (nytPhaseEnabled) {
            try {
                newYorkTimesBestsellerScheduler.forceProcessNewYorkTimesBestsellers();
                nytTriggered = true;
                log.info("Weekly catalog refresh completed NYT phase successfully.");
            } catch (RuntimeException exception) {
                log.error("Weekly catalog refresh NYT phase failed.", exception);
                failures.add("NYT phase failed: " + resolveFailureMessage(exception));
            }
        } else {
            log.info("Weekly catalog refresh skipped NYT phase because it is disabled.");
        }

        if (recommendationPhaseEnabled) {
            try {
                RecommendationCacheRefreshUseCase.RefreshSummary refreshedSummary =
                    recommendationCacheRefreshUseCase.refreshAllRecommendations();
                recommendationSummary = Optional.of(refreshedSummary);
                recommendationTriggered = true;
                log.info(
                    "Weekly catalog refresh completed recommendation phase successfully (refreshedRows={}).",
                    refreshedSummary.refreshedRows()
                );
            } catch (RuntimeException exception) {
                log.error("Weekly catalog refresh recommendation phase failed.", exception);
                failures.add("Recommendation phase failed: " + resolveFailureMessage(exception));
            }
        } else {
            log.info("Weekly catalog refresh skipped recommendation phase because it is disabled.");
        }

        WeeklyRefreshSummary summary = new WeeklyRefreshSummary(
            nytTriggered,
            recommendationTriggered,
            recommendationSummary,
            List.copyOf(failures)
        );
        if (!summary.failures().isEmpty()) {
            throw new IllegalStateException("Weekly catalog refresh failed: " + String.join(" | ", summary.failures()));
        }
        return summary;
    }

    private static String resolveFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * Immutable summary of one weekly refresh execution.
     *
     * @param nytTriggered whether NYT phase executed successfully
     * @param recommendationTriggered whether recommendation phase executed successfully
     * @param recommendationSummary recommendation-phase row metrics when phase runs
     * @param failures phase-level failure details
     */
    public record WeeklyRefreshSummary(boolean nytTriggered,
                                       boolean recommendationTriggered,
                                       Optional<RecommendationCacheRefreshUseCase.RefreshSummary> recommendationSummary,
                                       List<String> failures) {
    }
}
