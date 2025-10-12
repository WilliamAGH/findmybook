/**
 * Service for managing book cover images in S3 object storage
 *
 * @author William Callahan
 *
 * Features:
 * - Provides durable object storage for book cover images
 * - Manages image uploading and URL generation
 * - Implements in-memory caching for optimized performance
 * - Supports multiple resolution variants of cover images
 * - Handles image metadata and resolution preferences
 * - Integrates with content delivery networks for fast global access
 */
package com.williamcallahan.book_recommendation_engine.service.image;

import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.model.image.CoverImageSource;
import com.williamcallahan.book_recommendation_engine.model.image.ImageDetails;
import com.williamcallahan.book_recommendation_engine.model.image.ImageProvenanceData;
import com.williamcallahan.book_recommendation_engine.model.image.ImageResolutionPreference;
import com.williamcallahan.book_recommendation_engine.model.image.ProcessedImage;
import com.williamcallahan.book_recommendation_engine.util.ValidationUtils;
import com.williamcallahan.book_recommendation_engine.util.cover.CoverIdentifierResolver;
import com.williamcallahan.book_recommendation_engine.util.cover.S3KeyGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
@Service
public class S3BookCoverService implements ExternalCoverService {
    private static final Logger logger = LoggerFactory.getLogger(S3BookCoverService.class);
    private static final List<String> DEFAULT_SOURCE_BASE_LABELS = List.of("google-books", "open-library", "longitood");
    
    @Value("${s3.bucket-name}")
    private String s3BucketName;
    
    @Value("${s3.cdn-url}")
    private String s3CdnUrl;
    
    @Value("${s3.public-cdn-url:${S3_PUBLIC_CDN_URL:}}")
    private String s3PublicCdnUrl;
    
    @Value("${s3.server-url:https://sfo3.digitaloceanspaces.com}")
    private String s3ServerUrl;
    
    @Value("${s3.access-key-id:}")
    private String s3AccessKeyId;
    
    @Value("${s3.secret-access-key:}")
    private String s3SecretAccessKey;
    
    @Value("${s3.enabled:true}")
    private boolean s3EnabledCheck;

    @Value("${s3.write-enabled:${S3_WRITE_ENABLED:true}}")
    private boolean s3WriteEnabled;

    @Value("${app.cover-cache.max-file-size-bytes:5242880}") 
    private long maxFileSizeBytes; 
    
    private final S3Client s3Client;
    private final WebClient webClient;
    private final ImageProcessingService imageProcessingService;

    private final Cache<String, Boolean> objectExistsCache;

    public S3BookCoverService(WebClient.Builder webClientBuilder,
                               ImageProcessingService imageProcessingService,
                               S3Client s3Client) {
        this.webClient = webClientBuilder.build();
        this.imageProcessingService = imageProcessingService;
        this.s3Client = s3Client;
        this.objectExistsCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();
        
        if (this.s3Client == null && this.s3EnabledCheck) {
            logger.warn("S3 is configured as enabled, but S3Client bean was not injected (likely due to missing credentials/config). S3 functionality will be disabled.");
            this.s3EnabledCheck = false;
        } else if (this.s3Client != null && this.s3EnabledCheck) {
             logger.info("S3BookCoverService initialized with injected S3Client. Bucket: {}, CDN URL: {}", s3BucketName, s3CdnUrl);
        } else {
            logger.info("S3BookCoverService: S3 is disabled by configuration.");
        }
    }

    /**
     * SSRF protection: validates that an image URL is safe to download
     * @param imageUrl the URL to validate
     * @return true if the URL is allowed, false otherwise
     */
    private boolean isAllowedImageUrl(String imageUrl) {
        try {
            java.net.URI uri = java.net.URI.create(imageUrl);
            return isAllowedImageHost(uri);
        } catch (Exception e) {
            logger.warn("Invalid image URL format, blocking: {}", imageUrl);
            return false;
        }
    }

