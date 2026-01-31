package net.findmybook.util.cover;

import net.findmybook.model.image.ImageDetails;

/**
 * Single Source of Truth for image dimension validation, estimation, and normalization.
 * 
 * Consolidates logic from:
 * - CoverImageService.estimateDimensions()
 * - Legacy cache helper normalizeImageDimension()
 * - Legacy cache helper isValidImageDetails() dimension checks
 * - Legacy cover fetching heuristics for dimension thresholds
 * 
 * @author William Callahan
 */
public final class ImageDimensionUtils {
    
    // Dimension validation thresholds
    public static final int MIN_VALID_DIMENSION = 2; // Absolute minimum for valid image
    public static final int MIN_ACCEPTABLE_NON_GOOGLE = 200; // Minimum for acceptable quality (non-Google sources)
    public static final int MIN_ACCEPTABLE_CACHED = 150; // Minimum for cached images to be considered
    public static final int MIN_SEARCH_RESULT_HEIGHT = 280; // Minimum height for displaying covers in search results
    public static final int MIN_SEARCH_RESULT_WIDTH = 180; // Minimum width for displaying covers in search results
    
    // Aspect ratio validation (book covers are typically portrait, taller than wide)
    public static final double MIN_ASPECT_RATIO = 1.2; // height / width (e.g., 360x300 = 1.2)
    public static final double MAX_ASPECT_RATIO = 2.0; // height / width (e.g., 600x300 = 2.0)
    
    // High-resolution threshold (total pixels)
    public static final int HIGH_RES_PIXEL_THRESHOLD = 320_000; // ~640x500 or 512x625
    
    // Default/fallback dimension for unknown images
    public static final int DEFAULT_DIMENSION = 512;
    
    private ImageDimensionUtils() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Returns priority ranking for Google Books image types.
     * Lower numbers indicate better quality.
     * 
     * @param imageType The image type string (case-insensitive)
     * @return Priority ranking (1 = best quality, 7 = worst/unknown)
     * 
     * @example
     * <pre>{@code
     * int priority = ImageDimensionUtils.getTypePriority("extraLarge");
     * // Returns: 1 (highest quality)
     * 
     * int priority = ImageDimensionUtils.getTypePriority("thumbnail");
     * // Returns: 5 (lower quality)
     * }</pre>
     */
    public static int getTypePriority(String imageType) {
        if (imageType == null) {
            return 7; // Lowest priority for null
        }
        
        return switch (imageType.toLowerCase()) {
            case "canonical" -> 0;
            case "extralarge" -> 1;
            case "large" -> 2;
            case "medium" -> 3;
            case "small" -> 4;
            case "thumbnail" -> 5;
            case "smallthumbnail" -> 6;
            default -> 7;
        };
    }
    
    /**
     * Estimates dimensions based on Google Books image type.
     * 
     * @param imageType The image type from Google Books (extraLarge, large, medium, etc.)
     * @return Estimated dimensions record
     */
    public static DimensionEstimate estimateFromGoogleType(String imageType) {
        if (imageType == null) {
            return new DimensionEstimate(DEFAULT_DIMENSION, DEFAULT_DIMENSION * 3 / 2, false);
        }
        
        return switch (imageType.toLowerCase()) {
            case "extralarge" -> new DimensionEstimate(800, 1200, true);
            case "large" -> new DimensionEstimate(600, 900, true);
            case "medium" -> new DimensionEstimate(400, 600, false);
            case "small" -> new DimensionEstimate(300, 450, false);
            case "thumbnail" -> new DimensionEstimate(128, 192, false);
            case "smallthumbnail" -> new DimensionEstimate(64, 96, false);
            default -> new DimensionEstimate(DEFAULT_DIMENSION, DEFAULT_DIMENSION * 3 / 2, false);
        };
    }
    
    /**
     * Normalizes a dimension value, replacing invalid/missing values with default.
     * 
     * @param dimension The dimension value to normalize (may be null or <= 1)
     * @return Normalized dimension (returns DEFAULT_DIMENSION if input is invalid)
     */
    public static int normalize(Integer dimension) {
        if (dimension == null || dimension <= MIN_VALID_DIMENSION) {
            return DEFAULT_DIMENSION;
        }
        return dimension;
    }
    
