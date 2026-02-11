package net.findmybook.service.image;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.findmybook.exception.CoverDownloadException;
import net.findmybook.exception.CoverProcessingException;
import net.findmybook.exception.CoverTooLargeException;
import net.findmybook.exception.S3CoverUploadException;
import net.findmybook.exception.S3UploadException;
import net.findmybook.exception.UnsafeUrlException;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverRejectionReason;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.model.image.ImageProvenanceData;
import net.findmybook.model.image.ProcessedImage;
import net.findmybook.support.s3.CoverUploadPayload;
import net.findmybook.support.s3.S3CoverStorageGateway;
import net.findmybook.support.s3.S3CoverStorageProperties;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Application-level orchestrator for cover download, processing, and S3 persistence.
 *
 * <p>AWS SDK interactions are intentionally delegated to {@link S3CoverStorageGateway} so this
 * service remains focused on workflow orchestration.</p>
 */
@Service
public class S3BookCoverService implements ExternalCoverService {

    private static final Logger logger = LoggerFactory.getLogger(S3BookCoverService.class);
    private final WebClient webClient;
    private final ImageProcessingService imageProcessingService;
    private final CoverUrlSafetyValidator coverUrlSafetyValidator;
    private final S3CoverStorageGateway s3CoverStorageGateway;
    private final S3CoverStorageProperties s3CoverStorageProperties;
    private final long maxFileSizeBytes;