    /**
     * SSRF protection: validates that a URI points to an allowed image host
     * and does not resolve to private/internal IP addresses
     * @param uri the URI to validate
     * @return true if the host is allowed and safe, false otherwise
     */
    private boolean isAllowedImageHost(java.net.URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        // Allowlist of known safe image CDN hosts
        String[] allowedHosts = {
            "books.googleusercontent.com",
            "covers.openlibrary.org",
            "images-na.ssl-images-amazon.com",
            "images-eu.ssl-images-amazon.com",
            "m.media-amazon.com",
            "images.amazon.com",
            "d1w7fb2mkkr3kw.cloudfront.net", // Open Library CDN
            "ia600100.us.archive.org",  // Internet Archive
            "ia800100.us.archive.org",
            "ia601400.us.archive.org",
            "ia800200.us.archive.org",
            "syndetics.com",
            "cdn.penguin.com",
            "images.penguinrandomhouse.com",
            "longitood.com" // Longitood service
        };

        boolean inAllowlist = false;
        for (String allowedHost : allowedHosts) {
            if (host.equals(allowedHost) || host.endsWith("." + allowedHost)) {
                inAllowlist = true;
                break;
            }
        }
        if (!inAllowlist) {
            return false;
        }

        // Additional protection: block private/internal IP addresses
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isSiteLocalAddress()) {
                logger.warn("Blocked private IP address for host: {}", host);
                return false;
            }
            String ip = addr.getHostAddress();
            // Block common private/reserved ranges
            if (ip.startsWith("169.254.") || // Link-local
                ip.startsWith("127.") ||      // Loopback
                ip.startsWith("10.") ||        // Private Class A
                ip.startsWith("192.168.") ||   // Private Class C
                ip.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) { // Private Class B
                logger.warn("Blocked reserved IP range for host: {} -> {}", host, ip);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Could not resolve host for validation, blocking: {}", host);
            return false;
        }

