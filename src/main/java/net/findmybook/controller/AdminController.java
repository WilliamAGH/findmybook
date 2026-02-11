/**
 * REST Controller for administrative operations
 * 
 * @author William Callahan
 * 
 * Features:
 * - Provides endpoints for S3 cover image cleanup operations
 * - Supports dry run mode for evaluating cleanup impact
 * - Handles moving flagged images to quarantine
 * - Configurable batch processing limits
 * - Detailed logging and error handling
 * - Returns operation summaries in text and JSON formats
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

    /**
     * Constructs AdminController with optional services that may be null when disabled.
     *
     * @param s3CoverCleanupServiceProvider provider for the S3 cleanup service
     * @param newYorkTimesBestsellerScheduler the NYT scheduler, or null if disabled
     * @param weeklyCatalogRefreshScheduler scheduler that orchestrates weekly NYT + recommendation refresh
     * @param recommendationCacheRefreshUseCase use case for full recommendation-cache expiry refresh
     * @param bookCacheWarmingScheduler the cache warming scheduler
     * @param apiCircuitBreakerService the circuit breaker service
     * @param configuredS3Prefix configured source prefix for S3 cleanup
     * @param defaultBatchLimit configured default batch size for cleanup operations
     * @param configuredQuarantinePrefix configured quarantine prefix for moved objects
     */
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
     * Triggers a dry run of the S3 cover cleanup process
     * The S3 prefix to scan can be overridden by a request parameter,
     * otherwise, the configured 'app.s3.cleanup.prefix' is used
     * The number of items to process can be limited by a request parameter,
     * otherwise, the configured 'app.s3.cleanup.default-batch-limit' is used
     *
     * @param prefixOptional Optional request parameter to override the S3 prefix
     * @param limitOptional Optional request parameter to override the batch processing limit
     * @return A ResponseEntity containing a plain text summary and list of flagged files
     */
    @GetMapping(value = "/s3-cleanup/dry-run", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerS3CoverCleanupDryRun(
            @RequestParam(name = "prefix", required = false) String prefixOptional,
            @RequestParam(name = "limit", required = false) Integer limitOptional) {
        
        if (s3CoverCleanupService == null) {
            String errorMessage = "S3 Cover Cleanup Service is not available. S3 integration may be disabled.";
            log.warn(errorMessage);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        
        String prefixToUse = prefixOptional != null ? prefixOptional : s3CleanupConfig.prefix();
        int requestedLimit     = limitOptional != null ? limitOptional : s3CleanupConfig.defaultBatchLimit();
        int batchLimitToUse    = requestedLimit > 0 ? requestedLimit : Integer.MAX_VALUE;
        if (requestedLimit <= 0) {
            log.warn("Batch limit {} requested; treating as unlimited.", requestedLimit);
        }
        
        log.info("Admin endpoint /admin/s3-cleanup/dry-run invoked. Triggering S3 Cover Cleanup Dry Run with prefix: '{}', limit: {}", prefixToUse, batchLimitToUse);

        // Note: This is a synchronous call. For very long operations,
        // consider making performDryRun @Async or wrapping this call
        try {
            DryRunSummary summary = s3CoverCleanupService.performDryRun(prefixToUse, batchLimitToUse);
            
            StringBuilder responseBuilder = new StringBuilder();
            responseBuilder.append(String.format(
                "S3 Cover Cleanup Dry Run completed for prefix: '%s', limit: %d.%n",
                prefixToUse, batchLimitToUse
            ));
            responseBuilder.append(String.format(
                "Total Objects Scanned: %d, Total Objects Flagged: %d%n",
                summary.getTotalScanned(), summary.getTotalFlagged()
            ));

            if (summary.getTotalFlagged() > 0) {
                responseBuilder.append(String.format("%nFlagged File Keys:%n"));
                for (String key : summary.getFlaggedFileKeys()) {
                    responseBuilder.append(String.format("%s%n", key));
                }
            } else {
                responseBuilder.append(String.format("%nNo files were flagged.%n"));
            }
            
            String responseBody = responseBuilder.toString();
            log.info("S3 Cover Cleanup Dry Run response prepared for prefix: '{}', limit: {}. Summary: {} flagged out of {} scanned.", 
                        prefixToUse, batchLimitToUse, summary.getTotalFlagged(), summary.getTotalScanned());
            return ResponseEntity.ok(responseBody);
        } catch (IllegalStateException ex) {
            String errorMessage = String.format(
                "Failed to complete S3 Cover Cleanup Dry Run with prefix: '%s', limit: %d.",
                prefixToUse,
                batchLimitToUse
            );
            log.error("{} Cause: {}", errorMessage, ex.getMessage(), ex);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error during S3 Cover Cleanup Dry Run. Check server logs for details.",
                ex
            );
        }
    }

    /**
     * Triggers the action of moving flagged S3 cover images to a quarantine prefix
     *
     * @param prefixOptional Optional request parameter to override the S3 source prefix
     * @param limitOptional Optional request parameter to override the batch processing limit
     * @param quarantinePrefixOptional Optional request parameter to override the quarantine prefix
     * @return A ResponseEntity containing the MoveActionSummary as JSON
     */
    @PostMapping(value = "/s3-cleanup/move-flagged", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> triggerS3CoverMoveAction(
            @RequestParam(name = "prefix", required = false) String prefixOptional,
            @RequestParam(name = "limit", required = false) Integer limitOptional,
            @RequestParam(name = "quarantinePrefix", required = false) String quarantinePrefixOptional) {

        if (s3CoverCleanupService == null) {
            String errorMessage = "S3 Cover Cleanup Service is not available. S3 integration may be disabled.";
            log.warn(errorMessage);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }

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
        
        log.info("Admin endpoint /admin/s3-cleanup/move-flagged invoked. " +
                        "Source Prefix: '{}', Limit: {}, Quarantine Prefix: '{}'",
                sourcePrefixToUse, batchLimitToUse, quarantinePrefixToUse);

        try {
            MoveActionSummary summary = 
                s3CoverCleanupService.performMoveAction(sourcePrefixToUse, batchLimitToUse, quarantinePrefixToUse);
            
            log.info("S3 Cover Cleanup Move Action completed. Summary: {}", summary.toString());
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException ex) {
            String errorMessage = String.format(
                "Invalid move action parameters. Source Prefix: '%s', Limit: %d, Quarantine Prefix: '%s'.",
                sourcePrefixToUse, batchLimitToUse, quarantinePrefixToUse
            );
            log.warn(errorMessage, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage, ex);
        } catch (IllegalStateException ex) {
            String errorMessage = String.format(
                "Failed to complete S3 Cover Cleanup Move Action. Source Prefix: '%s', Limit: %d, Quarantine Prefix: '%s'.",
                sourcePrefixToUse, batchLimitToUse, quarantinePrefixToUse
            );
            log.error(errorMessage, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, errorMessage, ex);
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
        log.info("Admin endpoint /admin/trigger-recommendation-refresh invoked.");
        try {
            RecommendationCacheRefreshUseCase.RefreshSummary summary =
                recommendationCacheRefreshUseCase.refreshAllRecommendations();
            String successMessage = String.format(
                "Recommendation refresh completed. totalRows=%d, activeBefore=%d, refreshedRows=%d, activeAfter=%d, ttlDays=%d",
                summary.totalRows(),
                summary.activeRowsBefore(),
                summary.refreshedRows(),
                summary.activeRowsAfter(),
                summary.ttlDays()
            );
            log.info(successMessage);
            return ResponseEntity.ok(successMessage);
        } catch (IllegalStateException exception) {
            log.error("Recommendation refresh failed.", exception);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to trigger recommendation refresh.",
                exception
            );
        }
    }

    /**
     * Triggers the weekly catalog refresh orchestration job immediately.
     *
     * @return plain-text summary for NYT and recommendation phases
     */
    @PostMapping(value = "/trigger-weekly-refresh", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerWeeklyRefresh() {
        log.info("Admin endpoint /admin/trigger-weekly-refresh invoked.");
        try {
            WeeklyCatalogRefreshScheduler.WeeklyRefreshSummary summary =
                weeklyCatalogRefreshScheduler.forceRunWeeklyRefreshCycle();
            String successMessage = String.format(
                "Weekly refresh completed. nytTriggered=%s, recommendationTriggered=%s%s",
                summary.nytTriggered(),
                summary.recommendationTriggered(),
                summary.recommendationSummary() == null
                    ? ""
                    : String.format(
                        ", recommendationRefreshedRows=%d",
                        summary.recommendationSummary().refreshedRows()
                    )
            );
            log.info(successMessage);
            return ResponseEntity.ok(successMessage);
        } catch (IllegalStateException exception) {
            log.error("Weekly refresh trigger failed.", exception);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to trigger weekly refresh.",
                exception
            );
        }
    }

    /**
     * Triggers the Book Cache Warming job.
     *
     * @return A ResponseEntity indicating the outcome of the trigger.
     */
    @PostMapping(value = "/trigger-cache-warming", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> triggerCacheWarming() {
        log.info("Admin endpoint /admin/trigger-cache-warming invoked.");
        try {
            bookCacheWarmingScheduler.warmPopularBookCaches();
            String successMessage = "Successfully triggered book cache warming job.";
            log.info(successMessage);
            return ResponseEntity.ok(successMessage);
        } catch (IllegalStateException ex) {
            log.error("Failed to trigger book cache warming job.", ex);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to trigger book cache warming job.",
                ex
            );
        }
    }

    /**
     * Gets the current status of the API circuit breaker.
     *
     * @return A ResponseEntity containing the circuit breaker status
     */
    @GetMapping(value = "/circuit-breaker/status", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getCircuitBreakerStatus() {
        log.info("Admin endpoint /admin/circuit-breaker/status invoked.");
        try {
            String status = apiCircuitBreakerService.getCircuitStatus();
            log.info("Circuit breaker status retrieved: {}", status);
            return ResponseEntity.ok(status);
        } catch (IllegalStateException ex) {
            log.error("Failed to get circuit breaker status.", ex);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to get circuit breaker status.",
                ex
            );
        }
    }

    /**
     * Manually resets the API circuit breaker to CLOSED state.
     *
     * @return A ResponseEntity indicating the outcome of the reset
     */
    @PostMapping(value = "/circuit-breaker/reset", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resetCircuitBreaker() {
        log.info("Admin endpoint /admin/circuit-breaker/reset invoked.");
        try {
            apiCircuitBreakerService.reset();
            String successMessage = "Successfully reset API circuit breaker to CLOSED state.";
            log.info(successMessage);
            return ResponseEntity.ok(successMessage);
        } catch (IllegalStateException ex) {
            log.error("Failed to reset circuit breaker.", ex);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to reset circuit breaker.",
                ex
            );
        }
    }

}
