package net.findmybook.application.cover;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.exception.CoverDownloadException;
import net.findmybook.exception.CoverProcessingException;
import net.findmybook.exception.CoverTooLargeException;
import net.findmybook.exception.S3CoverUploadException;
import net.findmybook.exception.S3UploadException;
import net.findmybook.exception.UnsafeUrlException;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.service.image.S3BookCoverService;
import net.findmybook.support.cover.CoverImageUrlSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Coordinates asynchronous cover uploads to S3 after book-upsert events.
 *
 * <p>This service owns retry policy, metrics, and persistence hand-off so realtime
 * event listeners can stay focused on message fan-out.</p>
 */
@Service
public class CoverS3UploadCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(CoverS3UploadCoordinator.class);

    private static final int MAX_UPLOAD_RETRIES = 3;
    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(10);

    private final Optional<S3BookCoverService> s3BookCoverService;
    private final CoverPersistenceService coverPersistenceService;

    private final Counter s3UploadAttempts;
    private final Counter s3UploadSuccesses;
    private final Counter s3UploadFailures;
    private final Counter s3UploadRetriesTotal;
    private final Timer s3UploadDuration;

    /**
     * Creates the upload coordinator with S3, persistence, and metrics collaborators.
     */
    public CoverS3UploadCoordinator(Optional<S3BookCoverService> s3BookCoverService,
                                    CoverPersistenceService coverPersistenceService,
                                    MeterRegistry meterRegistry) {
        this.s3BookCoverService = s3BookCoverService != null ? s3BookCoverService : Optional.empty();
        this.coverPersistenceService = coverPersistenceService;

        this.s3UploadAttempts = meterRegistry.counter("book.cover.s3.upload.attempts");
        this.s3UploadSuccesses = meterRegistry.counter("book.cover.s3.upload.success");
        this.s3UploadFailures = meterRegistry.counter("book.cover.s3.upload.failure");
        this.s3UploadRetriesTotal = meterRegistry.counter("book.cover.s3.upload.retries");
        this.s3UploadDuration = meterRegistry.timer("book.cover.s3.upload.duration");
    }

    /**
     * Starts the S3 upload pipeline for the provided upsert event, when canonical cover data exists.
     */
    public void triggerUpload(BookUpsertEvent event) {
        if (event == null || !StringUtils.hasText(event.getBookId())) {
            logger.warn("Cannot trigger S3 upload because BookUpsertEvent is missing book ID");
            return;
        }

        if (s3BookCoverService.isEmpty()) {
            logger.error("S3BookCoverService is unavailable for book {}. Verify S3 bean configuration and environment variables.",
                event.getBookId());
            return;
        }
        if (!s3BookCoverService.get().isUploadEnabled()) {
            logger.debug("Skipping S3 upload for book {} because S3 uploads are disabled or unavailable.",
                event.getBookId());
            return;
        }

        String canonicalImageUrl = CoverImageUrlSelector.resolveCanonicalImageUrl(
            event.getCanonicalImageUrl(),
            event.getImageLinks()
        );
        if (!StringUtils.hasText(canonicalImageUrl)) {
            logger.warn("BookUpsertEvent for book {} has no uploadable canonical URL. canonicalImageUrl='{}', imageLinks={}, source='{}'.",
                event.getBookId(), event.getCanonicalImageUrl(), event.getImageLinks(), event.getSource());
            return;
        }

        String source = StringUtils.hasText(event.getSource()) ? event.getSource() : "UNKNOWN";
        try {
            UUID bookUuid = UUID.fromString(event.getBookId());
            executeUpload(event.getBookId(), bookUuid, canonicalImageUrl, source, s3BookCoverService.get());
        } catch (IllegalArgumentException ignored) {
            logger.warn("BookUpsertEvent contains non-UUID bookId '{}'; skipping S3 upload.", event.getBookId());
        }
    }

    private void executeUpload(String bookId,
                               UUID bookUuid,
                               String canonicalImageUrl,
                               String source,
                               S3BookCoverService service) {
        if (!service.isUploadEnabled()) {
            logger.debug("Skipping S3 upload for book {} because upload availability changed before execution.", bookId);
            return;
        }
        s3UploadAttempts.increment();
        Timer.Sample sample = Timer.start();

        service.uploadCoverToS3Async(canonicalImageUrl, bookId, source)
            .retryWhen(buildRetrySpec(bookId))
            .switchIfEmpty(emptyUploadResult(bookId, canonicalImageUrl))
            .publishOn(Schedulers.boundedElastic())
            .subscribe(
                details -> handleUploadSuccess(details, bookId, bookUuid, sample),
                error -> handleUploadError(error, bookId, sample)
            );
    }

    private Retry buildRetrySpec(String bookId) {
        return Retry.backoff(MAX_UPLOAD_RETRIES, BASE_RETRY_DELAY)
            .maxBackoff(MAX_RETRY_DELAY)
            .filter(throwable -> shouldRetryThrowable(throwable, bookId))
            .doBeforeRetry(retrySignal -> {
                s3UploadRetriesTotal.increment();
                logger.info("Retry attempt {} for book {} after error: {}",
                    retrySignal.totalRetries() + 1,
                    bookId,
                    retrySignal.failure().getMessage());
            });
    }

    private boolean shouldRetryThrowable(Throwable throwable, String bookId) {
        if (throwable instanceof CoverProcessingException
            || throwable instanceof CoverTooLargeException
            || throwable instanceof UnsafeUrlException) {
            logger.warn("Not retrying S3 upload for book {} ({}): {}",
                bookId,
                throwable.getClass().getSimpleName(),
                throwable.getMessage());
            return false;
        }

        if (throwable instanceof S3CoverUploadException s3Exception) {
            boolean shouldRetry = s3Exception.isRetryable();
            logger.warn("{} S3 upload for book {} ({}): {}",
                shouldRetry ? "Retrying" : "Not retrying",
                bookId,
                s3Exception.getClass().getSimpleName(),
                s3Exception.getMessage());
            return shouldRetry;
        }

        logger.warn("Retrying S3 upload for book {} after unexpected error: {}", bookId, throwable.getMessage());
        return true;
    }

    private Mono<ImageDetails> emptyUploadResult(String bookId, String canonicalImageUrl) {
        return Mono.defer(() -> {
            logger.error("S3 upload pipeline emitted no ImageDetails for book {}. Canonical URL: {}",
                bookId,
                canonicalImageUrl);
            return Mono.error(new IllegalStateException("S3 upload pipeline completed without emitting ImageDetails"));
        });
    }

    private void handleUploadSuccess(ImageDetails details, String bookId, UUID bookUuid, Timer.Sample sample) {
        sample.stop(s3UploadDuration);

        if (details == null || !StringUtils.hasText(details.getStorageKey())) {
            s3UploadFailures.increment();
            logger.error("S3 upload returned non-persistable details for book {}. storageKey='{}'",
                bookId,
                details != null ? details.getStorageKey() : null);
            return;
        }

        CoverPersistenceService.PersistenceResult persistenceResult = coverPersistenceService.updateAfterS3Upload(
            bookUuid,
            new CoverPersistenceService.S3UploadResult(
                details.getStorageKey(),
                details.getUrlOrPath(),
                details.getWidth(),
                details.getHeight(),
                details.getCoverImageSource() != null ? details.getCoverImageSource() : CoverImageSource.UNDEFINED
            )
        );

        if (!persistenceResult.success()) {
            s3UploadFailures.increment();
            logger.error("S3 upload metadata persistence reported unsuccessful status for book {} and key {}",
                bookId,
                details.getStorageKey());
            return;
        }

        s3UploadSuccesses.increment();
    }

    private void handleUploadError(Throwable error, String bookId, Timer.Sample sample) {
        sample.stop(s3UploadDuration);
        s3UploadFailures.increment();

        switch (error) {
            case CoverDownloadException downloadException -> {
                String causeMessage = downloadException.getCause() != null
                    ? downloadException.getCause().getMessage()
                    : "Unknown cause";
                logger.error("S3 upload permanently failed for book {} after retries: download failed from {}. Cause: {}",
                    bookId,
                    downloadException.getImageUrl(),
                    causeMessage);
            }
            case CoverProcessingException processingException ->
                logger.error("S3 upload failed for book {} (non-retryable): processing failed: {}",
                    bookId,
                    processingException.getMessage());
            case CoverTooLargeException tooLargeException ->
                logger.error("S3 upload failed for book {} (non-retryable): image too large: {} bytes (max: {} bytes)",
                    bookId,
                    tooLargeException.getActualSize(),
                    tooLargeException.getMaxSize());
            case UnsafeUrlException unsafeUrlException ->
                logger.error("S3 upload failed for book {} (non-retryable): unsafe URL blocked: {}",
                    bookId,
                    unsafeUrlException.getImageUrl());
            case S3UploadException s3UploadException ->
                logger.warn("S3 upload skipped for book {} due to runtime configuration: {}",
                    bookId,
                    s3UploadException.getMessage());
            default ->
                logger.error("S3 upload failed for book {} after retries: {}. Metrics: attempts={}, successes={}, failures={}",
                    bookId,
                    error.getMessage(),
                    s3UploadAttempts.count(),
                    s3UploadSuccesses.count(),
                    s3UploadFailures.count(),
                    error);
        }
    }
}