    /**
     * Checks if dimensions represent a high-resolution image.
     * 
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return true if total pixels meet high-resolution threshold
     */
    public static boolean isHighResolution(Integer width, Integer height) {
        if (width == null || height == null) {
            return false;
        }

        long totalPixels = (long) width * height;
        return totalPixels >= HIGH_RES_PIXEL_THRESHOLD;
    }

    /**
     * Computes the total pixel count for the provided dimensions.
     *
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return Total pixel count or 0 if dimensions are missing
     */
    public static long totalPixels(Integer width, Integer height) {
        if (width == null || height == null) {
            return 0L;
        }
        return (long) width * height;
    }
    
    /**
     * Validates that dimensions meet minimum acceptable quality thresholds.
     * 
     * @param width Image width
     * @param height Image height
     * @param minThreshold Minimum acceptable dimension (width OR height must meet this)
     * @return true if dimensions meet threshold
     */
    public static boolean meetsThreshold(Integer width, Integer height, int minThreshold) {
        if (width == null || height == null) {
            return false;
        }
        
        return width >= minThreshold && height >= minThreshold;
    }
    
    public static boolean meetsSearchDisplayThreshold(Integer width, Integer height) {
        if (width == null || height == null) {
            return false;
        }
        return width >= MIN_SEARCH_RESULT_WIDTH && height >= MIN_SEARCH_RESULT_HEIGHT;
    }
    
    /**
     * Checks if dimensions are valid (not null and greater than minimum).
     * 
     * @param width Image width
     * @param height Image height
     * @return true if both dimensions are valid
     */
    public static boolean areValid(Integer width, Integer height) {
        return width != null && width > MIN_VALID_DIMENSION
            && height != null && height > MIN_VALID_DIMENSION;
    }
    
    /**
     * Validates ImageDetails has acceptable dimensions for use.
     * 
     * @param imageDetails The image details to validate
     * @return true if imageDetails has valid, acceptable dimensions
     */
    public static boolean hasAcceptableDimensions(ImageDetails imageDetails) {
        if (imageDetails == null) {
            return false;
        }

        return areValid(imageDetails.getWidth(), imageDetails.getHeight());
    }

    /**
     * Determines whether an image height falls below the minimum threshold for search results.
     *
     * @param height Image height in pixels
     * @return true if the height is known and below the minimum display threshold
     */
    public static boolean isBelowSearchMinimum(Integer height) {
        return height != null && height < MIN_SEARCH_RESULT_HEIGHT;
    }
    
    /**
     * Validates that an image has an acceptable aspect ratio for book covers.
     * Book covers should be portrait-oriented (taller than wide).
     * 
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return true if aspect ratio is within acceptable range for book covers
     */
    public static boolean hasValidAspectRatio(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return false;
        }
        
        double aspectRatio = (double) height / width;
        return aspectRatio >= MIN_ASPECT_RATIO && aspectRatio <= MAX_ASPECT_RATIO;
    }
    
    /**
     * Comprehensive validation for display-ready book covers.
     * Checks minimum dimensions AND aspect ratio.
     * 
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return true if image meets all display requirements (dimensions + aspect ratio)
     */
    public static boolean meetsDisplayRequirements(Integer width, Integer height) {
        return meetsSearchDisplayThreshold(width, height) && hasValidAspectRatio(width, height);
    }
    
    /**
     * Comprehensive validation for display-ready covers including URL quality checks.
     * Validates dimensions, aspect ratio, AND URL patterns to exclude title pages.
     * 
     * <p>This is the most complete validation combining:</p>
     * <ul>
     *   <li>Minimum dimensions (180x280)</li>
     *   <li>Valid aspect ratio (1.2-2.0)</li>
     *   <li>URL pattern validation (no title pages, requires edge=curl for Google Books)</li>
     * </ul>
     * 
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param url Image URL to validate
     * @return true if image meets all quality requirements for display
     * 
     * @see CoverUrlValidator#isLikelyCoverImage(String)
     * @see #meetsDisplayRequirements(Integer, Integer)
     */
    public static boolean meetsDisplayQualityRequirements(Integer width, Integer height, String url) {
        return meetsDisplayRequirements(width, height) 
            && CoverUrlValidator.isLikelyCoverImage(url);
    }
    
    /**
     * Record for dimension estimates with high-resolution flag.
     * 
     * @param width Estimated width in pixels
     * @param height Estimated height in pixels
     * @param highRes Whether dimensions qualify as high-resolution
     */
    public record DimensionEstimate(int width, int height, boolean highRes) {}
}