    /**
     * Creates the cover workflow orchestrator for HTTP fetch, image processing, and S3 storage.
     *
     * @param webClientBuilder shared WebClient builder for outbound image downloads
     * @param imageProcessingService service that validates/transcodes downloaded image payloads
     * @param coverUrlSafetyValidator validator that blocks unsafe remote image URLs
     * @param s3CoverStorageGateway gateway for S3 metadata lookup and object uploads
     * @param s3CoverStorageProperties typed storage configuration for key naming/lookup
     * @param maxFileSizeBytes maximum allowed image size for downloads before processing
     */
    public S3BookCoverService(WebClient.Builder webClientBuilder,
                              ImageProcessingService imageProcessingService,
                              CoverUrlSafetyValidator coverUrlSafetyValidator,
                              S3CoverStorageGateway s3CoverStorageGateway,
                              S3CoverStorageProperties s3CoverStorageProperties,
                              @Value("${app.cover-cache.max-file-size-bytes:5242880}") long maxFileSizeBytes) {
        this.webClient = webClientBuilder.build();
        this.imageProcessingService = imageProcessingService;
        this.coverUrlSafetyValidator = coverUrlSafetyValidator;
        this.s3CoverStorageGateway = s3CoverStorageGateway;
        this.s3CoverStorageProperties = s3CoverStorageProperties;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    /**
     * Indicates whether uploads are expected to run in the current runtime configuration.
     */
    public boolean isUploadEnabled() {
        return s3CoverStorageGateway.isUploadAvailable();
    }

    /**
     * Resolves the first available S3-backed cover for a book.
     */
    @Override
    public CompletableFuture<Optional<ImageDetails>> fetchCover(Book book) {
        if (book == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String bookKey = S3UploadValidation.resolveBookLookupKey(book);
        if (!StringUtils.hasText(bookKey)) {
            logger.warn("Cannot fetch S3 cover for book without a valid ID or ISBN. Title: {}", book.getTitle());
            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (!s3CoverStorageGateway.isReadAvailable()) {
            logger.debug("S3 fetchCover skipped: S3 cover storage not available.");
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return s3CoverStorageGateway
            .findFirstAvailableCover(
                bookKey,
                s3CoverStorageProperties.coverDefaultFileExtension(),
                s3CoverStorageProperties.coverSourceBaseLabels()
            )
            .doOnTerminate(() -> logger.debug("S3 cover lookup completed for book {}.", bookKey))
            .toFuture();
    }

    /**
     * Downloads and uploads a cover image for a known source label.
     */
    public Mono<ImageDetails> uploadCoverToS3Async(String imageUrl, String bookId, String source) {
        return uploadCoverToS3Async(imageUrl, bookId, source, null);
    }

    /**
     * Downloads, processes, and uploads a cover image.
     * @param provenanceData retained for API compatibility (persistence handled elsewhere)
     */
    public Mono<ImageDetails> uploadCoverToS3Async(String imageUrl,
                                                   String bookId,
                                                   String source,
                                                   @Nullable ImageProvenanceData provenanceData) {
        // Provenance data retained for API compatibility; actual persistence handled elsewhere
        try {
            s3CoverStorageGateway.ensureUploadReady(bookId, imageUrl);
            S3UploadValidation.validateUploadInput(imageUrl, bookId);
        } catch (S3UploadException exception) {
            logger.warn("Skipping S3 upload for book {}: {}", bookId, exception.getMessage());
            return Mono.error(exception);
        }

        String s3Source = S3UploadValidation.resolveUploadSource(source);
        if (!coverUrlSafetyValidator.isAllowedImageUrl(imageUrl)) {
            logger.warn("Blocked non-allowed or potentially unsafe image URL for book {}: {}", bookId, imageUrl);
            return Mono.error(new UnsafeUrlException(bookId, imageUrl));
        }

        // The provenance parameter remains for API compatibility while persistence is handled elsewhere.
        return webClient.get().uri(imageUrl).retrieve().bodyToMono(byte[].class)
            .timeout(Duration.ofSeconds(10))
            .onErrorMap(error -> {
                logger.error("Error downloading image for book {}: {}", bookId, error.getMessage());
                return new CoverDownloadException(bookId, imageUrl, error);
            })
            .flatMap(rawImageBytes -> processDownloadedImageForUpload(rawImageBytes, imageUrl, bookId, s3Source))
            .onErrorMap(error -> mapUnexpectedUploadError(error, bookId, imageUrl));
    }

    /**
     * Captures all fields needed to upload a pre-processed cover image to S3.
     * <p>Note: Class (not record) required for proper byte[] content comparison.</p>
     *
     * @param processedImageBytes the image payload to upload (must not be null or empty)
     * @param fileExtension       the file extension for the S3 key (e.g., "webp", "jpg")
     * @param mimeType            the content type for the S3 object (e.g., "image/webp")
     * @param width               the image width in pixels (must be positive)
     * @param height              the image height in pixels (must be positive)
     * @param bookId              the book identifier for S3 key generation
     * @param originalSourceForS3Key the source label used in the S3 key path
     * @param provenanceData      optional provenance metadata for audit/tracking
     */
    public static final class ProcessedCoverUploadRequest {
        private final byte[] processedImageBytes;
        private final String fileExtension;
        private final String mimeType;
        private final int width;
        private final int height;
        private final String bookId;
        private final String originalSourceForS3Key;
        private final ImageProvenanceData provenanceData;

        public ProcessedCoverUploadRequest(
            byte[] processedImageBytes,
            String fileExtension,
            String mimeType,
            int width,
            int height,
            String bookId,
            String originalSourceForS3Key,
            @Nullable ImageProvenanceData provenanceData) {
            this.processedImageBytes = processedImageBytes;
            this.fileExtension = fileExtension;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.bookId = bookId;
            this.originalSourceForS3Key = originalSourceForS3Key;
            this.provenanceData = provenanceData;
        }

        public byte[] processedImageBytes() { return processedImageBytes; }
        public String fileExtension() { return fileExtension; }
        public String mimeType() { return mimeType; }
        public int width() { return width; }
        public int height() { return height; }
        public String bookId() { return bookId; }
        public String originalSourceForS3Key() { return originalSourceForS3Key; }
        public ImageProvenanceData provenanceData() { return provenanceData; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProcessedCoverUploadRequest that = (ProcessedCoverUploadRequest) o;
            return width == that.width
                && height == that.height
                && java.util.Arrays.equals(processedImageBytes, that.processedImageBytes)
                && java.util.Objects.equals(fileExtension, that.fileExtension)
                && java.util.Objects.equals(mimeType, that.mimeType)
                && java.util.Objects.equals(bookId, that.bookId)
                && java.util.Objects.equals(originalSourceForS3Key, that.originalSourceForS3Key)
                && java.util.Objects.equals(provenanceData, that.provenanceData);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                java.util.Arrays.hashCode(processedImageBytes),
                fileExtension, mimeType, width, height, bookId, originalSourceForS3Key, provenanceData
            );
        }

        @Override
        public String toString() {
            return "ProcessedCoverUploadRequest{" +
                "processedImageBytes=" + processedImageBytes.length + " bytes" +
                ", fileExtension='" + fileExtension + '\'' +
                ", mimeType='" + mimeType + '\'' +
                ", width=" + width +
                ", height=" + height +
                ", bookId='" + bookId + '\'' +
                ", originalSourceForS3Key='" + originalSourceForS3Key + '\'' +
                ", provenanceData=" + provenanceData +
                '}';
        }
    }

    /**
     * Uploads a processed image payload directly to S3 without additional processing.
     */
    public Mono<ImageDetails> uploadProcessedCoverToS3Async(ProcessedCoverUploadRequest request) {
        try {
            S3UploadValidation.validateProcessedUploadInput(
                request.processedImageBytes(),
                request.fileExtension(),
                request.mimeType(),
                request.width(),
                request.height(),
                request.bookId()
            );
        } catch (S3UploadException exception) {
            logger.warn("Skipping processed S3 upload for book {}: {}", request.bookId(), exception.getMessage());
            return Mono.error(exception);
        }

        if (request.processedImageBytes().length > this.maxFileSizeBytes) {
            logger.warn(
                "Book ID {}: Processed image too large for S3 (size: {} bytes, max: {} bytes). Will not upload.",
                request.bookId(),
                request.processedImageBytes().length,
                this.maxFileSizeBytes
            );
            return Mono.error(new CoverTooLargeException(request.bookId(), null, request.processedImageBytes().length, this.maxFileSizeBytes));
        }

        String s3Source = S3UploadValidation.resolveUploadSource(request.originalSourceForS3Key());
        ProcessedImage processedImage = new ProcessedImage(
            request.processedImageBytes(),
            request.fileExtension(),
            request.mimeType(),
            request.width(),
            request.height(),
            false,
            true,
            null,
            null
        );

        // The provenance parameter remains for API compatibility while persistence is handled elsewhere.
        return s3CoverStorageGateway
            .uploadProcessedCover(new CoverUploadPayload(
                request.bookId(),
                request.fileExtension(),
                s3Source,
                request.processedImageBytes(),
                request.mimeType(),
                processedImage
            ))
            .onErrorMap(error -> mapUnexpectedUploadError(error, request.bookId(), null));
    }

    private Mono<ImageDetails> processDownloadedImageForUpload(byte[] rawImageBytes,
                                                               String imageUrl,
                                                               String bookId,
                                                               String s3Source) {
        logger.debug("Book ID {}: Downloaded {} bytes from {}. Starting image processing.", bookId, rawImageBytes.length, imageUrl);
        return Mono.fromFuture(imageProcessingService.processImageForS3(rawImageBytes, bookId))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(processedImage -> {
                if (!processedImage.isProcessingSuccessful()) {
                    String reason = processedImage.getProcessingError();
                    logger.warn("Book ID {}: Image processing failed: {}. Will not upload to S3.", bookId, reason);
                    CoverRejectionReason rejection = processedImage.rejectionReason();
                    if (rejection != null) {
                        return Mono.error(new CoverProcessingException(bookId, imageUrl, rejection, reason));
                    }
                    return Mono.error(new CoverProcessingException(bookId, imageUrl, reason));
                }

                byte[] imageBytesForS3 = processedImage.getProcessedBytes();
                if (imageBytesForS3.length > this.maxFileSizeBytes) {
                    logger.warn(
                        "Book ID {}: Processed image too large (size: {} bytes, max: {} bytes). URL: {}. Will not upload to S3.",
                        bookId,
                        imageBytesForS3.length,
                        this.maxFileSizeBytes,
                        imageUrl
                    );
                    return Mono.error(new CoverTooLargeException(bookId, imageUrl, imageBytesForS3.length, this.maxFileSizeBytes));
                }

                return s3CoverStorageGateway.uploadProcessedCover(new CoverUploadPayload(
                    bookId,
                    processedImage.getNewFileExtension(),
                    s3Source,
                    imageBytesForS3,
                    processedImage.getNewMimeType(),
                    processedImage
                ));
            });
    }

    private Throwable mapUnexpectedUploadError(Throwable error, String bookId, String imageUrl) {
        if (error instanceof S3CoverUploadException) {
            return error;
        }
        logger.error("Unexpected exception during S3 upload for book {}: {}. URL: {}", bookId, error.getMessage(), imageUrl, error);
        return new S3UploadException(bookId, imageUrl, error);
    }
}
