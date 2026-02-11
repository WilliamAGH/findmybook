package net.findmybook.controller;

import net.findmybook.application.cover.BackfillMode;
import net.findmybook.application.cover.BackfillProgress;
import net.findmybook.application.cover.CoverBackfillService;
import net.findmybook.service.BackfillCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin endpoints for Google volume and cover image backfill operations.
 *
 * <p>Separated from {@link AdminController} to keep both controllers under the
 * 350-line file-size ceiling while reusing the shared {@code /admin/**} security rule.</p>
 */
@RestController
@RequestMapping("/admin")
public class BackfillAdminController {

    private static final Logger log = LoggerFactory.getLogger(BackfillAdminController.class);

    private final BackfillCoordinator backfillCoordinator;
    private final CoverBackfillService coverBackfillService;

    /**
     * Creates the backfill admin controller.
     *
     * @param backfillCoordinatorProvider optional coordinator for async Google volume backfill
     * @param coverBackfillService service that runs cover image backfill batches
     */
    public BackfillAdminController(ObjectProvider<BackfillCoordinator> backfillCoordinatorProvider,
                                    CoverBackfillService coverBackfillService) {
        this.backfillCoordinator = backfillCoordinatorProvider.getIfAvailable();
        this.coverBackfillService = coverBackfillService;
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
