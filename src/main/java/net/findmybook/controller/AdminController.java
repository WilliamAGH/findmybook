/**
 * REST controller for administrative operations: S3 cleanup, scheduler triggers,
 * and circuit breaker management.
 */
package net.findmybook.controller;

import net.findmybook.application.book.RecommendationCacheRefreshUseCase;
import net.findmybook.boot.scheduler.WeeklyCatalogRefreshScheduler;
import net.findmybook.scheduler.BookCacheWarmingScheduler;
import net.findmybook.scheduler.NewYorkTimesBestsellerScheduler;
import net.findmybook.service.ApiCircuitBreakerService;
import net.findmybook.service.S3CoverCleanupService;
import net.findmybook.service.s3.DryRunSummary;
import net.findmybook.service.s3.MoveActionSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.function.Supplier;

@RestController
@RequestMapping("/admin")
@Slf4j
public class AdminController {

    /**
     * Externalized S3 cleanup configuration injected via {@code @Value} properties.
     */
    public record S3CleanupConfig(
        String prefix,
        int defaultBatchLimit,
        String quarantinePrefix
    ) {}

    private final S3CoverCleanupService s3CoverCleanupService;
    private final S3CleanupConfig s3CleanupConfig;
    private final NewYorkTimesBestsellerScheduler newYorkTimesBestsellerScheduler;
    private final WeeklyCatalogRefreshScheduler weeklyCatalogRefreshScheduler;
    private final RecommendationCacheRefreshUseCase recommendationCacheRefreshUseCase;
    private final BookCacheWarmingScheduler bookCacheWarmingScheduler;
    private final ApiCircuitBreakerService apiCircuitBreakerService;

    /** Constructs AdminController with optional S3 cleanup service and externalized config. */
    public AdminController(ObjectProvider<S3CoverCleanupService> s3CoverCleanupServiceProvider,
                           NewYorkTimesBestsellerScheduler newYorkTimesBestsellerScheduler,
                           WeeklyCatalogRefreshScheduler weeklyCatalogRefreshScheduler,
                           RecommendationCacheRefreshUseCase recommendationCacheRefreshUseCase,
                           BookCacheWarmingScheduler bookCacheWarmingScheduler,
                           ApiCircuitBreakerService apiCircuitBreakerService,
                           @Value("${app.s3.cleanup.prefix:images/book-covers/}") String configuredS3Prefix,
                           @Value("${app.s3.cleanup.default-batch-limit:100}") int defaultBatchLimit,
                           @Value("${app.s3.cleanup.quarantine-prefix:images/non-covers-pages/}") String configuredQuarantinePrefix) {
        this.s3CoverCleanupService = s3CoverCleanupServiceProvider.getIfAvailable();
        this.newYorkTimesBestsellerScheduler = newYorkTimesBestsellerScheduler;
        this.weeklyCatalogRefreshScheduler = weeklyCatalogRefreshScheduler;
        this.recommendationCacheRefreshUseCase = recommendationCacheRefreshUseCase;
        this.bookCacheWarmingScheduler = bookCacheWarmingScheduler;
        this.apiCircuitBreakerService = apiCircuitBreakerService;
        this.s3CleanupConfig = new S3CleanupConfig(configuredS3Prefix, defaultBatchLimit, configuredQuarantinePrefix);
    }

