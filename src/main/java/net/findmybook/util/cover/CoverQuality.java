package net.findmybook.util.cover;

import org.springframework.util.StringUtils;
import java.util.Locale;

/**
 * Centralises cover quality scoring so that all ranking logic
 * (search results, homepage carousels, API fallbacks) uses the
 * exact same definition of what constitutes a "good" cover.
 *
 * <p>Score tiers (higher is better):</p>
 * <ul>
 *   <li><b>5</b> — S3/CDN + high-res + color</li>
 *   <li><b>4</b> — High-res + color</li>
 *   <li><b>3</b> — S3/CDN or meets display threshold + color</li>
 *   <li><b>2</b> — Any color cover</li>
 *   <li><b>1</b> — Grayscale cover (any quality)</li>
 *   <li><b>0</b> — No cover</li>
 * </ul>
 */
public final class CoverQuality {

    private CoverQuality() {
    }

    /**
     * Scores a cover using separate S3 key and external URL candidates,
     * with grayscale awareness.
     *
     * @param s3Path         S3 object key (nullable)
     * @param externalUrl    external cover URL (nullable)
     * @param width          image width in pixels (nullable)
     * @param height         image height in pixels (nullable)
     * @param highResolution whether the cover is flagged as high-res (nullable)
     * @param grayscale      whether the cover is grayscale/B&amp;W (nullable — null treated as color)
     * @return quality tier between 0 and 5 inclusive
     */
    public static int rank(String s3Path,
                           String externalUrl,
                           Integer width,
                           Integer height,
                           Boolean highResolution,
                           Boolean grayscale) {
        boolean hasS3 = isRenderable(s3Path);
        boolean hasExternal = isRenderable(externalUrl);
        if (!hasS3 && !hasExternal) {
            return 0;
        }
        String preferred = hasS3 ? s3Path : externalUrl;
        return rankFromUrl(preferred, width, height, highResolution, grayscale);
    }

    /**
     * Backward-compatible overload that treats grayscale as unknown (color).
     */
    public static int rank(String s3Path,
                           String externalUrl,
                           Integer width,
                           Integer height,
                           Boolean highResolution) {
        return rank(s3Path, externalUrl, width, height, highResolution, null);
    }

    /**
     * Scores a cover when only a single resolved URL is available,
     * with grayscale awareness.
     *
     * @param url            the resolved cover URL or S3 key
     * @param width          image width in pixels (nullable)
     * @param height         image height in pixels (nullable)
     * @param highResolution whether the cover is flagged as high-res (nullable)
     * @param grayscale      whether the cover is grayscale/B&amp;W (nullable — null treated as color)
     * @return quality tier between 0 and 5 inclusive
     */
    public static int rankFromUrl(String url,
                                  Integer width,
                                  Integer height,
                                  Boolean highResolution,
                                  Boolean grayscale) {
        if (!isRenderable(url)) {
            return 0;
        }

        if (!CoverUrlValidator.isLikelyCoverImage(url)) {
            return 0;
        }

        if (hasKnownBadAspectRatio(width, height)) {
            return 0;
        }

        if (Boolean.TRUE.equals(grayscale)) {
            return 1;
        }

        boolean fromS3 = url != null && !(url.startsWith("http://") || url.startsWith("https://"));
        boolean hasCdn = fromS3 || CoverUrlResolver.isCdnUrl(url);
        boolean resolvedHighRes = Boolean.TRUE.equals(highResolution)
            || ImageDimensionUtils.isHighResolution(width, height);
        boolean meetsDisplay = ImageDimensionUtils.meetsSearchDisplayThreshold(width, height);

        if (hasCdn && resolvedHighRes) return 5;
        if (resolvedHighRes)           return 4;
        if (hasCdn || meetsDisplay)    return 3;
        return 2;
    }

    /**
     * Backward-compatible overload that treats grayscale as unknown (color).
     */
    public static int rankFromUrl(String url,
                                  Integer width,
                                  Integer height,
                                  Boolean highResolution) {
        return rankFromUrl(url, width, height, highResolution, null);
    }

    /**
     * Returns {@code true} when dimensions are known and the aspect ratio
     * falls outside the acceptable range for book covers.
     * Unknown (null) dimensions are treated as acceptable to avoid rejecting
     * covers where the database simply lacks dimension metadata.
     */
    private static boolean hasKnownBadAspectRatio(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return false;
        }
        return !ImageDimensionUtils.hasValidAspectRatio(width, height);
    }

    private static boolean isRenderable(String url) {
        String normalized = StringUtils.hasText(url) ? url.toLowerCase(Locale.ROOT) : "";
        return StringUtils.hasText(url)
            && !CoverUrlResolver.isNullEquivalent(url)
            && !normalized.contains("placeholder-book-cover.svg");
    }
}
