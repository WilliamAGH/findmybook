package net.findmybook.util.cover;

import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.ValidationUtils;

/**
 * Centralises logic for turning S3 keys or raw cover URLs into CDN-ready URLs with sensible fallbacks,
 * while also providing best-effort metadata about dimensions and quality.
 */
public final class CoverUrlResolver {

    private static final java.util.concurrent.atomic.AtomicReference<String> CDN_BASE_OVERRIDE = new java.util.concurrent.atomic.AtomicReference<>();

    private CoverUrlResolver() {
    }

    public static void setCdnBase(String base) {
        if (ValidationUtils.hasText(base)) {
            CDN_BASE_OVERRIDE.set(normalizeBase(base));
        } else {
            CDN_BASE_OVERRIDE.set(null);
        }
    }

    private static String currentCdnBase() {
        String override = CDN_BASE_OVERRIDE.get();
        if (ValidationUtils.hasText(override)) {
            return override;
        }
        return resolveCdnBaseFromEnvironment();
    }

    public static ResolvedCover resolve(String primary) {
        return resolve(primary, null, null, null, null);
    }

    public static ResolvedCover resolve(String primary, String fallbackExternal) {
        return resolve(primary, fallbackExternal, null, null, null);
    }

    public static ResolvedCover resolve(String primary,
                                        String fallbackExternal,
                                        Integer width,
                                        Integer height,
                                        Boolean highResolution) {
        UrlResolution urlResolution = resolveUrl(primary, fallbackExternal);
        Dimensions resolvedDimensions = resolveDimensions(urlResolution.url(), width, height);
        boolean resolvedHighRes = Boolean.TRUE.equals(highResolution)
            || (!resolvedDimensions.defaulted()
                && ImageDimensionUtils.isHighResolution(resolvedDimensions.width(), resolvedDimensions.height()));

        return new ResolvedCover(
            urlResolution.url(),
            urlResolution.s3Key(),
            urlResolution.fromS3(),
            resolvedDimensions.width(),
            resolvedDimensions.height(),
            resolvedHighRes
        );
    }

    public static boolean isCdnUrl(String url) {
        String cdnBase = currentCdnBase();
        return ValidationUtils.hasText(url) && ValidationUtils.hasText(cdnBase) && url.startsWith(cdnBase);
    }

    private static UrlResolution resolveUrl(String primary, String fallbackExternal) {
        String candidate = ValidationUtils.hasText(primary) ? primary.trim() : null;

        if (ValidationUtils.hasText(candidate)) {
            if (isHttp(candidate) || isDataUri(candidate)) {
                return new UrlResolution(candidate, null, false);
            }

            // ValidationUtils.hasText() already checks for null, but explicit check satisfies static analysis
            String cdnBase = currentCdnBase();
            if (candidate != null && ValidationUtils.hasText(cdnBase)) {
                String key = candidate.startsWith("/") ? candidate.substring(1) : candidate;
                if (ValidationUtils.hasText(key)) {
                    return new UrlResolution(cdnBase + key, key, true);
                }
            }
        }

        if (ValidationUtils.hasText(fallbackExternal)) {
            String external = fallbackExternal.trim();
            if (isHttp(external) || isDataUri(external)) {
                return new UrlResolution(external, null, false);
            }
        }

        return new UrlResolution(ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH, null, false);
    }

    private static Dimensions resolveDimensions(String url, Integer width, Integer height) {
        if (width != null && width > 0 && height != null && height > 0) {
            return new Dimensions(width, height, false);
        }
        return estimateDimensionsFromUrl(url);
    }

    private static Dimensions estimateDimensionsFromUrl(String url) {
        int defaultWidth = ImageDimensionUtils.DEFAULT_DIMENSION;
        int defaultHeight = defaultWidth * 3 / 2;

        if (!ValidationUtils.hasText(url)) {
            return new Dimensions(defaultWidth, defaultHeight, true);
        }

        // Google Books zoom parameter
        Integer zoom = extractIntQueryParam(url, "zoom");
        if (zoom != null) {
            return switch (zoom) {
                case 4 -> new Dimensions(640, 960, false);
                case 3 -> new Dimensions(512, 768, false);
                case 2 -> new Dimensions(320, 480, false);
                case 1 -> new Dimensions(200, 300, false);
                default -> new Dimensions(defaultWidth, defaultHeight, true);
            };
        }

        // Explicit width/height query params
        Integer w = extractIntQueryParam(url, "w");
        Integer h = extractIntQueryParam(url, "h");
        if (w != null && h != null) {
            return new Dimensions(
                Math.max(w, ImageDimensionUtils.MIN_VALID_DIMENSION),
                Math.max(h, ImageDimensionUtils.MIN_VALID_DIMENSION),
                false
            );
        }

        // Open Library suffix
        if (url.contains("covers.openlibrary.org")) {
            if (url.contains("-L.jpg") || url.endsWith("-L")) {
                return new Dimensions(600, 900, false);
            } else if (url.contains("-M.jpg") || url.endsWith("-M")) {
                return new Dimensions(320, 480, false);
            } else if (url.contains("-S.jpg") || url.endsWith("-S")) {
                return new Dimensions(120, 180, false);
            }
        }

        return new Dimensions(defaultWidth, defaultHeight, true);
    }

    private static Integer extractIntQueryParam(String url, String param) {
        int idx = url.indexOf(param + "=");
        if (idx == -1) {
            return null;
        }
        int start = idx + param.length() + 1;
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(url.substring(start, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isHttp(String value) {
        if (!ValidationUtils.hasText(value)) {
            return false;
        }
        return value.regionMatches(true, 0, "http://", 0, 7)
            || value.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean isDataUri(String value) {
        return value.startsWith("data:image");
    }

    private static String resolveCdnBaseFromEnvironment() {
        String configured = System.getProperty("s3.cdn-url");
        if (!ValidationUtils.hasText(configured)) {
            configured = System.getProperty("S3_CDN_URL");
        }
        if (!ValidationUtils.hasText(configured)) {
            configured = System.getenv("S3_CDN_URL");
        }
        if (!ValidationUtils.hasText(configured)) {
            return "";
        }
        String trimmed = configured.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static String normalizeBase(String value) {
        if (!ValidationUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private record UrlResolution(String url, String s3Key, boolean fromS3) {}
    private record Dimensions(int width, int height, boolean defaulted) {}

    public record ResolvedCover(String url,
                                String s3Key,
                                boolean fromS3,
                                int width,
                                int height,
                                boolean highResolution) {}
}
