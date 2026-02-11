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

import net.findmybook.application.cover.BackfillMode;
import net.findmybook.application.cover.BackfillProgress;
import net.findmybook.application.cover.CoverBackfillService;
import net.findmybook.scheduler.BookCacheWarmingScheduler;
import net.findmybook.scheduler.NewYorkTimesBestsellerScheduler;
import net.findmybook.service.ApiCircuitBreakerService;
import net.findmybook.service.BackfillCoordinator;
import net.findmybook.service.S3CoverCleanupService;
import net.findmybook.service.s3.DryRunSummary;
import net.findmybook.service.s3.MoveActionSummary;
import org.springframework.util.StringUtils;
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
    private final BookCacheWarmingScheduler bookCacheWarmingScheduler;
    private final ApiCircuitBreakerService apiCircuitBreakerService;
    private final BackfillCoordinator backfillCoordinator;
    private final CoverBackfillService coverBackfillService;

    /**
     * Constructs AdminController with optional services that may be null when disabled.
     *
     * @param s3CoverCleanupService the S3 cleanup service, or null if S3 is disabled
     * @param newYorkTimesBestsellerScheduler the NYT scheduler, or null if disabled
     * @param backfillCoordinatorProvider provider for optional backfill coordinator bean
     * @param bookCacheWarmingScheduler the cache warming scheduler
     * @param apiCircuitBreakerService the circuit breaker service
     * @param configuredS3Prefix configured source prefix for S3 cleanup
     * @param defaultBatchLimit configured default batch size for cleanup operations
     * @param configuredQuarantinePrefix configured quarantine prefix for moved objects
     */
    public AdminController(ObjectProvider<S3CoverCleanupService> s3CoverCleanupServiceProvider,
                           NewYorkTimesBestsellerScheduler newYorkTimesBestsellerScheduler,
                           ObjectProvider<BackfillCoordinator> backfillCoordinatorProvider,
                           BookCacheWarmingScheduler bookCacheWarmingScheduler,
                           ApiCircuitBreakerService apiCircuitBreakerService,
                           CoverBackfillService coverBackfillService,
                           @Value("${app.s3.cleanup.prefix:images/book-covers/}") String configuredS3Prefix,
                           @Value("${app.s3.cleanup.default-batch-limit:100}") int defaultBatchLimit,
                           @Value("${app.s3.cleanup.quarantine-prefix:images/non-covers-pages/}") String configuredQuarantinePrefix) {
        this.s3CoverCleanupService = s3CoverCleanupServiceProvider.getIfAvailable();
        this.newYorkTimesBestsellerScheduler = newYorkTimesBestsellerScheduler;
        this.bookCacheWarmingScheduler = bookCacheWarmingScheduler;
        this.apiCircuitBreakerService = apiCircuitBreakerService;
        this.coverBackfillService = coverBackfillService;
        this.s3CleanupConfig = new S3CleanupConfig(configuredS3Prefix, defaultBatchLimit, configuredQuarantinePrefix);
        this.backfillCoordinator = backfillCoordinatorProvider.getIfAvailable();
    }

    /**
     * Enqueue a Google Books backfill task for a specific volume ID.
     * Useful for refreshing cover metadata after ingestion fixes.
     *
     * @param volumeId Google Books volume identifier
     * @param priority Optional priority override (1 = highest, 10 = lowest)
     * @return text response describing the enqueued task
     */
    @PostMapping(value = "/backfill/google-volume", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> enqueueGoogleVolumeBackfill(
            @RequestParam("volumeId") String volumeId,
            @RequestParam(name = "priority", defaultValue = "3") int priority) {

        if (backfillCoordinator == null) {
            String message = "Async backfill is disabled. Set APP_FEATURE_ASYNC_BACKFILL_ENABLED=true to enable.";
            log.warn(message);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }

        if (!StringUtils.hasText(volumeId)) {
            String message = "volumeId must not be blank";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }

        int clampedPriority = Math.clamp(priority, 1, 10);
        String normalizedVolume = volumeId.trim();

        backfillCoordinator.enqueue("GOOGLE_BOOKS", normalizedVolume, clampedPriority);

        String message = String.format("Enqueued GOOGLE_BOOKS backfill for %s with priority %d",
            normalizedVolume, clampedPriority);
        log.info(message);
        return ResponseEntity.ok(message);
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

    // ── Cover backfill endpoints ────────────────────────────────────────

    /**
     * Starts an asynchronous cover backfill run.
     *
     * @param mode  {@code missing} (default), {@code grayscale}, or {@code rejected}
     * @param limit maximum number of books to process (default 100)
     * @return acknowledgement message
     */
    @PostMapping(value = "/backfill/covers", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> startCoverBackfill(
            @RequestParam(name = "mode", defaultValue = "missing") String mode,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {

        if (coverBackfillService.isRunning()) {
            BackfillProgress current = coverBackfillService.getProgress();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A cover backfill is already running (" + current.processed() + "/" + current.totalCandidates() + ")");
        }

        BackfillMode backfillMode = switch (mode.toLowerCase()) {
            case "missing" -> BackfillMode.MISSING;
            case "grayscale" -> BackfillMode.GRAYSCALE;
            case "rejected" -> BackfillMode.REJECTED;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid backfill mode: '" + mode + "'. Supported values: missing, grayscale, rejected");
        };

        int clampedLimit = Math.clamp(limit, 1, 10_000);
        coverBackfillService.runBackfill(backfillMode, clampedLimit);

        String message = String.format("Cover backfill started: mode=%s, limit=%d", backfillMode, clampedLimit);
        log.info(message);
        return ResponseEntity.accepted().body(message);
    }

    /**
     * Returns the current progress of a running (or last completed) cover backfill.
     *
     * @return progress snapshot as JSON
     */
    @GetMapping(value = "/backfill/covers/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BackfillProgress> getCoverBackfillStatus() {
        return ResponseEntity.ok(coverBackfillService.getProgress());
    }

    /**
     * Requests cancellation of the running cover backfill.
     *
     * @return acknowledgement message
     */
    @PostMapping(value = "/backfill/covers/cancel", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> cancelCoverBackfill() {
        coverBackfillService.cancel();
        return ResponseEntity.ok("Cover backfill cancellation requested");
    }
}
