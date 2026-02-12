package net.findmybook.service.image;

import net.findmybook.model.image.CoverImageSource;
import net.findmybook.util.IdGenerator;
import net.findmybook.util.UrlUtils;
import jakarta.annotation.Nullable;
import org.springframework.util.StringUtils;
import net.findmybook.util.cover.CoverUrlResolver;
import net.findmybook.util.cover.ImageDimensionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single Source of Truth for ALL book cover image persistence operations.
 * 
 * Consolidates and replaces logic from:
 * - CoverImageService (metadata persistence)
 * - Legacy background upload orchestrators
 * - S3BookCoverService (direct S3 upload handling)
 * - Scattered book_image_links INSERT/UPDATE statements
 * 
 * Responsibilities:
 * 1. Persist cover metadata to book_image_links table
 * 2. Persist canonical cover metadata directly into book_image_links
 * 3. Handle both initial estimates and post-upload actual dimensions
 * 4. Provide idempotent upsert operations
 * 
 * Phase 1: Metadata persistence (this implementation)
 * Phase 2: Integration with S3 upload workflows (next iteration)
 * 
 * @author William Callahan
 */
@Service
public class CoverPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(CoverPersistenceService.class);
    private static final String PLACEHOLDER_FILENAME = "placeholder-book-cover.svg";
    private static final Set<String> DISALLOWED_IMAGE_TYPES = Set.of("preferred", "fallback", "s3");

    private final JdbcTemplate jdbcTemplate;
    
    public CoverPersistenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Result record for cover persistence operations.
     * 
     * @param success Whether the operation succeeded
     * @param s3Key S3 object key if applicable, or external URL
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param highRes Whether the image is high-resolution
     */
    public record PersistenceResult(
        boolean success,
        String canonicalUrl,
        Integer width,
        Integer height,
        Boolean highRes
    ) {}
    
    /**
     * Persists all image variants from Google Books imageLinks and sets the best as primary.
     * 
     * This method handles the initial persistence when a book is first fetched from the API,
     * using estimated dimensions based on Google's image type classifications.
     * 
     * @param bookId Canonical book UUID
     * @param imageLinks Map from Google Books volumeInfo.imageLinks
     * @param source Provider name (e.g., "GOOGLE_BOOKS")
     * @return PersistenceResult with the canonical cover metadata
     */
    @Transactional
    public PersistenceResult persistFromGoogleImageLinks(UUID bookId, Map<String, String> imageLinks, String source) {
        if (imageLinks == null || imageLinks.isEmpty()) {
            log.debug("No image links provided for book {}", bookId);
            return new PersistenceResult(false, null, null, null, false);
        }
        
        String canonicalCoverUrl = null;
        Integer canonicalWidth = null;
        Integer canonicalHeight = null;
        Boolean canonicalHighRes = false;
        int bestPriority = Integer.MAX_VALUE;
        
        // Process all image types
        for (Map.Entry<String, String> entry : imageLinks.entrySet()) {
            String imageType = entry.getKey();
            String url = entry.getValue();
            
            if (url == null || url.isBlank()) {
                continue;
            }
            
            // Normalize to HTTPS
            String httpsUrl = UrlUtils.validateAndNormalize(url);
            if (!StringUtils.hasText(httpsUrl)) {
                log.warn("Skipping invalid image URL for book {} type {}: {}", bookId, imageType, url);
                continue;
            }
            
            // Estimate dimensions based on Google's image type
            ImageDimensionUtils.DimensionEstimate estimate = 
                ImageDimensionUtils.estimateFromGoogleType(imageType);
            
            try {
                // Upsert book_image_links row
                upsertImageLink(new ImageLinkParams(bookId, imageType, httpsUrl, source, estimate.width(), estimate.height(), estimate.highRes()));
                
                // Track best quality image for canonical cover
                int priority = ImageDimensionUtils.getTypePriority(imageType);
                if (priority < bestPriority) {
                    bestPriority = priority;
                    canonicalCoverUrl = httpsUrl;
                    canonicalWidth = estimate.width();
                    canonicalHeight = estimate.height();
                    canonicalHighRes = estimate.highRes();
                }
                
            } catch (IllegalArgumentException ex) {
                log.warn("Failed to persist image link for book {} type {}: {}",
                    bookId, imageType, ex.getMessage());
            } catch (DataAccessException ex) {
                log.error("Failed to persist image link for book {} type {} due to database error: {}",
                    bookId, imageType, ex.getMessage(), ex);
                throw ex;
            }
        }
        
        // Canonical cover now represented via highest-priority book_image_links row
        if (canonicalCoverUrl != null) {
            CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(new CoverUrlResolver.ResolveContext(
                canonicalCoverUrl,
                canonicalCoverUrl,
                canonicalWidth,
                canonicalHeight,
                canonicalHighRes
            ));

            upsertImageLink(new ImageLinkParams(bookId, "canonical", resolved.url(), source, resolved.width(), resolved.height(), resolved.highResolution()));

            log.info("Persisted cover metadata for book {}: {} ({}x{}, highRes={})",
                bookId, resolved.url(), resolved.width(), resolved.height(), resolved.highResolution());

            return new PersistenceResult(true, resolved.url(), resolved.width(), resolved.height(), resolved.highResolution());
        }

        return new PersistenceResult(false, null, null, null, false);
    }
    
    /**
     * Captures the result of a successful S3 cover upload for persistence.
     *
     * @param s3Key S3 object key (required — cannot be null or blank)
     * @param s3CdnUrl Full CDN URL for the uploaded image, or {@code null} when the URL
     *                  must be resolved from the key via {@link CoverUrlResolver}
     * @param width Actual detected width (may be null if dimensions are unknown)
     * @param height Actual detected height (may be null if dimensions are unknown)
     * @param source Original source that provided the image (required)
     */
    public record S3UploadResult(
            String s3Key,
            @Nullable String s3CdnUrl,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Boolean isGrayscale,
            CoverImageSource source) {

        public S3UploadResult {
            if (!StringUtils.hasText(s3Key)) {
                throw new IllegalArgumentException("S3UploadResult requires a non-blank s3Key");
            }
            java.util.Objects.requireNonNull(source, "S3UploadResult requires a non-null source");
        }

        /** Backward-compatible constructor without grayscale parameter. */
        public S3UploadResult(String s3Key,
                              @Nullable String s3CdnUrl,
                              @Nullable Integer width,
                              @Nullable Integer height,
                              CoverImageSource source) {
            this(s3Key, s3CdnUrl, width, height, null, source);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String s3Key;
            private String s3CdnUrl;
            private Integer width;
            private Integer height;
            private Boolean isGrayscale;
            private CoverImageSource source;

            public Builder s3Key(String s3Key) { this.s3Key = s3Key; return this; }
            public Builder s3CdnUrl(String s3CdnUrl) { this.s3CdnUrl = s3CdnUrl; return this; }
            public Builder width(Integer width) { this.width = width; return this; }
            public Builder height(Integer height) { this.height = height; return this; }
            public Builder isGrayscale(Boolean isGrayscale) { this.isGrayscale = isGrayscale; return this; }
            public Builder source(CoverImageSource source) { this.source = source; return this; }

            public S3UploadResult build() {
                return new S3UploadResult(s3Key, s3CdnUrl, width, height, isGrayscale, source);
            }
        }
    }
    
    /**
     * Persists external cover URL with estimated or actual dimensions.
     * Used for non-Google sources or when directly persisting external URLs.
     * 
     * @param bookId Canonical book UUID
     * @param externalUrl External image URL
     * @param source Source identifier
     * @param width Image width (null for unknown)
     * @param height Image height (null for unknown)
     * @return PersistenceResult indicating success
     */
    @Transactional
    public PersistenceResult persistExternalCover(
        UUID bookId,
        String externalUrl,
        String source,
        Integer width,
        Integer height
    ) {
        if (externalUrl == null || externalUrl.isBlank()) {
            log.warn("Cannot persist external cover for book {}: URL is null/blank", bookId);
            return new PersistenceResult(false, null, null, null, false);
        }
        
        String httpsUrl = UrlUtils.validateAndNormalize(externalUrl);
        if (!StringUtils.hasText(httpsUrl)) {
            log.warn("Cannot persist external cover for book {}: URL is invalid ({})", bookId, externalUrl);
            return new PersistenceResult(false, null, null, null, false);
        }

        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(new CoverUrlResolver.ResolveContext(
            httpsUrl,
            httpsUrl,
            width,
            height,
            null
        ));

        try {
            upsertImageLink(new ImageLinkParams(bookId, "canonical", resolved.url(), source,
                resolved.width(), resolved.height(), resolved.highResolution()));

            log.info("Persisted external cover for book {} from {}: {}x{}",
                bookId, source, resolved.width(), resolved.height());

            return new PersistenceResult(true, resolved.url(), resolved.width(), resolved.height(), resolved.highResolution());

        } catch (IllegalArgumentException ex) {
            log.error("Failed to persist external cover for book {}: {}", bookId, ex.getMessage(), ex);
            throw ex;
        } catch (DataAccessException ex) {
            log.error("Failed to persist external cover for book {} due to database error: {}", bookId, ex.getMessage(), ex);
            throw ex;
        }
    }
    
    /**
     * Records a download error for the canonical image link of a book.
     *
     * @param bookId Canonical book UUID
     * @param error Error message or code
     */
    @Transactional
    public void recordDownloadError(UUID bookId, String error) {
        if (bookId == null || !StringUtils.hasText(error)) {
            return;
        }
        try {
            int updated = jdbcTemplate.update(
                "UPDATE book_image_links SET download_error = ?, updated_at = NOW() WHERE book_id = ? AND image_type = 'canonical'",
                error, bookId
            );
            if (updated > 0) {
                log.warn("Recorded download error for book {}: {}", bookId, error);
            } else {
                log.warn("Could not record download error for book {} (canonical row not found): {}", bookId, error);
            }
        } catch (DataAccessException e) {
            log.error("Failed to record download error for book {}: {}", bookId, e.getMessage(), e);
            throw new IllegalStateException("Failed to persist download error for book " + bookId, e);
        }
    }
    
    /**
     * Propagates a non-null grayscale flag to all sibling rows for the same book
     * that have not yet been independently analyzed ({@code is_grayscale IS NULL}).
     *
     * <p>All {@code book_image_links} rows for a given book originate from the same
     * source image at different zoom levels, so grayscale status is inherently shared.
     * Without propagation, unanalyzed external-URL rows retain {@code NULL} which the
     * priority function treats as "color," creating a priority inversion against the
     * explicitly-grayscale S3 row.</p>
     */
    private void propagateGrayscaleToSiblings(UUID bookId, @Nullable Boolean isGrayscale) {
        if (isGrayscale == null) {
            return;
        }
        int updated = jdbcTemplate.update("""
            UPDATE book_image_links
            SET is_grayscale = ?, updated_at = NOW()
            WHERE book_id = ?
              AND is_grayscale IS NULL
            """,
            isGrayscale, bookId
        );
        if (updated > 0) {
            log.info("Propagated is_grayscale={} to {} sibling rows for book {}",
                isGrayscale, updated, bookId);
        }
    }

    /**
     * Internal method to upsert a row in book_image_links with optional S3 path.
     * Handles conflict resolution with ON CONFLICT DO UPDATE.
     */
    private void upsertImageLink(ImageLinkParams params) {
        String normalizedImageType = sanitizeImageType(params.imageType());
        String normalizedUrl = sanitizeUrl(params.url(), params.bookId(), normalizedImageType);
        String normalizedS3Path = sanitizeS3Path(params.s3ImagePath(), params.bookId(), normalizedImageType);

        jdbcTemplate.update("""
            INSERT INTO book_image_links (
                id, book_id, image_type, url, source,
                width, height, is_high_resolution, s3_image_path, is_grayscale,
                created_at, updated_at, s3_uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), CASE WHEN ? THEN NOW() ELSE NULL END)
            ON CONFLICT (book_id, image_type) DO UPDATE SET
                url = EXCLUDED.url,
                source = EXCLUDED.source,
                width = EXCLUDED.width,
                height = EXCLUDED.height,
                is_high_resolution = EXCLUDED.is_high_resolution,
                s3_image_path = COALESCE(EXCLUDED.s3_image_path, book_image_links.s3_image_path),
                is_grayscale = COALESCE(EXCLUDED.is_grayscale, book_image_links.is_grayscale),
                s3_uploaded_at = CASE
                    WHEN EXCLUDED.s3_image_path IS NOT NULL THEN NOW()
                    ELSE book_image_links.s3_uploaded_at
                END,
                updated_at = NOW()
            """,
            IdGenerator.generate(),
            params.bookId(),
            normalizedImageType,
            normalizedUrl,
            params.source(),
            params.width(),
            params.height(),
            params.highRes(),
            normalizedS3Path,
            params.isGrayscale(),
            normalizedS3Path != null
        );
    }

    private String sanitizeImageType(String imageType) {
        if (!StringUtils.hasText(imageType)) {
            throw new IllegalArgumentException("image_type cannot be null or blank");
        }
        String normalizedImageType = imageType.trim();
        if (DISALLOWED_IMAGE_TYPES.contains(normalizedImageType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("image_type is unsupported: " + normalizedImageType);
        }
        return normalizedImageType;
    }

    private String sanitizeUrl(String url, UUID bookId, String imageType) {
        String normalized = UrlUtils.validateAndNormalize(url);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("Invalid image URL for book " + bookId + " type " + imageType);
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains(PLACEHOLDER_FILENAME)) {
            throw new IllegalArgumentException("Placeholder URL is not persistable for book " + bookId + " type " + imageType);
        }
        if (lower.contains("://localhost") || lower.contains("://127.0.0.1") || lower.contains("://0.0.0.0")) {
            throw new IllegalArgumentException("Localhost URL is not persistable for book " + bookId + " type " + imageType);
        }

        return normalized;
    }

    private String sanitizeS3Path(@Nullable String s3Path, UUID bookId, String imageType) {
        if (!StringUtils.hasText(s3Path)) {
            return null;
        }

        String normalizedPath = s3Path.trim();
        if (normalizedPath.toLowerCase(Locale.ROOT).contains(PLACEHOLDER_FILENAME)) {
            throw new IllegalArgumentException(
                "Placeholder S3 path is not persistable for book " + bookId + " type " + imageType);
        }
        return normalizedPath;
    }

    /**
     * Record for upsertImageLink parameters to avoid excessive parameter count.
     */
    private record ImageLinkParams(
        UUID bookId,
        String imageType,
        String url,
        String source,
        Integer width,
        Integer height,
        Boolean highRes,
        String s3ImagePath,
        @Nullable Boolean isGrayscale
    ) {
        ImageLinkParams {
            java.util.Objects.requireNonNull(bookId, "bookId cannot be null");
            java.util.Objects.requireNonNull(imageType, "imageType cannot be null");
        }

        ImageLinkParams(UUID bookId, String imageType, String url, String source,
                        Integer width, Integer height, Boolean highRes) {
            this(bookId, imageType, url, source, width, height, highRes, null, null);
        }

        ImageLinkParams(UUID bookId, String imageType, String url, String source,
                        Integer width, Integer height, Boolean highRes, String s3ImagePath) {
            this(bookId, imageType, url, source, width, height, highRes, s3ImagePath, null);
        }
    }
}
