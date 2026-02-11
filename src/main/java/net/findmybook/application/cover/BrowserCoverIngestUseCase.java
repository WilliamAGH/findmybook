package net.findmybook.application.cover;

import java.io.IOException;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.findmybook.exception.CoverTooLargeException;
import net.findmybook.exception.S3UploadException;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.model.image.ProcessedImage;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.service.image.CoverUrlSafetyValidator;
import net.findmybook.service.image.ImageProcessingService;
import net.findmybook.service.image.S3BookCoverService;
import net.findmybook.util.UrlUtils;
import net.findmybook.util.cover.CoverSourceMapper;
import net.findmybook.util.cover.UrlSourceDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Handles browser-originated cover relay ingestion and persistence.
 *
 * <p>This use case validates the requested source URL against known cover candidates,
 * processes uploaded image bytes, uploads to S3, and persists canonical metadata.</p>
 */
@Service
public class BrowserCoverIngestUseCase {

    private static final Logger log = LoggerFactory.getLogger(BrowserCoverIngestUseCase.class);
    private static final long INGEST_TIMEOUT_SECONDS = 20;
    private static final String BROWSER_UPLOAD_SOURCE = "BROWSER_UPLOAD";

    private final BookCoverResolutionService bookCoverResolutionService;
    private final S3BookCoverService s3BookCoverService;
    private final ImageProcessingService imageProcessingService;
    private final CoverPersistenceService coverPersistenceService;
    private final CoverUrlSafetyValidator coverUrlSafetyValidator;

    /**
     * Creates the browser cover relay use case.
     *
     * @param bookCoverResolutionService canonical cover resolver for identifier lookups
     * @param s3BookCoverService S3 upload orchestrator for processed cover payloads
     * @param imageProcessingService image validator/processor for uploaded bytes
     * @param coverPersistenceService persistence service for canonical cover metadata
     * @param coverUrlSafetyValidator SSRF-safe allowlist validator for source URLs
     */
    public BrowserCoverIngestUseCase(BookCoverResolutionService bookCoverResolutionService,
                                     S3BookCoverService s3BookCoverService,
                                     ImageProcessingService imageProcessingService,
                                     CoverPersistenceService coverPersistenceService,
                                     CoverUrlSafetyValidator coverUrlSafetyValidator) {
        this.bookCoverResolutionService = bookCoverResolutionService;
        this.s3BookCoverService = s3BookCoverService;
        this.imageProcessingService = imageProcessingService;
        this.coverPersistenceService = coverPersistenceService;
        this.coverUrlSafetyValidator = coverUrlSafetyValidator;
    }

    /**
     * Persists a browser-fetched cover payload to S3 and canonical metadata storage.
     *
     * @param identifier user-facing book identifier
     * @param image uploaded image bytes from browser fetch response
     * @param sourceUrl rendered external source URL fetched by browser
     * @param source optional provider hint from client
     * @return persisted ingest result with canonical URL + S3 key metadata
     */
    public BrowserCoverIngestResult ingest(String identifier,
                                           MultipartFile image,
                                           String sourceUrl,
                                           String source) {
        BookCoverResolutionService.ResolvedCoverPayload payload = bookCoverResolutionService.resolveCover(identifier)
            .orElseThrow(() -> new NoSuchElementException("Book not found with ID: " + identifier));

        UUID bookUuid = bookCoverResolutionService.resolveBookUuid(identifier, payload.bookId())
            .orElseThrow(() -> new NoSuchElementException("Book not found with ID: " + identifier));

        validateImagePayload(image, bookUuid);
        String normalizedSourceUrl = validateBrowserSourceUrl(sourceUrl, payload, identifier, bookUuid);
        CoverImageSource uploadSource = resolveUploadSource(source, normalizedSourceUrl, payload.sourceLabel());
        ProcessedImage processedImage = processUploadedImage(image, bookUuid, normalizedSourceUrl);

        ImageDetails uploadedImage = uploadProcessedCover(bookUuid, uploadSource, processedImage);
        String storageKey = requireStorageKey(uploadedImage, bookUuid);

        CoverPersistenceService.PersistenceResult persistenceResult = coverPersistenceService.updateAfterS3Upload(
            bookUuid,
            new CoverPersistenceService.S3UploadResult(
                storageKey,
                uploadedImage.getUrlOrPath(),
                uploadedImage.getWidth(),
                uploadedImage.getHeight(),
                uploadedImage.getGrayscale(),
                uploadSource
            )
        );

        if (!persistenceResult.success() || !StringUtils.hasText(persistenceResult.canonicalUrl())) {
            log.error("Cover persistence reported unsuccessful status for book {} after browser ingest", bookUuid);
            throw new IllegalStateException("Failed to persist cover metadata for book '" + bookUuid + "'");
        }

        return new BrowserCoverIngestResult(
            bookUuid.toString(),
            persistenceResult.canonicalUrl(),
            storageKey,
            uploadSource.name(),
            persistenceResult.width(),
            persistenceResult.height(),
            persistenceResult.highRes()
        );
    }