    /**
     * Triggers a dry run of the S3 cover cleanup process.
     *
     * @param prefixOptional optional S3 prefix override
     * @param limitOptional optional batch processing limit override
     * @return plain text summary and list of flagged files
     */
    @GetMapping(value = "/s3-cleanup/dry-run", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerS3CoverCleanupDryRun(
            @RequestParam(name = "prefix", required = false) String prefixOptional,
            @RequestParam(name = "limit", required = false) Integer limitOptional) {
        requireS3CleanupService();

        String prefixToUse = prefixOptional != null ? prefixOptional : s3CleanupConfig.prefix();
        int requestedLimit = limitOptional != null ? limitOptional : s3CleanupConfig.defaultBatchLimit();
        int batchLimitToUse = requestedLimit > 0 ? requestedLimit : Integer.MAX_VALUE;
        if (requestedLimit <= 0) {
            log.warn("Batch limit {} requested; treating as unlimited.", requestedLimit);
        }

        log.info("Admin dry-run invoked. prefix='{}', limit={}", prefixToUse, batchLimitToUse);
        try {
            DryRunSummary summary = s3CoverCleanupService.performDryRun(prefixToUse, batchLimitToUse);
            String responseBody = formatDryRunSummary(summary, prefixToUse, batchLimitToUse);
            log.info("Dry run complete: {} flagged / {} scanned.", summary.getTotalFlagged(), summary.getTotalScanned());
            return ResponseEntity.ok(responseBody);
        } catch (IllegalStateException ex) {
            log.error("Dry run failed for prefix='{}', limit={}: {}", prefixToUse, batchLimitToUse, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error during S3 Cover Cleanup Dry Run. Check server logs for details.", ex);
        }
    }

    /**
     * Moves flagged S3 cover images to a quarantine prefix.
     *
     * @param prefixOptional optional S3 source prefix override
     * @param limitOptional optional batch processing limit override
     * @param quarantinePrefixOptional optional quarantine prefix override
     * @return JSON summary of moved objects
     */
    @PostMapping(value = "/s3-cleanup/move-flagged", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MoveActionSummary> triggerS3CoverMoveAction(
            @RequestParam(name = "prefix", required = false) String prefixOptional,
            @RequestParam(name = "limit", required = false) Integer limitOptional,
            @RequestParam(name = "quarantinePrefix", required = false) String quarantinePrefixOptional) {

        requireS3CleanupService();

        String sourcePrefixToUse = prefixOptional != null ? prefixOptional : s3CleanupConfig.prefix();
        int batchLimitToUse = limitOptional != null ? limitOptional : s3CleanupConfig.defaultBatchLimit();
        String quarantinePrefixToUse = quarantinePrefixOptional != null ? quarantinePrefixOptional : s3CleanupConfig.quarantinePrefix();

        if (batchLimitToUse <= 0) {
            log.warn("Batch limit for move action specified as {} (or defaulted to it), processing all found items.", batchLimitToUse);
        }
        if (quarantinePrefixToUse.isEmpty() || quarantinePrefixToUse.equals(sourcePrefixToUse)) {
            String errorMsg = "Invalid quarantine prefix: cannot be empty or same as source prefix.";
            log.error(errorMsg + " Source: '{}', Quarantine: '{}'", sourcePrefixToUse, quarantinePrefixToUse);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMsg);
        }
        
        log.info("Admin move-flagged invoked. source='{}', limit={}, quarantine='{}'",
                sourcePrefixToUse, batchLimitToUse, quarantinePrefixToUse);

        try {
            MoveActionSummary summary =
                s3CoverCleanupService.performMoveAction(sourcePrefixToUse, batchLimitToUse, quarantinePrefixToUse);
            log.info("Move action completed. {}", summary);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid move action params (source='{}', quarantine='{}')", sourcePrefixToUse, quarantinePrefixToUse, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            log.error("Move action failed (source='{}', limit={})", sourcePrefixToUse, batchLimitToUse, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    /**
     * Triggers New York Times bestseller processing.
     * <p>
     * Modes:
     * <ul>
     *   <li>Default (no params): run the latest overview ingest once.</li>
     *   <li>{@code publishedDate=yyyy-MM-dd}: force one historical date ingest.</li>
     *   <li>{@code rerunAll=true}: rerun all historical NYT publication dates in Postgres.</li>
     * </ul>
     *
     * @param publishedDate optional NYT publication date to force
     * @param rerunAll when true, rerun all historical NYT publication dates
     * @return A ResponseEntity indicating the outcome of the trigger.
     */
    @PostMapping(value = "/trigger-nyt-bestsellers", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerNytBestsellerProcessing(
        @RequestParam(name = "publishedDate", required = false) LocalDate publishedDate,
        @RequestParam(name = "rerunAll", defaultValue = "false") boolean rerunAll
    ) {
        log.info("Admin endpoint /admin/trigger-nyt-bestsellers invoked. publishedDate={}, rerunAll={}",
            publishedDate,
            rerunAll);
        
        if (newYorkTimesBestsellerScheduler == null) {
            String errorMessage = "New York Times Bestseller Scheduler is not available. S3 integration may be disabled.";
            log.warn(errorMessage);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }

        if (rerunAll && publishedDate != null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Cannot combine rerunAll=true with a specific publishedDate."
            );
        }
        
        try {
            String successMessage;
            if (rerunAll) {
                NewYorkTimesBestsellerScheduler.HistoricalRerunSummary summary =
                    newYorkTimesBestsellerScheduler.rerunHistoricalBestsellers();
                successMessage = String.format(
                    "NYT historical rerun completed. totalDates=%d, succeeded=%d, failed=%d%s",
                    summary.totalDates(),
                    summary.succeededDates(),
                    summary.failedDates(),
                    summary.failures().isEmpty() ? "" : ", failures=" + summary.failures()
                );
            } else if (publishedDate != null) {
                newYorkTimesBestsellerScheduler.forceProcessNewYorkTimesBestsellers(publishedDate);
                successMessage = "Successfully triggered New York Times Bestseller processing job for "
                    + publishedDate
                    + ".";
            } else {
                newYorkTimesBestsellerScheduler.processNewYorkTimesBestsellers();
                successMessage = "Successfully triggered New York Times Bestseller processing job.";
            }
            log.info(successMessage);
            return ResponseEntity.ok(successMessage);
        } catch (IllegalStateException ex) {
            log.warn("New York Times Bestseller processing trigger rejected: {}", ex.getMessage(), ex);
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                ex.getMessage() != null ? ex.getMessage() : "Failed to trigger New York Times Bestseller processing job.",
                ex
            );
        }
    }

    /**
     * Triggers a full recommendation-cache expiration refresh.
     *
     * @return plain-text summary for operational diagnostics
     */
    @PostMapping(value = "/trigger-recommendation-refresh", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerRecommendationRefresh() {
        return executeAdminAction("trigger-recommendation-refresh", "recommendation refresh", () -> {
            RecommendationCacheRefreshUseCase.RefreshSummary summary =
                recommendationCacheRefreshUseCase.refreshAllRecommendations();
            return String.format(
                "Recommendation refresh completed. totalRows=%d, activeBefore=%d, refreshedRows=%d, activeAfter=%d, ttlDays=%d",
                summary.totalRows(),
                summary.activeRowsBefore(),
                summary.refreshedRows(),
                summary.activeRowsAfter(),
                summary.ttlDays()
            );
        });
    }

    /**
     * Triggers the weekly catalog refresh orchestration job immediately.
     *
     * @return plain-text summary for NYT and recommendation phases
     */
    @PostMapping(value = "/trigger-weekly-refresh", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerWeeklyRefresh() {
        return executeAdminAction("trigger-weekly-refresh", "weekly refresh", () -> {
            WeeklyCatalogRefreshScheduler.WeeklyRefreshSummary summary =
                weeklyCatalogRefreshScheduler.forceRunWeeklyRefreshCycle();
            return String.format(
                "Weekly refresh completed. nytTriggered=%s, recommendationTriggered=%s%s",
                summary.nytTriggered(),
                summary.recommendationTriggered(),
                summary.recommendationSummary()
                    .map(recommendation -> String.format(", recommendationRefreshedRows=%d", recommendation.refreshedRows()))
                    .orElse("")
            );
        });
    }

    /**
     * Triggers the book cache warming job.
     *
     * @return plain-text outcome summary
     */
    @PostMapping(value = "/trigger-cache-warming", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerCacheWarming() {
        return executeAdminAction("trigger-cache-warming", "book cache warming", () -> {
            bookCacheWarmingScheduler.warmPopularBookCaches();
            return "Successfully triggered book cache warming job.";
        });
    }

    /**
     * Gets the current status of the API circuit breaker.
     *
     * @return plain-text circuit breaker status
     */
    @GetMapping(value = "/circuit-breaker/status", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getCircuitBreakerStatus() {
        return executeAdminAction("circuit-breaker/status", "circuit breaker status retrieval", () ->
            apiCircuitBreakerService.getCircuitStatus()
        );
    }

    /**
     * Manually resets the API circuit breaker to CLOSED state.
     *
     * @return plain-text outcome confirmation
     */
    @PostMapping(value = "/circuit-breaker/reset", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resetCircuitBreaker() {
        return executeAdminAction("circuit-breaker/reset", "circuit breaker reset", () -> {
            apiCircuitBreakerService.reset();
            return "Successfully reset API circuit breaker to CLOSED state.";
        });
    }

    private void requireS3CleanupService() {
        if (s3CoverCleanupService == null) {
            String errorMessage = "S3 Cover Cleanup Service is not available. S3 integration may be disabled.";
            log.warn(errorMessage);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    private static String formatDryRunSummary(DryRunSummary summary, String prefix, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("S3 Cover Cleanup Dry Run completed for prefix: '%s', limit: %d.%n", prefix, limit));
        sb.append(String.format("Total Objects Scanned: %d, Total Objects Flagged: %d%n",
            summary.getTotalScanned(), summary.getTotalFlagged()));
        if (summary.getTotalFlagged() > 0) {
            sb.append(String.format("%nFlagged File Keys:%n"));
            for (String key : summary.getFlaggedFileKeys()) {
                sb.append(key).append(System.lineSeparator());
            }
        } else {
            sb.append(String.format("%nNo files were flagged.%n"));
        }
        return sb.toString();
    }

    private ResponseEntity<String> executeAdminAction(String endpointLabel,
                                                       String operationLabel,
                                                       Supplier<String> action) {
        log.info("Admin endpoint /admin/{} invoked.", endpointLabel);
        try {
            String result = action.get();
            log.info(result);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException exception) {
            log.error("Failed to trigger {}.", operationLabel, exception);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to trigger " + operationLabel + ".",
                exception
            );
        }
    }

}
