package net.findmybook.util.cover;

import org.springframework.util.StringUtils;
import java.util.Locale;

/**
 * Centralises cover quality scoring so that all ranking logic
 * (search results, homepage carousels, API fallbacks) uses the
 * exact same definition of what constitutes a “good” cover.
 *
 * Score tiers (higher is better):
 * 4 — CDN/S3 backed and high-resolution
 * 3 — High-resolution without CDN/S3 backing
 * 2 — CDN/S3 backed or meets display thresholds
 * 1 — Any remaining non-placeholder cover
 * 0 — No meaningful cover information
 */
public final class CoverQuality {

    private CoverQuality() {
    }

    /**
     * Scores a cover using separate S3 key and external URL candidates.
     */
    public static int rank(String s3Path,
                           String externalUrl,
                           Integer width,
                           Integer height,
                           Boolean highResolution) {
        boolean hasS3 = isRenderable(s3Path);
        boolean hasExternal = isRenderable(externalUrl);
        if (!hasS3 && !hasExternal) {
            return 0;
        }
        String preferred = hasS3 ? s3Path : externalUrl;
        return rankFromUrl(preferred, width, height, highResolution);
    }

    /**
     * Scores a cover when only a single resolved URL is available.
     */
    public static int rankFromUrl(String url,
                                  Integer width,
                                  Integer height,
                                  Boolean highResolution) {
        if (!isRenderable(url)) {
            return 0;
        }
        boolean fromS3 = url != null && !(url.startsWith("http://") || url.startsWith("https://"));
        boolean hasCdn = fromS3 || CoverUrlResolver.isCdnUrl(url);
        boolean resolvedHighRes = Boolean.TRUE.equals(highResolution)
            || ImageDimensionUtils.isHighResolution(width, height);
        boolean meetsDisplay = ImageDimensionUtils.meetsSearchDisplayThreshold(width, height);

        if (hasCdn && resolvedHighRes) {
            return 4;
        }
        if (resolvedHighRes) {
            return 3;
        }
        if (hasCdn || meetsDisplay) {
            return 2;
        }
        return 1;
    }

    private static boolean isRenderable(String url) {
        String normalized = StringUtils.hasText(url) ? url.toLowerCase(Locale.ROOT) : "";
        return StringUtils.hasText(url)
            && !CoverUrlResolver.isNullEquivalent(url)
            && !normalized.contains("placeholder-book-cover.svg");
    }
}
