package com.williamcallahan.book_recommendation_engine.util.cover;

import com.williamcallahan.book_recommendation_engine.util.ApplicationConstants;
import com.williamcallahan.book_recommendation_engine.util.ValidationUtils;

/**
 * Centralises logic for turning S3 keys or raw cover URLs into CDN-ready URLs with sensible fallbacks,
 * while also providing best-effort metadata about dimensions and quality.
 */
public final class CoverUrlResolver {

    private static final String CDN_BASE = resolveCdnBase();

    private CoverUrlResolver() {
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
            || ImageDimensionUtils.isHighResolution(resolvedDimensions.width(), resolvedDimensions.height());

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
        return ValidationUtils.hasText(url) && ValidationUtils.hasText(CDN_BASE) && url.startsWith(CDN_BASE);
    }

    private static UrlResolution resolveUrl(String primary, String fallbackExternal) {
        String candidate = ValidationUtils.hasText(primary) ? primary.trim() : null;

        if (ValidationUtils.hasText(candidate)) {
            if (isHttp(candidate) || isDataUri(candidate)) {
                return new UrlResolution(candidate, null, false);
            }

            // ValidationUtils.hasText() already checks for null, but explicit check satisfies static analysis
            if (candidate != null && ValidationUtils.hasText(CDN_BASE)) {
                String key = candidate.startsWith("/") ? candidate.substring(1) : candidate;
                if (ValidationUtils.hasText(key)) {
                    return new UrlResolution(CDN_BASE + key, key, true);
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
            return new Dimensions(width, height);
        }
        return estimateDimensionsFromUrl(url);
    }

    private static Dimensions estimateDimensionsFromUrl(String url) {
        int defaultWidth = ImageDimensionUtils.DEFAULT_DIMENSION;
        int defaultHeight = defaultWidth * 3 / 2;

        if (!ValidationUtils.hasText(url)) {
            return new Dimensions(defaultWidth, defaultHeight);
        }

        // Google Books zoom parameter
        Integer zoom = extractIntQueryParam(url, "zoom");
        if (zoom != null) {
            return switch (zoom) {
                case 4 -> new Dimensions(640, 960);
                case 3 -> new Dimensions(512, 768);
                case 2 -> new Dimensions(320, 480);
                case 1 -> new Dimensions(200, 300);
                default -> new Dimensions(defaultWidth, defaultHeight);
            };
        }

        // Explicit width/height query params
        Integer w = extractIntQueryParam(url, "w");
        Integer h = extractIntQueryParam(url, "h");
        if (w != null && h != null) {
            return new Dimensions(Math.max(w, ImageDimensionUtils.MIN_VALID_DIMENSION), Math.max(h, ImageDimensionUtils.MIN_VALID_DIMENSION));
        }

        // Open Library suffix
        if (url.contains("covers.openlibrary.org")) {
            if (url.contains("-L.jpg") || url.endsWith("-L")) {
                return new Dimensions(600, 900);
            } else if (url.contains("-M.jpg") || url.endsWith("-M")) {
                return new Dimensions(320, 480);
            } else if (url.contains("-S.jpg") || url.endsWith("-S")) {
                return new Dimensions(120, 180);
            }
        }

        return new Dimensions(defaultWidth, defaultHeight);
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
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static boolean isDataUri(String value) {
        return value.startsWith("data:image");
    }

    private static String resolveCdnBase() {
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

    private record UrlResolution(String url, String s3Key, boolean fromS3) {}
    private record Dimensions(int width, int height) {}

    public record ResolvedCover(String url,
                                String s3Key,
                                boolean fromS3,
                                int width,
                                int height,
                                boolean highResolution) {}
}
