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
     * @param context ranking context containing all cover attributes
     * @return quality tier between 0 and 5 inclusive
     */
    public static int rank(RankingContext context) {
        boolean hasS3 = isRenderable(context.s3Path());
        boolean hasExternal = isRenderable(context.externalUrl());
        if (!hasS3 && !hasExternal) {
            return 0;
        }
        String preferred = hasS3 ? context.s3Path() : context.externalUrl();
        return rankFromUrl(new UrlRankingContext(
            preferred,
            context.width(),
            context.height(),
            context.highResolution(),
            context.grayscale()
        ));
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
        return rank(new RankingContext(s3Path, externalUrl, width, height, highResolution, grayscale));
    }

    /**
     * Backward-compatible overload that treats grayscale as unknown (color).
     */
    public static int rank(String s3Path,
                           String externalUrl,
                           Integer width,
                           Integer height,
                           Boolean highResolution) {
        return rank(new RankingContext(s3Path, externalUrl, width, height, highResolution, null));
    }

    /**
     * Scores a cover when only a single resolved URL is available,
     * with grayscale awareness.
     *
     * @param context url ranking context
     * @return quality tier between 0 and 5 inclusive
     */
    public static int rankFromUrl(UrlRankingContext context) {
        String url = context.url();
        if (!isRenderable(url)) {
            return 0;
        }

        if (!CoverUrlValidator.isLikelyCoverImage(url)) {
            return 0;
        }

        if (hasKnownBadAspectRatio(context.width(), context.height())) {
            return 0;
        }

        if (Boolean.TRUE.equals(context.grayscale())) {
            return 1;
        }

        boolean fromS3 = url != null && !(url.startsWith("http://") || url.startsWith("https://"));
        boolean hasCdn = fromS3 || CoverUrlResolver.isCdnUrl(url);
        boolean resolvedHighRes = Boolean.TRUE.equals(context.highResolution())
            || ImageDimensionUtils.isHighResolution(context.width(), context.height());
        boolean meetsDisplay = ImageDimensionUtils.meetsSearchDisplayThreshold(context.width(), context.height());

        if (hasCdn && resolvedHighRes) return 5;
        if (resolvedHighRes)           return 4;
        if (hasCdn || meetsDisplay)    return 3;
        return 2;
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
        return rankFromUrl(new UrlRankingContext(url, width, height, highResolution, grayscale));
    }

    /**
     * Backward-compatible overload that treats grayscale as unknown (color).
     */
    public static int rankFromUrl(String url,
                                  Integer width,
                                  Integer height,
                                  Boolean highResolution) {
        return rankFromUrl(new UrlRankingContext(url, width, height, highResolution, null));
    }

    public record RankingContext(
        String s3Path,
        String externalUrl,
        Integer width,
        Integer height,
        Boolean highResolution,
        Boolean grayscale
    ) {}

    public record UrlRankingContext(
        String url,
        Integer width,
        Integer height,
        Boolean highResolution,
        Boolean grayscale
    ) {}

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
