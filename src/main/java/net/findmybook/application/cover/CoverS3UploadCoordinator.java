package net.findmybook.application.cover;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
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
    /** Structured error codes for S3 upload pipeline failures and diagnostics. */
    enum S3UploadErrorCode {
        EVENT_MISSING_BOOK_ID("S3_UPLOAD_EVENT_MISSING_BOOK_ID"),
        SERVICE_UNAVAILABLE("S3_UPLOAD_SERVICE_UNAVAILABLE"),
        EVENT_NO_CANONICAL_URL("S3_UPLOAD_EVENT_NO_CANONICAL_URL"),
        EVENT_INVALID_BOOK_ID("S3_UPLOAD_EVENT_INVALID_BOOK_ID"),
        RETRY_ATTEMPT("S3_UPLOAD_RETRY_ATTEMPT"),
        RETRY_UNEXPECTED("S3_UPLOAD_RETRY_UNEXPECTED"),
        EMPTY_UPLOAD_RESULT("S3_UPLOAD_EMPTY_RESULT"),
        NON_PERSISTABLE_DETAILS("S3_UPLOAD_NON_PERSISTABLE_DETAILS"),
        METADATA_PERSIST_FAILED("S3_UPLOAD_METADATA_PERSIST_FAILED"),
        DOWNLOAD_FAILED("S3_UPLOAD_DOWNLOAD_FAILED"),
        PROCESSING_FAILED("S3_UPLOAD_PROCESSING_FAILED"),
        TOO_LARGE("S3_UPLOAD_TOO_LARGE"),
        UNSAFE_URL("S3_UPLOAD_UNSAFE_URL"),
        RUNTIME_CONFIGURATION("S3_UPLOAD_RUNTIME_CONFIGURATION"),
        UNEXPECTED_FAILURE("S3_UPLOAD_UNEXPECTED_FAILURE");

        private final String code;
        S3UploadErrorCode(String code) { this.code = code; }
        String code() { return code; }
    }

    /** Resolved failure details for unified logging and database recording. */
    private record UploadFailureDetail(S3UploadErrorCode code, String reason, boolean useWarnLevel) {}

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
                S3UploadErrorCode.EVENT_MISSING_BOOK_ID.code(),
                "missing-book-id");
            return;
        }

        if (s3BookCoverService.isEmpty()) {
            logger.error("S3BookCoverService is unavailable for book {} [code={}]: reason={}. Verify S3 bean configuration and environment variables.",
                event.getBookId(),
                S3UploadErrorCode.SERVICE_UNAVAILABLE.code(),
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
                S3UploadErrorCode.EVENT_NO_CANONICAL_URL.code(),
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
                S3UploadErrorCode.EVENT_INVALID_BOOK_ID.code(),
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
                    S3UploadErrorCode.RETRY_ATTEMPT.code(),
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
                S3UploadErrorCode.RETRY_UNEXPECTED.code(),
                reason,
                throwable);
            return false;
        }
        boolean likelyTransient = throwable instanceof java.io.IOException
            || throwable instanceof java.util.concurrent.TimeoutException;
        if (!likelyTransient) {
            logger.warn("Not retrying S3 upload for book {} after non-transient error [code={}]: reason={}",
                bookId,
                S3UploadErrorCode.RETRY_UNEXPECTED.code(),
                reason,
                throwable);
            return false;
        }
        logger.warn("Retrying S3 upload for book {} after transient error [code={}]: reason={}",
            bookId,
            S3UploadErrorCode.RETRY_UNEXPECTED.code(),
            reason,
            throwable);
        return true;
    }

    private Mono<ImageDetails> emptyUploadResult(String bookId, String canonicalImageUrl) {
        return Mono.defer(() -> {
            logger.error("S3 upload pipeline emitted no ImageDetails for book {} [code={}]: reason={}. Canonical URL: {}",
                bookId,
                S3UploadErrorCode.EMPTY_UPLOAD_RESULT.code(),
                "empty-upload-result",
                canonicalImageUrl);
            return Mono.error(new IllegalStateException("S3 upload pipeline completed without emitting ImageDetails"));
        });
    }

    private void validateAndPersistUpload(ImageDetails details, String bookId, UUID bookUuid) {
        Objects.requireNonNull(details, "ImageDetails must be non-null");
        if (!StringUtils.hasText(details.getStorageKey())) {
            String storageKey = details.getStorageKey();
            logger.error("S3 upload returned non-persistable details for book {} [code={}]: reason={}. storageKey='{}'",
                bookId,
                S3UploadErrorCode.NON_PERSISTABLE_DETAILS.code(),
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
                S3UploadErrorCode.METADATA_PERSIST_FAILED.code(),
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
        UploadFailureDetail detail = resolveUploadFailureDetail(error);
        if (detail.useWarnLevel()) {
            // Intentionally includes error for stack trace — warn-level failures
            // (e.g. S3UploadException/runtime config) still need diagnostic context.
            logger.warn("S3 upload skipped for book {} [code={}]: reason={}",
                bookId, detail.code().code(), detail.reason(), error);
        } else {
            logger.error("S3 upload failed for book {} [code={}]: reason={}",
                bookId, detail.code().code(), detail.reason(), error);
        }
    }

    /**
     * Resolves structured failure details from an upload error for unified logging
     * and database recording, eliminating repeated type-dispatch switches.
     */
    private UploadFailureDetail resolveUploadFailureDetail(Throwable error) {
        return switch (error) {
            case CoverDownloadException e -> {
                String cause = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage()
                    : "Unknown cause";
                yield new UploadFailureDetail(S3UploadErrorCode.DOWNLOAD_FAILED,
                    cause + " imageUrl=" + e.getImageUrl(), false);
            }
            case CoverProcessingException e -> new UploadFailureDetail(
                S3UploadErrorCode.PROCESSING_FAILED,
                e.getRejectionReason() != null ? e.getRejectionReason().name() : resolveFailureReason(e),
                false);
            case CoverTooLargeException e -> new UploadFailureDetail(
                S3UploadErrorCode.TOO_LARGE,
                "image-too-large actual=" + e.getActualSize() + " max=" + e.getMaxSize(),
                false);
            case UnsafeUrlException e -> new UploadFailureDetail(
                S3UploadErrorCode.UNSAFE_URL,
                "unsafe-url imageUrl=" + e.getImageUrl(),
                false);
            case S3UploadException e -> new UploadFailureDetail(
                S3UploadErrorCode.RUNTIME_CONFIGURATION, resolveFailureReason(e), true);
            default -> new UploadFailureDetail(
                S3UploadErrorCode.UNEXPECTED_FAILURE, resolveFailureReason(error), false);
        };
    }

    /**
     * Maps an upload error to the concise message recorded in the database.
     *
     * <p>Only genuine download, processing, and upload failures are recorded. Metadata
     * persistence errors and runtime configuration skips return empty — recording those
     * as {@code download_error} would hide an otherwise valid cover from queries.</p>
     */
    private Optional<String> resolveRecordableErrorMessage(Throwable error) {
        UploadFailureDetail detail = resolveUploadFailureDetail(error);
        return switch (detail.code()) {
            case DOWNLOAD_FAILED, PROCESSING_FAILED, TOO_LARGE, UNSAFE_URL ->
                Optional.of(detail.reason());
            default -> Optional.empty();
        };
    }

    String classifyS3FailureCode(Throwable error) {
        return resolveUploadFailureDetail(error).code().code();
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
}
