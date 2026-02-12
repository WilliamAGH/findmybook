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
    private static final String CODE_S3_EVENT_MISSING_BOOK_ID = "S3_UPLOAD_EVENT_MISSING_BOOK_ID";
    private static final String CODE_S3_SERVICE_UNAVAILABLE = "S3_UPLOAD_SERVICE_UNAVAILABLE";
    private static final String CODE_S3_EVENT_NO_CANONICAL_URL = "S3_UPLOAD_EVENT_NO_CANONICAL_URL";
    private static final String CODE_S3_EVENT_INVALID_BOOK_ID = "S3_UPLOAD_EVENT_INVALID_BOOK_ID";
    private static final String CODE_S3_RETRY_ATTEMPT = "S3_UPLOAD_RETRY_ATTEMPT";
    private static final String CODE_S3_RETRY_UNEXPECTED = "S3_UPLOAD_RETRY_UNEXPECTED";
    private static final String CODE_S3_EMPTY_UPLOAD_RESULT = "S3_UPLOAD_EMPTY_RESULT";
    private static final String CODE_S3_NON_PERSISTABLE_DETAILS = "S3_UPLOAD_NON_PERSISTABLE_DETAILS";
    private static final String CODE_S3_METADATA_PERSIST_FAILED = "S3_UPLOAD_METADATA_PERSIST_FAILED";
    private static final String CODE_S3_DOWNLOAD_FAILED = "S3_UPLOAD_DOWNLOAD_FAILED";
    private static final String CODE_S3_PROCESSING_FAILED = "S3_UPLOAD_PROCESSING_FAILED";
    private static final String CODE_S3_TOO_LARGE = "S3_UPLOAD_TOO_LARGE";
    private static final String CODE_S3_UNSAFE_URL = "S3_UPLOAD_UNSAFE_URL";
    private static final String CODE_S3_RUNTIME_CONFIGURATION = "S3_UPLOAD_RUNTIME_CONFIGURATION";
    private static final String CODE_S3_UNEXPECTED_FAILURE = "S3_UPLOAD_UNEXPECTED_FAILURE";

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
        this.s3BookCoverService = s3BookCoverService;
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
            logger.warn("Cannot trigger S3 upload because BookUpsertEvent is missing book ID [code={}]: reason={}",
                CODE_S3_EVENT_MISSING_BOOK_ID,
                "missing-book-id");
            return;
        }

        if (s3BookCoverService.isEmpty()) {
            logger.error("S3BookCoverService is unavailable for book {} [code={}]: reason={}. Verify S3 bean configuration and environment variables.",
                event.getBookId(),
                CODE_S3_SERVICE_UNAVAILABLE,
                "s3-service-unavailable");
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
            logger.warn("BookUpsertEvent for book {} has no uploadable canonical URL [code={}]: reason={}. canonicalImageUrl='{}', imageLinks={}, source='{}'.",
                event.getBookId(),
                CODE_S3_EVENT_NO_CANONICAL_URL,
                "missing-canonical-url",
                event.getCanonicalImageUrl(),
                event.getImageLinks(),
                event.getSource());
            return;
        }

        String source = StringUtils.hasText(event.getSource()) ? event.getSource() : "UNKNOWN";
        UUID bookUuid;
        try {
            bookUuid = UUID.fromString(event.getBookId());
        } catch (IllegalArgumentException _) {
            logger.warn("BookUpsertEvent contains non-UUID bookId '{}' [code={}]: reason={}",
                event.getBookId(),
                CODE_S3_EVENT_INVALID_BOOK_ID,
                "non-uuid-book-id");
            return;
        }
        executeUpload(event.getBookId(), bookUuid, canonicalImageUrl, source);
    }

    private void executeUpload(String bookId,
                               UUID bookUuid,
                               String canonicalImageUrl,
                               String source) {
        S3BookCoverService service = s3BookCoverService.get();
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
            .handle((details, sink) -> {
                validateAndPersistUpload(details, bookId, bookUuid);
                sink.next(details);
            })
            .onErrorResume(error -> {
                recordUploadFailureInDatabase(error, bookUuid, bookId);
                return Mono.error(error);
            })
            .doFinally(_ -> sample.stop(s3UploadDuration))
            .subscribe(
                _ -> s3UploadSuccesses.increment(),
                error -> {
                    s3UploadFailures.increment();
                    handleUploadError(error, bookId);
                }
            );
    }

    private Retry buildRetrySpec(String bookId) {
        return Retry.backoff(MAX_UPLOAD_RETRIES, BASE_RETRY_DELAY)
            .maxBackoff(MAX_RETRY_DELAY)
            .filter(throwable -> shouldRetryThrowable(throwable, bookId))
            .doBeforeRetry(retrySignal -> {
                s3UploadRetriesTotal.increment();
                logger.info("Retry attempt {} for book {} [code={}]: reason={}",
                    retrySignal.totalRetries() + 1,
                    bookId,
                    CODE_S3_RETRY_ATTEMPT,
                    resolveFailureReason(retrySignal.failure()));
            });
    }

    private boolean shouldRetryThrowable(Throwable throwable, String bookId) {
        String failureCode = classifyS3FailureCode(throwable);
        String reason = resolveFailureReason(throwable);
        if (throwable instanceof S3CoverUploadException s3Exception) {
            boolean shouldRetry = s3Exception.isRetryable();
            logger.warn("{} S3 upload for book {} [code={}]: reason={}",
                shouldRetry ? "Retrying" : "Not retrying",
                bookId,
                failureCode,
                reason);
            return shouldRetry;
        }

        if (throwable instanceof Error) {
            logger.error("Not retrying S3 upload for book {} after fatal error [code={}]: reason={}",
                bookId,
                CODE_S3_RETRY_UNEXPECTED,
                reason,
                throwable);
            return false;
        }
        boolean likelyTransient = throwable instanceof java.io.IOException
            || throwable instanceof java.util.concurrent.TimeoutException;
        if (!likelyTransient) {
            logger.warn("Not retrying S3 upload for book {} after non-transient error [code={}]: reason={}",
                bookId,
                CODE_S3_RETRY_UNEXPECTED,
                reason,
                throwable);
            return false;
        }
        logger.warn("Retrying S3 upload for book {} after transient error [code={}]: reason={}",
            bookId,
            CODE_S3_RETRY_UNEXPECTED,
            reason,
            throwable);
        return true;
    }

    private Mono<ImageDetails> emptyUploadResult(String bookId, String canonicalImageUrl) {
        return Mono.defer(() -> {
            logger.error("S3 upload pipeline emitted no ImageDetails for book {} [code={}]: reason={}. Canonical URL: {}",
                bookId,
                CODE_S3_EMPTY_UPLOAD_RESULT,
                "empty-upload-result",
                canonicalImageUrl);
            return Mono.error(new IllegalStateException("S3 upload pipeline completed without emitting ImageDetails"));
        });
    }

    private void validateAndPersistUpload(ImageDetails details, String bookId, UUID bookUuid) {
        if (details == null || !StringUtils.hasText(details.getStorageKey())) {
            String storageKey = details != null ? details.getStorageKey() : null;
            logger.error("S3 upload returned non-persistable details for book {} [code={}]: reason={}. storageKey='{}'",
                bookId,
                CODE_S3_NON_PERSISTABLE_DETAILS,
                "missing-storage-key",
                storageKey);
            throw new IllegalStateException(
                "S3 upload returned non-persistable details for book '" + bookId + "': missing storage key");
        }

        CoverPersistenceService.PersistenceResult persistenceResult = coverPersistenceService.updateAfterS3Upload(
            bookUuid,
            new CoverPersistenceService.S3UploadResult(
                details.getStorageKey(),
                details.getUrlOrPath(),
                details.getWidth(),
                details.getHeight(),
                details.getGrayscale(),
                details.getCoverImageSource() != null ? details.getCoverImageSource() : CoverImageSource.UNDEFINED
            )
        );

        if (!persistenceResult.success()) {
            logger.error("S3 upload metadata persistence reported unsuccessful status for book {} and key {} [code={}]: reason={}",
                bookId,
                details.getStorageKey(),
                CODE_S3_METADATA_PERSIST_FAILED,
                "metadata-persistence-unsuccessful");
            throw new IllegalStateException(
                "S3 upload metadata persistence failed for book '" + bookId + "' and key '" + details.getStorageKey() + "'");
        }
    }

    /**
     * Records an upload failure in the database so the error is persisted beyond logs.
     *
     * <p>If the secondary persistence itself fails, the failure is attached to the primary
     * error via {@link Throwable#addSuppressed} so both failures remain observable.</p>
     *
     * <p>Errors that are not recordable (e.g. runtime config skips) produce an empty
     * {@link Optional} from {@link #resolveRecordableErrorMessage}, skipping the DB write.</p>
     */
    private void recordUploadFailureInDatabase(Throwable primaryError, UUID bookUuid, String bookId) {
        resolveRecordableErrorMessage(primaryError).ifPresent(recordableError -> {
            try {
                coverPersistenceService.recordDownloadError(bookUuid, recordableError);
            } catch (RuntimeException persistenceFailure) {
                logger.error("Failed to persist download error for book {} (secondary failure): {}",
                    bookId, persistenceFailure.getMessage(), persistenceFailure);
                primaryError.addSuppressed(persistenceFailure);
            }
        });
    }

    private void handleUploadError(Throwable error, String bookId) {
        switch (error) {
            case CoverDownloadException downloadException -> {
                String causeMessage = downloadException.getCause() != null
                    ? downloadException.getCause().getMessage()
                    : "Unknown cause";
                logger.error("S3 upload permanently failed for book {} after retries [code={}]: reason={}. imageUrl={}",
                    bookId,
                    CODE_S3_DOWNLOAD_FAILED,
                    causeMessage,
                    downloadException.getImageUrl());
            }
            case CoverProcessingException processingException ->
                logProcessingFailure(bookId, processingException);
            case CoverTooLargeException tooLargeException ->
                logger.error("S3 upload failed for book {} (non-retryable) [code={}]: reason=image-too-large actual={} max={}",
                    bookId,
                    CODE_S3_TOO_LARGE,
                    tooLargeException.getActualSize(),
                    tooLargeException.getMaxSize());
            case UnsafeUrlException unsafeUrlException ->
                logger.error("S3 upload failed for book {} (non-retryable) [code={}]: reason=unsafe-url imageUrl={}",
                    bookId,
                    CODE_S3_UNSAFE_URL,
                    unsafeUrlException.getImageUrl());
            case S3UploadException s3UploadException ->
                logRuntimeConfigurationSkip(bookId, s3UploadException);
            default ->
                {
                    if (logger.isErrorEnabled()) {
                        logger.error("S3 upload failed for book {} after retries [code={}]: reason={}. Metrics: attempts={}, successes={}, failures={}",
                            bookId,
                            CODE_S3_UNEXPECTED_FAILURE,
                            resolveFailureReason(error),
                            s3UploadAttempts.count(),
                            s3UploadSuccesses.count(),
                            s3UploadFailures.count(),
                            error);
                    }
                }
        }
    }

    /**
     * Maps an upload error to the message that should be recorded in the database.
     *
     * <p>Returns empty for errors that should not be persisted — specifically
     * {@link S3UploadException}, which represents a runtime configuration skip
     * rather than a recordable failure.</p>
     */
    private Optional<String> resolveRecordableErrorMessage(Throwable error) {
        return switch (error) {
            case CoverDownloadException downloadException -> {
                String causeMessage = downloadException.getCause() != null
                    ? downloadException.getCause().getMessage()
                    : "Unknown cause";
                yield Optional.of("DownloadFailed: " + causeMessage);
            }
            case CoverProcessingException processingException ->
                Optional.of(processingException.getRejectionReason() != null
                    ? processingException.getRejectionReason().name()
                    : resolveFailureReason(processingException));
            case CoverTooLargeException _ -> Optional.of("TooLarge");
            case UnsafeUrlException _ -> Optional.of("UnsafeUrl");
            case S3UploadException _ -> Optional.empty();
            default -> Optional.of(resolveFailureReason(error));
        };
    }

    String classifyS3FailureCode(Throwable error) {
        return switch (error) {
            case CoverDownloadException _ -> CODE_S3_DOWNLOAD_FAILED;
            case CoverProcessingException _ -> CODE_S3_PROCESSING_FAILED;
            case CoverTooLargeException _ -> CODE_S3_TOO_LARGE;
            case UnsafeUrlException _ -> CODE_S3_UNSAFE_URL;
            case S3UploadException _ -> CODE_S3_RUNTIME_CONFIGURATION;
            default -> CODE_S3_UNEXPECTED_FAILURE;
        };
    }

    private String resolveFailureReason(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        if (!StringUtils.hasText(message)) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private void logProcessingFailure(String bookId, CoverProcessingException processingException) {
        if (!logger.isErrorEnabled()) {
            return;
        }
        logger.error("S3 upload failed for book {} (non-retryable) [code={}]: reason={}",
            bookId,
            CODE_S3_PROCESSING_FAILED,
            resolveFailureReason(processingException));
    }

    private void logRuntimeConfigurationSkip(String bookId, S3UploadException s3UploadException) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        logger.warn("S3 upload skipped for book {} due to runtime configuration [code={}]: reason={}",
            bookId,
            CODE_S3_RUNTIME_CONFIGURATION,
            resolveFailureReason(s3UploadException));
    }
}
