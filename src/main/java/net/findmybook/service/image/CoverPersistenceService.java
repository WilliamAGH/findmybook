package net.findmybook.service.image;

import net.findmybook.model.image.CoverImageSource;
import net.findmybook.util.IdGenerator;
import net.findmybook.util.UrlUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import net.findmybook.util.cover.CoverUrlResolver;
import net.findmybook.util.cover.ImageDimensionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
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
@Slf4j
public class CoverPersistenceService {
    
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
            String httpsUrl = UrlUtils.normalizeToHttps(url);
            
            // Estimate dimensions based on Google's image type
            ImageDimensionUtils.DimensionEstimate estimate = 
                ImageDimensionUtils.estimateFromGoogleType(imageType);
            
            try {
                // Upsert book_image_links row
                upsertImageLink(bookId, imageType, httpsUrl, source, 
                    estimate.width(), estimate.height(), estimate.highRes());
                
                // Track best quality image for canonical cover
                int priority = getImageTypePriority(imageType);
                if (priority < bestPriority) {
                    bestPriority = priority;
                    canonicalCoverUrl = httpsUrl;
                    canonicalWidth = estimate.width();
                    canonicalHeight = estimate.height();
                    canonicalHighRes = estimate.highRes();
                }
                
            } catch (DataAccessException | IllegalArgumentException ex) {
                log.warn("Failed to persist image link for book {} type {}: {}", 
                    bookId, imageType, ex.getMessage());
            }
        }
        
        // Canonical cover now represented via highest-priority book_image_links row
        if (canonicalCoverUrl != null) {
            CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
                canonicalCoverUrl,
                canonicalCoverUrl,
                canonicalWidth,
                canonicalHeight,
                canonicalHighRes
            );

            upsertImageLink(bookId, "canonical", resolved.url(), source,
                resolved.width(), resolved.height(), resolved.highResolution());

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
            CoverImageSource source) {

        public S3UploadResult {
            if (!StringUtils.hasText(s3Key)) {
                throw new IllegalArgumentException("S3UploadResult requires a non-blank s3Key");
            }
            java.util.Objects.requireNonNull(source, "S3UploadResult requires a non-null source");
        }
    }

    /**
     * Updates cover metadata after successful S3 upload with actual dimensions.
     *
     * @param bookId Canonical book UUID
     * @param upload S3 upload result containing key, URL, dimensions, and source
     * @return PersistenceResult indicating success
     */
    @Transactional
    public PersistenceResult updateAfterS3Upload(UUID bookId, S3UploadResult upload) {
        String s3Key = upload.s3Key();
        String s3CdnUrl = upload.s3CdnUrl();
        Integer width = upload.width();
        Integer height = upload.height();
        CoverImageSource source = upload.source();

        boolean highRes = ImageDimensionUtils.isHighResolution(width, height);
        String canonicalUrl = s3CdnUrl;
        if (!StringUtils.hasText(canonicalUrl)) {
            CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(s3Key, null, width, height, highRes);
            canonicalUrl = resolved.url();
            width = resolved.width();
            height = resolved.height();
            highRes = resolved.highResolution();
        }

        if (!StringUtils.hasText(canonicalUrl)) {
            log.warn("Skipping S3 persistence for book {} because CDN URL resolved empty for key {}", bookId, s3Key);
            return new PersistenceResult(false, null, width, height, highRes);
        }

        try {
            // Upsert canonical S3 row as authoritative cover
            upsertImageLink(bookId, "canonical", canonicalUrl, source.name(), width, height, highRes, s3Key);
            
            log.info("Updated cover metadata for book {} after S3 upload: {} ({}x{}, highRes={})",
                bookId, s3Key, width, height, highRes);
            
            return new PersistenceResult(true, canonicalUrl, width, height, highRes);
            
        } catch (DataAccessException | IllegalArgumentException ex) {
            log.error("Failed to update cover metadata after S3 upload for book {}: {}", 
                bookId, ex.getMessage(), ex);
            return new PersistenceResult(false, canonicalUrl, width, height, highRes);
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
        
        String httpsUrl = UrlUtils.normalizeToHttps(externalUrl);
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            httpsUrl,
            httpsUrl,
            width,
            height,
            null
        );

        try {
            upsertImageLink(bookId, "canonical", resolved.url(), source,
                resolved.width(), resolved.height(), resolved.highResolution());
            
            log.info("Persisted external cover for book {} from {}: {}x{}", 
                bookId, source, resolved.width(), resolved.height());
            
            return new PersistenceResult(true, resolved.url(), resolved.width(), resolved.height(), resolved.highResolution());
            
        } catch (DataAccessException | IllegalArgumentException ex) {
            log.error("Failed to persist external cover for book {}: {}", bookId, ex.getMessage(), ex);
            return new PersistenceResult(false, resolved.url(), resolved.width(), resolved.height(), resolved.highResolution());
        }
    }
    
    /**
     * Internal method to upsert a row in book_image_links.
     * Handles conflict resolution with ON CONFLICT DO UPDATE.
     */
    private void upsertImageLink(
        UUID bookId,
        String imageType,
        String url,
        String source,
        Integer width,
        Integer height,
        Boolean highRes
    ) {
        upsertImageLink(bookId, imageType, url, source, width, height, highRes, null);
    }

    /**
     * Internal method to upsert a row in book_image_links with optional S3 path.
     * Handles conflict resolution with ON CONFLICT DO UPDATE.
     */
    private void upsertImageLink(
        UUID bookId,
        String imageType,
        String url,
        String source,
        Integer width,
        Integer height,
        Boolean highRes,
        String s3ImagePath
    ) {
        jdbcTemplate.update("""
            INSERT INTO book_image_links (
                id, book_id, image_type, url, source,
                width, height, is_high_resolution, s3_image_path, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (book_id, image_type) DO UPDATE SET
                url = EXCLUDED.url,
                source = EXCLUDED.source,
                width = EXCLUDED.width,
                height = EXCLUDED.height,
                is_high_resolution = EXCLUDED.is_high_resolution,
                s3_image_path = COALESCE(EXCLUDED.s3_image_path, book_image_links.s3_image_path)
            """,
            IdGenerator.generate(),
            bookId,
            imageType,
            url,
            source,
            width,
            height,
            highRes,
            s3ImagePath
        );
    }
    
    /**
     * Returns priority ranking for Google Books image types (lower = better quality).
     * 
     * @deprecated Use {@link net.findmybook.util.cover.ImageDimensionUtils#getTypePriority(String)} instead.
     * This method duplicates image type ranking logic that is now centralized in ImageDimensionUtils.
     * Will be removed in version 1.0.0.
     * 
     * <p><b>Migration Example:</b></p>
     * <pre>{@code
     * // Old:
     * int priority = getImageTypePriority(imageType);
     * 
     * // New:
     * int priority = ImageDimensionUtils.getTypePriority(imageType);
     * }</pre>
     */
    @Deprecated(since = "0.9.0", forRemoval = true)
    private int getImageTypePriority(String imageType) {
        return switch (imageType.toLowerCase()) {
            case "extralarge" -> 1;
            case "large" -> 2;
            case "medium" -> 3;
            case "small" -> 4;
            case "thumbnail" -> 5;
            case "smallthumbnail" -> 6;
            default -> 7;
        };
    }
}
