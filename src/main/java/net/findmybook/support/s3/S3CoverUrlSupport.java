package net.findmybook.support.s3;

import java.util.Optional;
import jakarta.annotation.PostConstruct;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.model.image.ProcessedImage;
import net.findmybook.util.ValidationUtils;
import net.findmybook.util.cover.CoverUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/**
 * Builds CDN URLs and materializes S3-backed image details from storage keys.
 */
@Component
public class S3CoverUrlSupport {

    private static final Logger logger = LoggerFactory.getLogger(S3CoverUrlSupport.class);

    private final String s3CdnUrl;
    private final String s3PublicCdnUrl;
    private final String s3ServerUrl;
    private final String s3BucketName;

    public S3CoverUrlSupport(@Value("${s3.cdn-url}") String s3CdnUrl,
                             @Value("${s3.public-cdn-url:${S3_PUBLIC_CDN_URL:}}") String s3PublicCdnUrl,
                             @Value("${s3.server-url:https://sfo3.digitaloceanspaces.com}") String s3ServerUrl,
                             @Value("${s3.bucket-name}") String s3BucketName) {
        this.s3CdnUrl = s3CdnUrl;
        this.s3PublicCdnUrl = s3PublicCdnUrl;
        this.s3ServerUrl = s3ServerUrl;
        this.s3BucketName = s3BucketName;
    }

    /**
     * Sets shared resolver CDN base once S3 URL properties are available.
     */
    @PostConstruct
    void configureResolver() {
        resolveCdnBase().ifPresent(CoverUrlResolver::setCdnBase);
    }

    /**
     * Builds image details for a known S3 key.
     */
    public ImageDetails buildImageDetailsFromKey(String s3Key, ProcessedImage processedImage) {
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

    /**
     * Builds the externally accessible CDN URL for a storage key.
     */
    public Optional<String> buildCdnUrl(String s3Key) {
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
}