    private void validateImagePayload(MultipartFile image, UUID bookUuid) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Image file is required");
        }
        if (image.getSize() <= 0) {
            throw new IllegalArgumentException("Image file is empty");
        }
        log.info("Received browser cover ingest payload for book {} (bytes={})", bookUuid, image.getSize());
    }

    private String validateBrowserSourceUrl(String sourceUrl,
                                            BookCoverResolutionService.ResolvedCoverPayload payload,
                                            String identifier,
                                            UUID bookUuid) {
        String normalizedSourceUrl = UrlUtils.validateAndNormalize(sourceUrl);
        if (!StringUtils.hasText(normalizedSourceUrl)) {
            throw new IllegalArgumentException("sourceUrl must be a valid HTTP(S) URL");
        }
        if (!coverUrlSafetyValidator.isAllowedImageUrl(normalizedSourceUrl)) {
            log.warn("Blocked non-allowlisted browser cover source URL for identifier {} (book {}): {}",
                identifier, bookUuid, normalizedSourceUrl);
            throw new IllegalArgumentException("sourceUrl host is not allowlisted");
        }
        if (!matchesKnownCoverCandidate(payload, normalizedSourceUrl)) {
            log.warn("Rejected browser cover ingest for identifier {} because sourceUrl does not match known candidates. payloadPreferred={}, payloadFallback={}, requested={}",
                identifier, payload.cover().url(), payload.fallbackUrl(), normalizedSourceUrl);
            throw new IllegalArgumentException("sourceUrl must match one of the book's current cover URLs");
        }
        return normalizedSourceUrl;
    }

    private boolean matchesKnownCoverCandidate(BookCoverResolutionService.ResolvedCoverPayload payload,
                                               String normalizedSourceUrl) {
        String normalizedPreferred = UrlUtils.validateAndNormalize(payload.cover().url());
        if (StringUtils.hasText(normalizedPreferred) && normalizedPreferred.equals(normalizedSourceUrl)) {
            return true;
        }

        String normalizedFallback = UrlUtils.validateAndNormalize(payload.fallbackUrl());
        return StringUtils.hasText(normalizedFallback) && normalizedFallback.equals(normalizedSourceUrl);
    }

    private CoverImageSource resolveUploadSource(String requestedSource, String sourceUrl, String payloadSourceLabel) {
        CoverImageSource fromRequest = CoverSourceMapper.toCoverImageSource(requestedSource);
        if (isPersistableExternalSource(fromRequest)) {
            return fromRequest;
        }

        CoverImageSource fromUrl = UrlSourceDetector.detectSource(sourceUrl);
        if (isPersistableExternalSource(fromUrl)) {
            return fromUrl;
        }

        CoverImageSource fromPayload = CoverSourceMapper.toCoverImageSource(payloadSourceLabel);
        if (isPersistableExternalSource(fromPayload)) {
            return fromPayload;
        }

        log.warn("Could not resolve a persistable cover source from request='{}', url='{}', payload='{}'; defaulting to UNDEFINED",
            requestedSource, sourceUrl, payloadSourceLabel);
        return CoverImageSource.UNDEFINED;
    }

    private boolean isPersistableExternalSource(CoverImageSource source) {
        return source != null
            && source != CoverImageSource.UNDEFINED
            && source != CoverImageSource.ANY
            && source != CoverImageSource.NONE;
    }

    private ProcessedImage processUploadedImage(MultipartFile image, UUID bookUuid, String sourceUrl) {
        byte[] uploadedBytes;
        try {
            uploadedBytes = image.getBytes();
        } catch (IOException ioException) {
            log.error("Failed to read uploaded cover bytes for book {} from {}: {}",
                bookUuid, sourceUrl, ioException.getMessage(), ioException);
            throw new IllegalArgumentException("Unable to read uploaded image bytes", ioException);
        }

        ProcessedImage processedImage;
        try {
            processedImage = imageProcessingService
                .processImageForS3(uploadedBytes, bookUuid.toString())
                .orTimeout(INGEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
        } catch (CompletionException processingFailure) {
            Throwable cause = processingFailure.getCause() != null ? processingFailure.getCause() : processingFailure;
            log.error("Cover image processing failed for book {} sourced from {}: {}",
                bookUuid, sourceUrl, cause.getMessage(), cause);
            throw new CoverImageRejectedException("Cover image processing failed for book '" + bookUuid + "'", cause);
        }

        if (processedImage == null || !processedImage.isProcessingSuccessful()) {
            String reason = processedImage != null ? processedImage.getProcessingError() : "Unknown image processing failure";
            log.warn("Cover ingest rejected for book {} sourced from {}: {}",
                bookUuid, sourceUrl, reason);
            throw new CoverImageRejectedException("Cover image rejected: " + reason);
        }
        return processedImage;
    }

    private ImageDetails uploadProcessedCover(UUID bookUuid,
                                              CoverImageSource uploadSource,
                                              ProcessedImage processedImage) {
        String s3SourceLabel = isPersistableExternalSource(uploadSource)
            ? uploadSource.name()
            : BROWSER_UPLOAD_SOURCE;

        S3BookCoverService.ProcessedCoverUploadRequest uploadRequest = new S3BookCoverService.ProcessedCoverUploadRequest(
            processedImage.getProcessedBytes(),
            processedImage.getNewFileExtension(),
            processedImage.getNewMimeType(),
            processedImage.getWidth(),
            processedImage.getHeight(),
            bookUuid.toString(),
            s3SourceLabel,
            null
        );

        try {
            return s3BookCoverService
                .uploadProcessedCoverToS3Async(uploadRequest)
                .timeout(Duration.ofSeconds(INGEST_TIMEOUT_SECONDS))
                .blockOptional()
                .orElseThrow(() -> new IllegalStateException("S3 upload completed without an image payload"));
        } catch (CoverTooLargeException tooLargeException) {
            log.warn("Browser cover ingest rejected as too large for book {}: actual={} max={}",
                bookUuid, tooLargeException.getActualSize(), tooLargeException.getMaxSize());
            throw tooLargeException;
        } catch (S3UploadException uploadException) {
            log.error("S3 cover upload failed for book {}: {}", bookUuid, uploadException.getMessage(), uploadException);
            throw uploadException;
        } catch (RuntimeException runtimeException) {
            log.error("Unexpected S3 cover upload failure for book {}: {}",
                bookUuid, runtimeException.getMessage(), runtimeException);
            throw new IllegalStateException(
                "Unexpected cover upload failure for book '" + bookUuid + "'",
                runtimeException
            );
        }
    }

    private String requireStorageKey(ImageDetails uploadedImage, UUID bookUuid) {
        if (uploadedImage == null || !StringUtils.hasText(uploadedImage.getStorageKey())) {
            log.error("S3 upload returned non-persistable image details for book {}: {}", bookUuid, uploadedImage);
            throw new IllegalStateException("S3 upload returned missing storage key for book '" + bookUuid + "'");
        }
        return uploadedImage.getStorageKey();
    }

    /**
     * Signals that uploaded bytes were syntactically valid but rejected by processing rules.
     */
    public static final class CoverImageRejectedException extends RuntimeException {
        public CoverImageRejectedException(String message) {
            super(message);
        }

        public CoverImageRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Immutable response payload for successful browser cover ingestion.
     *
     * @param bookId canonical book identifier
     * @param storedCoverUrl persisted canonical cover URL
     * @param storageKey stored S3 key
     * @param source resolved cover source label
     * @param width persisted width
     * @param height persisted height
     * @param highResolution high-resolution classification
     */
    public record BrowserCoverIngestResult(String bookId,
                                           String storedCoverUrl,
                                           String storageKey,
                                           String source,
                                           Integer width,
                                           Integer height,
                                           Boolean highResolution) {
    }
}
