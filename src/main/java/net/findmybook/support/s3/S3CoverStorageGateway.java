package net.findmybook.support.s3;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import net.findmybook.exception.S3UploadException;
import net.findmybook.model.image.ImageDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Central gateway for S3 cover storage operations.
 *
 * <p>This component is the single infrastructure entry point for S3 cover lookup and upload
 * behavior. Application services should orchestrate workflow here instead of invoking AWS SDK
 * operations directly.</p>
 */
@Component
@EnableConfigurationProperties(S3CoverStorageProperties.class)
public class S3CoverStorageGateway {

    private static final Logger logger = LoggerFactory.getLogger(S3CoverStorageGateway.class);

    private final S3Client s3Client;
    private final S3CoverStorageProperties s3CoverStorageProperties;
    private final Environment environment;
    private final S3CoverObjectLookupSupport s3CoverObjectLookupSupport;
    private final S3CoverUrlSupport s3CoverUrlSupport;
    private final S3CoverUploadExecutor s3CoverUploadExecutor;

    /** Runtime override — set to false when configured as enabled but S3 client is missing. */
    private volatile boolean runtimeEnabled;

    /**
     * @implNote Six parameters are architecturally necessary: the S3 client, storage properties,
     *     and environment for profile detection are infrastructure concerns, while the three
     *     extracted support classes (lookup, URL, upload) each own a distinct S3 responsibility.
     *     Collapsing them into a parameter object would create an artificial wrapper with no
     *     cohesive meaning.
     */
    public S3CoverStorageGateway(@Nullable S3Client s3Client,
                                 S3CoverStorageProperties s3CoverStorageProperties,
                                 Environment environment,
                                 S3CoverObjectLookupSupport s3CoverObjectLookupSupport,
                                 S3CoverUrlSupport s3CoverUrlSupport,
                                 S3CoverUploadExecutor s3CoverUploadExecutor) {
        this.s3Client = s3Client;
        this.s3CoverStorageProperties = s3CoverStorageProperties;
        this.environment = environment;
        this.s3CoverObjectLookupSupport = s3CoverObjectLookupSupport;
        this.s3CoverUrlSupport = s3CoverUrlSupport;
        this.s3CoverUploadExecutor = s3CoverUploadExecutor;
        this.runtimeEnabled = s3CoverStorageProperties.enabled();
    }

    /**
     * Emits startup diagnostics for S3 read/write capability.
     */
    @PostConstruct
    public void logStartupStatus() {
        boolean s3Enabled = s3CoverStorageProperties.enabled();
        boolean s3WriteEnabled = s3CoverStorageProperties.writeEnabled();

        if (s3Client == null && s3Enabled) {
            logClientMisconfigured();
            this.runtimeEnabled = false;
        } else if (s3Client != null && s3Enabled) {
            String bucketName = s3CoverObjectLookupSupport.bucketName();
            logger.info(
                "S3 cover storage gateway initialized successfully. Bucket: {}",
                bucketName
            );
        } else {
            logger.info("S3 cover storage is disabled by configuration (s3.enabled=false or missing credentials).");
        }

        if (!s3WriteEnabled) {
            if (isDevOrTestProfile()) {
                logger.warn("S3_WRITE_ENABLED=false - cover uploads disabled (expected in dev/test).");
                logger.warn("Set S3_WRITE_ENABLED=true to enable uploads outside dev/test.");
            } else {
                logger.error("S3_WRITE_ENABLED=false - all cover uploads are disabled.");
            }
        } else if (isReadAvailable()) {
            String bucketName = s3CoverObjectLookupSupport.bucketName();
            logger.info(
                "S3 cover uploads enabled (bucket: {}, write-enabled: true).",
                bucketName
            );
        }
    }

    /**
     * Indicates if S3 reads are currently available.
     */
    public boolean isReadAvailable() {
        return runtimeEnabled && s3Client != null;
    }

    /**
     * Fails fast when upload preconditions are not met.
     */
    public void ensureUploadReady(String bookId, String imageUrl) {
        if (!isReadAvailable()) {
            throw new S3UploadException(
                "S3 uploads are unavailable because the S3 client is not configured",
                bookId,
                imageUrl,
                false,
                null
            );
        }
        if (!s3CoverStorageProperties.writeEnabled()) {
            throw new S3UploadException(
                "S3 uploads are disabled by configuration (S3_WRITE_ENABLED=false)",
                bookId,
                imageUrl,
                false,
                null
            );
        }
    }

    /**
     * Resolves the first available cover image across configured source labels.
     */
    public Mono<Optional<ImageDetails>> findFirstAvailableCover(String bookId,
                                                                String fileExtension,
                                                                List<String> sourceLabels) {
        if (!isReadAvailable()) {
            return Mono.just(Optional.empty());
        }
        return Flux.fromIterable(sourceLabels)
            .concatMap(source ->
                s3CoverObjectLookupSupport.locateExistingKeyAsync(bookId, fileExtension, source)
                    .map(key -> Optional.of(s3CoverUrlSupport.buildImageDetailsFromKey(key, null)))
                    .switchIfEmpty(Mono.just(Optional.empty()))
            )
            .filter(Optional::isPresent)
            .next()
            .defaultIfEmpty(Optional.empty());
    }

    /**
     * Uploads processed cover bytes through the central S3 executor.
     */
    public Mono<ImageDetails> uploadProcessedCover(CoverUploadPayload payload) {
        ensureUploadReady(payload.bookId(), null);
        return s3CoverUploadExecutor.uploadOrReuseExistingObject(payload);
    }

    private boolean isDevOrTestProfile() {
        return environment.acceptsProfiles(Profiles.of("dev", "test"));
    }

    private void logClientMisconfigured() {
        if (isDevOrTestProfile()) {
            logger.warn("S3 client is null while s3.enabled=true (dev/test profile).");
            logger.warn("Check S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY, and S3_SERVER_URL if S3 should be enabled.");
        } else {
            logger.error("S3 client is null while s3.enabled=true.");
            logger.error("Check S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY, and S3_SERVER_URL.");
            logger.error("All S3 cover uploads will be disabled until configuration is fixed.");
        }
    }
}