        return true;
    }

    /**
     * Cleanup method called during bean destruction
     * - S3Client lifecycle managed by Spring
     */
    @PreDestroy
    public void destroy() {
        logger.info("S3BookCoverService @PreDestroy called. S3Client lifecycle managed by Spring config.");
    }

    /**
     * Checks if S3 functionality is enabled and the client is available
     * @return true if S3 is enabled and usable, false otherwise
     */
    public boolean isS3Enabled() {
        return this.s3EnabledCheck && this.s3Client != null;
    }
    
    /**
     * Generates the S3 object key for a book cover image
     * - Creates standardized path structure for book cover images
     * - Validates book ID for valid characters
     * - Normalizes source identifier for S3 compatibility
     * - Defaults to JPG extension if none provided
     * 
     * @param bookId Unique identifier for the book
     * @param fileExtension File extension including the dot (e.g. ".jpg")
     * @param source Origin source identifier (e.g. "google-books", "open-library")
     * @return Constructed S3 key for the image
     * @throws IllegalArgumentException if bookId is null, empty or contains invalid characters
     */
    public String generateS3Key(String bookId, String fileExtension, String source) {
        return S3KeyGenerator.generateCoverKeyFromRawSource(bookId, fileExtension, source);
    }

    private static List<String> candidateSegmentsFor(String rawSource) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();

        if (rawSource != null) {
            String trimmed = rawSource.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                variants.add(trimmed);
                variants.add(trimmed.replace(' ', '-'));
                variants.add(trimmed.replace(' ', '_'));
            }
        }

        String canonical = S3KeyGenerator.normalizeRawSource(rawSource);
        if (canonical != null && !canonical.isBlank()) {
            variants.add(canonical);
            variants.add(canonical.replace('-', '_'));
            variants.add(canonical.replace('_', '-'));
        }

        variants.removeIf(String::isBlank);
        return new ArrayList<>(variants);
    }

    private List<String> buildCandidateKeys(String bookId, String fileExtension, String rawSource) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String segment : candidateSegmentsFor(rawSource)) {
            keys.add(S3KeyGenerator.generateCoverKeyFromRawSource(bookId, fileExtension, segment));
        }
        return new ArrayList<>(keys);
    }

    private Mono<String> locateExistingKeyAsync(String bookId, String fileExtension, String rawSource) {
        if (!s3EnabledCheck || s3Client == null) {
            return Mono.empty();
        }
        List<String> candidateKeys = buildCandidateKeys(bookId, fileExtension, rawSource);
        return Flux.fromIterable(candidateKeys)
            .concatMap(key -> headObjectExistsAsync(key)
                .filter(Boolean::booleanValue)
                .map(exists -> key))
            .next();
    }

    private Optional<String> locateExistingKeySync(String bookId, String fileExtension, String rawSource) {
        if (!s3EnabledCheck || s3Client == null) {
            return Optional.empty();
        }
        for (String key : buildCandidateKeys(bookId, fileExtension, rawSource)) {
            if (headObjectExistsSync(key)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    private Mono<Boolean> headObjectExistsAsync(String s3Key) {
        Boolean cached = objectExistsCache.getIfPresent(s3Key);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> headObjectExistsSync(s3Key))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private boolean headObjectExistsSync(String s3Key) {
        Boolean cached = objectExistsCache.getIfPresent(s3Key);
        if (cached != null) {
            return cached;
        }

        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(s3BucketName).key(s3Key).build());
            objectExistsCache.put(s3Key, true);
            return true;
        } catch (NoSuchKeyException e) {
            objectExistsCache.put(s3Key, false);
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception s3e) {
            if (s3e.statusCode() == 404) {
                objectExistsCache.put(s3Key, false);
                return false;
            }
            logger.error("S3Exception checking object existence for key {}: Status={}, Message={}", s3Key, s3e.statusCode(), s3e.getMessage());
            objectExistsCache.put(s3Key, false);
            return false;
        } catch (Exception e) {
            logger.error("Unexpected error checking object existence for key {}: {}", s3Key, e.getMessage(), e);
            objectExistsCache.put(s3Key, false);
            return false;
        }
    }

    private ImageDetails buildImageDetailsFromKey(String s3Key) {
        return buildImageDetailsFromKey(s3Key, null);
    }

    private ImageDetails buildImageDetailsFromKey(String s3Key, ProcessedImage processedImage) {
        Integer width = processedImage != null ? processedImage.getWidth() : null;
        Integer height = processedImage != null ? processedImage.getHeight() : null;

        ImageDetails details = new ImageDetails(
            null,
            "S3",
            s3Key,
            CoverImageSource.UNDEFINED,
            ImageResolutionPreference.ORIGINAL,
            width,
            height
        );

        buildCdnUrl(s3Key).ifPresent(cdnUrl -> {
            details.setUrlOrPath(cdnUrl);
            details.setStorageLocation(ImageDetails.STORAGE_S3);
            details.setStorageKey(s3Key);
        });

        return details;
    }

    private Optional<String> buildCdnUrl(String s3Key) {
        return resolveCdnBase()
            .map(base -> appendPath(base, s3Key))
            .or(() -> {
                logger.warn("No CDN base configured; unable to build S3 cover URL for key {}", s3Key);
                return Optional.empty();
            });
    }

    private Optional<String> resolveCdnBase() {
        if (ValidationUtils.hasText(s3PublicCdnUrl)) {
            return Optional.of(normalizeBase(s3PublicCdnUrl));
        }
        if (ValidationUtils.hasText(s3CdnUrl)) {
            return Optional.of(normalizeBase(s3CdnUrl));
        }
        if (ValidationUtils.hasText(s3ServerUrl) && ValidationUtils.hasText(s3BucketName)) {
            String combined = appendPath(normalizeBase(s3ServerUrl), s3BucketName);
            return Optional.of(normalizeBase(combined));
        }
        return Optional.empty();
    }

    private String normalizeBase(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String appendPath(String base, String suffix) {
        if (base == null || base.isBlank()) {
            return suffix;
        }
        if (base.endsWith("/")) {
            return base + suffix;
        }
        return base + "/" + suffix;
    }

    private Mono<ImageDetails> handleExistingObject(String existingKey,
                                                    String canonicalKey,
                                                    byte[] imageBytesForS3,
                                                    String mimeTypeForS3,
                                                    String bookId,
                                                    String fileExtensionForS3,
                                                    String rawSource,
                                                    ProcessedImage processedImage,
                                                    ImageProvenanceData provenanceData) {
        return Mono.fromCallable(() -> s3Client.headObject(HeadObjectRequest.builder().bucket(s3BucketName).key(existingKey).build()))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(headResponse -> {
                if (existingKey.equals(canonicalKey) && headResponse.contentLength() == imageBytesForS3.length) {
                    logger.info("Processed cover for book {} already exists in S3 with same size, skipping upload. Key: {}", bookId, existingKey);
                    objectExistsCache.put(existingKey, true);
                    return Mono.just(buildImageDetailsFromKey(existingKey, processedImage));
                }

                if (!existingKey.equals(canonicalKey)) {
                    logger.info("Book {} has legacy S3 key {}. Uploading canonical key {} for future lookups.", bookId, existingKey, canonicalKey);
                }

                return uploadToS3Internal(canonicalKey, imageBytesForS3, mimeTypeForS3, bookId, fileExtensionForS3, rawSource, processedImage, provenanceData);
            })
            .onErrorResume(NoSuchKeyException.class, e -> uploadToS3Internal(canonicalKey, imageBytesForS3, mimeTypeForS3, bookId, fileExtensionForS3, rawSource, processedImage, provenanceData))
            .onErrorResume(e -> {
                logger.warn("Error checking existing S3 object for book {}: {}. Proceeding with canonical upload.", bookId, e.getMessage());
                return uploadToS3Internal(canonicalKey, imageBytesForS3, mimeTypeForS3, bookId, fileExtensionForS3, rawSource, processedImage, provenanceData);
            });
    }

    /**
     * Fetches cover image from S3 storage for a given book
     * - Checks S3 storage for existing cover image
     * - Tries multiple source identifiers to find any available cover
     * - Returns image details with CDN URL if found
     * - Returns empty result if book has no valid identifier or S3 is disabled
     * 
     * @param book Book object containing identifiers to search with
     * @return CompletableFuture with Optional<ImageDetails> if cover exists in S3, empty Optional otherwise
     */
    @Override
    public CompletableFuture<Optional<ImageDetails>> fetchCover(Book book) {
        if (!s3EnabledCheck || s3Client == null || book == null) {
            if (!s3EnabledCheck || s3Client == null) logger.debug("S3 fetchCover skipped: S3 disabled or S3Client not available.");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String bookKey = CoverIdentifierResolver.getPreferredIsbn(book);
        if (!ValidationUtils.hasText(bookKey)) {
            bookKey = CoverIdentifierResolver.resolve(book);
        }
        if (!ValidationUtils.hasText(bookKey)) {
            bookKey = book.getId();
        }

        if (!ValidationUtils.hasText(bookKey)) {
            logger.warn("Cannot fetch S3 cover for book without a valid ID or ISBN. Title: {}", book.getTitle());
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final String finalBookKey = bookKey;
        String fileExtension = ".jpg"; 
        
        return Flux.fromIterable(DEFAULT_SOURCE_BASE_LABELS)
            .concatMap(baseLabel ->
                locateExistingKeyAsync(finalBookKey, fileExtension, baseLabel)
                    .map(existingKey -> {
                        String resolvedUrl = buildCdnUrl(existingKey).orElse(null);
                        logger.debug("Found existing S3 cover for book {} using source '{}': {}", finalBookKey, baseLabel, resolvedUrl);
                        return Optional.of(buildImageDetailsFromKey(existingKey));
                    })
                    .switchIfEmpty(Mono.just(Optional.empty()))
            )
            .filter(Optional::isPresent)
            .next()
            .defaultIfEmpty(Optional.empty())
            .doOnTerminate(() -> logger.debug("S3 cover check completed for book {}.", finalBookKey))
            .toFuture();
    }
    
    /**
     * Synchronous version for checking if a cover exists in S3
     * - Used for internal operations where blocking is acceptable
     * - Checks cache and makes direct S3 HEAD request
     * 
     * @param bookId Book identifier for the S3 key
     * @param fileExtension File extension to append to the key
     * @param source Source identifier string for the key
     * @return Boolean indicating if the object exists in S3
     */
    public boolean coverExistsInS3(String bookId, String fileExtension, String source) {
        return locateExistingKeySync(bookId, fileExtension, source).isPresent();
    }

    /**
     * Asynchronously checks if a cover image exists in S3 for specific parameters
     * - Checks in-memory cache first to avoid redundant S3 calls
     * - Makes non-blocking HEAD request to S3 if not found in cache
     * - Updates cache with results to improve future lookup performance
     * - Returns false for any errors without propagating exceptions
     * 
     * @param bookId Book identifier for the S3 key
     * @param fileExtension File extension to append to the key
     * @param source Source identifier string for the key
     * @return Mono containing boolean indicating if the object exists in S3
     */
    public Mono<Boolean> coverExistsInS3Async(String bookId, String fileExtension, String source) {
        return locateExistingKeyAsync(bookId, fileExtension, source).hasElement();
    }

    public Mono<Boolean> coverExistsInS3Async(String bookId, String fileExtension) {
        return Flux.concat(
                Flux.fromIterable(DEFAULT_SOURCE_BASE_LABELS)
                    .concatMap(label -> coverExistsInS3Async(bookId, fileExtension, label)),
                coverExistsInS3Async(bookId, fileExtension, "unknown")
            )
            .filter(Boolean::booleanValue)
            .next()
            .defaultIfEmpty(false);
    }

    public Mono<ImageDetails> uploadCoverToS3Async(String imageUrl, String bookId, String source) {
        return uploadCoverToS3Async(imageUrl, bookId, source, null);
    }

    public Mono<ImageDetails> uploadCoverToS3Async(String imageUrl, String bookId, String source, ImageProvenanceData provenanceData) {
        if (!s3EnabledCheck || s3Client == null || imageUrl == null || imageUrl.isEmpty() || bookId == null || bookId.isEmpty()) {
            logger.debug("S3 upload skipped: S3 disabled/S3Client not available, or imageUrl/bookId is null/empty. ImageUrl: {}, BookId: {}", imageUrl, bookId);
            return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(imageUrl, source, imageUrl, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL));
        }
        final String s3Source = (source != null && !source.isEmpty()) ? source : "unknown";

        // SSRF protection: validate URL before downloading
        if (!isAllowedImageUrl(imageUrl)) {
            logger.warn("Blocked non-allowed or potentially unsafe image URL for book {}: {}", bookId, imageUrl);
            return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(imageUrl, source, "blocked-unsafe-url-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL));
        }

        return webClient.get().uri(imageUrl).retrieve().bodyToMono(byte[].class)
            .timeout(Duration.ofSeconds(10))
            .flatMap(rawImageBytes -> {
                logger.debug("Book ID {}: Downloaded {} bytes from {}. Starting image processing.", bookId, rawImageBytes.length, imageUrl);
                // Convert CompletableFuture to Mono and continue reactive chain
                return Mono.fromFuture(imageProcessingService.processImageForS3(rawImageBytes, bookId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(processedImage -> {
                        if (!processedImage.isProcessingSuccessful()) {
                            logger.warn("Book ID {}: Image processing failed. Reason: {}. Will not upload to S3.", bookId, processedImage.getProcessingError());
                            return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(imageUrl, source, "processing-failed-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL));
                        }
                        logger.debug("Book ID {}: Image processing successful. New size: {}x{}, Extension: {}, MimeType: {}.",
                                     bookId, processedImage.getWidth(), processedImage.getHeight(), processedImage.getNewFileExtension(), processedImage.getNewMimeType());

                        byte[] imageBytesForS3 = processedImage.getProcessedBytes();
                        String fileExtensionForS3 = processedImage.getNewFileExtension();
                        String mimeTypeForS3 = processedImage.getNewMimeType();

                        if (imageBytesForS3.length > this.maxFileSizeBytes) {
                            logger.warn("Book ID {}: Processed image too large (size: {} bytes, max: {} bytes). URL: {}. Will not upload to S3.",
                                        bookId, imageBytesForS3.length, this.maxFileSizeBytes, imageUrl);
                            return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(imageUrl, source, "processed-image-too-large-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL));
                        }

                        String canonicalKey = generateS3Key(bookId, fileExtensionForS3, s3Source);

                        return locateExistingKeyAsync(bookId, fileExtensionForS3, s3Source)
                            .flatMap(existingKey -> handleExistingObject(
                                existingKey,
                                canonicalKey,
                                imageBytesForS3,
                                mimeTypeForS3,
                                bookId,
                                fileExtensionForS3,
                                s3Source,
                                processedImage,
                                provenanceData
                            ))
                            .switchIfEmpty(uploadToS3Internal(
                                canonicalKey,
                                imageBytesForS3,
                                mimeTypeForS3,
                                bookId,
                                fileExtensionForS3,
                                s3Source,
                                processedImage,
                                provenanceData
                            ));
                    })
                    .onErrorResume(e -> { // Catches exceptions from imageProcessingService.processImageForS3 or subsequent reactive chain
                        logger.error("Unexpected exception during S3 upload (image processing or subsequent steps) for book {}: {}. URL: {}", bookId, e.getMessage(), imageUrl, e);
                        return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(imageUrl, source, "upload-process-exception-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL));
                    });
            })
            .onErrorResume(e -> {
                logger.error("Error downloading image for S3 upload for book {}: {}. URL: {}", bookId, e.getMessage(), imageUrl, e);
                return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(imageUrl, source, "download-failed-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL));
            });
    }

    private Mono<com.williamcallahan.book_recommendation_engine.model.image.ImageDetails> uploadToS3Internal(String s3Key, byte[] imageBytesForS3, String mimeTypeForS3, String bookId, String fileExtensionForS3, String s3Source, ProcessedImage processedImage, ImageProvenanceData provenanceData) {
        if (!s3WriteEnabled) {
            logger.debug("S3 write disabled; skipping upload for book {} (key {}).", bookId, s3Key);
            com.williamcallahan.book_recommendation_engine.model.image.ImageDetails details =
                new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(
                    null,
                    s3Source,
                    null,
                    CoverImageSource.UNDEFINED,
                    ImageResolutionPreference.ORIGINAL,
                    processedImage != null ? processedImage.width() : null,
                    processedImage != null ? processedImage.height() : null
                );
            details.setStorageLocation(ImageDetails.STORAGE_LOCAL);
            return Mono.just(details);
        }
        return Mono.fromCallable(() -> {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType(mimeTypeForS3)
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytesForS3));
            
            objectExistsCache.put(s3Key, true);
            logger.info("Successfully uploaded processed cover for book {} to S3. Key: {}", bookId, s3Key);

            return buildImageDetailsFromKey(s3Key, processedImage);
        }).subscribeOn(Schedulers.boundedElastic());
    }
 
    /**
     * Synchronous version of cover upload with explicit source
     *
     * @param imageUrl URL of the image to upload
     * @param bookId Book identifier
     * @param source Source identifier
     * @return ImageDetails for the uploaded cover
     */
    public com.williamcallahan.book_recommendation_engine.model.image.ImageDetails uploadCoverToS3(String imageUrl, String bookId, String source) {
        try {
            return uploadCoverToS3Async(imageUrl, bookId, source).block(Duration.ofSeconds(15));
        } catch (Exception e) {
            logger.error("Error or timeout uploading cover to S3 for book {} from URL {}: {}", bookId, imageUrl, e.getMessage());
            return new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(imageUrl, source, imageUrl, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL);
        }
    }
    
    /**
     * Convenience method that derives source from URL
     */
    public com.williamcallahan.book_recommendation_engine.model.image.ImageDetails uploadCoverToS3(String imageUrl, String bookId) {
        String source = "google-books"; 
        if (imageUrl != null) {
            if (imageUrl.contains("openlibrary.org")) source = "open-library";
            else if (imageUrl.contains("longitood.com")) source = "longitood";
        }
        return uploadCoverToS3(imageUrl, bookId, source);
    }

    /**
     * Generates CDN URL for a book cover with known source
     */
    public String getS3CoverUrl(String bookId, String fileExtension, String source) {
        Optional<String> existingKey = locateExistingKeySync(bookId, fileExtension, source);
        if (existingKey.isPresent()) {
            return buildCdnUrl(existingKey.get()).orElse(null);
        }

        String fallbackKey = S3KeyGenerator.generateCoverKeyFromRawSource(bookId, fileExtension, source);
        logger.warn("getS3CoverUrl(bookId={}, fileExtension={}, source={}) found no existing object. Returning canonical key {}.",
            bookId, fileExtension, source, fallbackKey);
        return buildCdnUrl(fallbackKey).orElse(null);
    }

    /**
     * Finds existing cover URL by checking multiple sources
     * 
     * @param bookId Book identifier
     * @param fileExtension File extension with leading dot
     * @return CDN URL for the first found cover or null if none exists
     */
    public String getS3CoverUrl(String bookId, String fileExtension) {
        for (String baseLabel : DEFAULT_SOURCE_BASE_LABELS) {
            Optional<String> key = locateExistingKeySync(bookId, fileExtension, baseLabel);
            if (key.isPresent()) {
                return buildCdnUrl(key.get()).orElse(null);
            }
        }

        logger.warn("getS3CoverUrl(bookId={}, fileExtension={}) could not locate any cover. Returning canonical unknown key.", bookId, fileExtension);
        return buildCdnUrl(S3KeyGenerator.generateCoverKeyFromRawSource(bookId, fileExtension, "unknown")).orElse(null);
    }

    /**
     * Extracts file extension from URL
     * 
     * @param url Image URL to parse
     * @return File extension with leading dot or default (.jpg) if none found
     */
    /**
     * @deprecated Deprecated 2025-10-01. Use {@link com.williamcallahan.book_recommendation_engine.util.UrlUtils#extractFileExtension(String)} instead.
     */
    @Deprecated(since = "2025-10-01", forRemoval = true)
    public String getFileExtensionFromUrl(String url) {
        String extension = ".jpg"; 
        if (url != null && url.contains(".")) {
            int queryParamIndex = url.indexOf("?");
            String urlWithoutParams = queryParamIndex > 0 ? url.substring(0, queryParamIndex) : url;
            int lastDotIndex = urlWithoutParams.lastIndexOf(".");
            if (lastDotIndex > 0 && lastDotIndex < urlWithoutParams.length() - 1) {
                String ext = urlWithoutParams.substring(lastDotIndex).toLowerCase();
                if (ext.matches("\\.(jpg|jpeg|png|gif|webp|svg)")) {
                    extension = ext;
                }
            }
        }
        return extension;
    }

    /**
     * Overloaded version without provenance data
     */
    public Mono<com.williamcallahan.book_recommendation_engine.model.image.ImageDetails> uploadProcessedCoverToS3Async(byte[] processedImageBytes, String fileExtension, String mimeType, int width, int height, String bookId, String originalSourceForS3Key) {
        return uploadProcessedCoverToS3Async(processedImageBytes, fileExtension, mimeType, width, height, bookId, originalSourceForS3Key, null);
    }

    /**
     * Uploads pre-processed cover image to S3
     * 
     * @param processedImageBytes The processed image bytes
     * @param fileExtension File extension with leading dot
     * @param mimeType MIME type for content header
     * @param width Image width
     * @param height Image height
     * @param bookId Book identifier
     * @param originalSourceForS3Key Source identifier for S3 key
     * @param provenanceData Optional image provenance data 
     * @return Mono with ImageDetails for the uploaded cover
     */
    public Mono<com.williamcallahan.book_recommendation_engine.model.image.ImageDetails> uploadProcessedCoverToS3Async(byte[] processedImageBytes, String fileExtension, String mimeType, int width, int height, String bookId, String originalSourceForS3Key, ImageProvenanceData provenanceData) {
        if (!s3EnabledCheck || s3Client == null || processedImageBytes == null || processedImageBytes.length == 0 || bookId == null || bookId.isEmpty()) {
            logger.debug("S3 upload of processed cover skipped: S3 disabled/S3Client not available, or image bytes/bookId is null/empty. BookId: {}", bookId);
            return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(null, originalSourceForS3Key, "processed-upload-skipped-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL, width, height));
        }
        final String s3Source = (originalSourceForS3Key != null && !originalSourceForS3Key.isEmpty()) ? originalSourceForS3Key : "unknown";

        try {
            if (processedImageBytes.length > this.maxFileSizeBytes) {
                logger.warn("Book ID {}: Processed image too large for S3 (size: {} bytes, max: {} bytes). Will not upload.", 
                            bookId, processedImageBytes.length, this.maxFileSizeBytes);
                return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(null, originalSourceForS3Key, "processed-image-too-large-for-s3-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL, width, height));
            }

            String s3Key = generateS3Key(bookId, fileExtension, s3Source);

            // Asynchronous check for existing object
            return coverExistsInS3Async(bookId, fileExtension, s3Source)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.fromCallable(() -> s3Client.headObject(HeadObjectRequest.builder().bucket(s3BucketName).key(s3Key).build()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(headResponse -> {
                                if (headResponse.contentLength() == processedImageBytes.length) {
                                    logger.info("Processed cover for book {} (from source {}) already exists in S3 with same size, skipping upload. Key: {}", bookId, s3Source, s3Key);
                                    String cdnUrl = getS3CoverUrl(bookId, fileExtension, s3Source);
                                    com.williamcallahan.book_recommendation_engine.model.image.ImageDetails details = 
                                        new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(
                                            cdnUrl, "S3", s3Key, CoverImageSource.UNDEFINED, 
                                            ImageResolutionPreference.ORIGINAL, width, height);
                                    details.setStorageLocation(ImageDetails.STORAGE_S3);
                                    details.setStorageKey(s3Key);
                                    return Mono.just(details);
                                }
                                return uploadToS3Internal(s3Key, processedImageBytes, mimeType, bookId, fileExtension, s3Source, new ProcessedImage(processedImageBytes, fileExtension, mimeType, width, height, true, null), provenanceData);
                            })
                            .onErrorResume(NoSuchKeyException.class, e -> uploadToS3Internal(s3Key, processedImageBytes, mimeType, bookId, fileExtension, s3Source, new ProcessedImage(processedImageBytes, fileExtension, mimeType, width, height, true, null), provenanceData))
                            .onErrorResume(e -> {
                                 logger.warn("Error checking existing S3 object for book {}: {}. Proceeding with upload.", bookId, e.getMessage());
                                 return uploadToS3Internal(s3Key, processedImageBytes, mimeType, bookId, fileExtension, s3Source, new ProcessedImage(processedImageBytes, fileExtension, mimeType, width, height, true, null), provenanceData);
                            });
                    } else {
                        return uploadToS3Internal(s3Key, processedImageBytes, mimeType, bookId, fileExtension, s3Source, new ProcessedImage(processedImageBytes, fileExtension, mimeType, width, height, true, null), provenanceData);
                    }
                });
        
        } catch (Exception e) { // Catch synchronous exceptions from this method's setup
            logger.error("Unexpected exception during S3 upload setup for processed cover for book {}: {}.", bookId, e.getMessage(), e);
            return Mono.just(new com.williamcallahan.book_recommendation_engine.model.image.ImageDetails(null, originalSourceForS3Key, "processed-upload-setup-exception-" + bookId, CoverImageSource.ANY, ImageResolutionPreference.ORIGINAL, width, height));
        }
    }

}
