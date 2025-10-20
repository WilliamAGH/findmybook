package com.williamcallahan.book_recommendation_engine.util;

import org.springframework.lang.Nullable;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.regex.Pattern;

/**
 * Google Books image URL enhancement using Spring's UriComponentsBuilder.
 * <p>
 * Uses Spring Boot's built-in URI manipulation instead of custom regex.
 * Leverages Java 21 pattern matching for clean, zero-boilerplate code.
 * 
 * @since 1.0.0
 */
public final class ImageUrlEnhancer {
    
    // Pattern for fife parameter removal (compiled once for performance)
    private static final Pattern FIFE_PATTERN = Pattern.compile("[?&]fife=w\\d+");
    
    private ImageUrlEnhancer() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Enhances Google Books cover URL for optimal quality.
     * <p>
     * Uses Spring's UriComponentsBuilder (built-in) instead of manual string manipulation.
     * 
     * Operations:
     * 1. Normalizes to HTTPS (security)
     * 2. Removes fife parameter (causes quality degradation)
     * 3. Optimizes zoom parameter based on size
     * 
     * @param url Original URL from Google Books API
     * @param quality Size qualifier: "small", "medium", "large", "extraLarge", "thumbnail"
     * @return Enhanced URL with optimized parameters
     * 
     * @example
     * <pre>
     * enhanceGoogleImageUrl("http://books.google.com/image?id=123&zoom=5", "large")
     *   → "https://books.google.com/image?id=123&zoom=2"
     * </pre>
     */
    @Nullable
    public static String enhanceGoogleImageUrl(@Nullable String url, @Nullable String quality) {
        // Modern Java: Clean null/blank checks
        if (url == null || url.isBlank()) {
            return null;
        }
        
        return enhanceUrl(url, quality);
    }
    
    /**
     * Internal enhancement logic using Spring utilities.
     */
    private static String enhanceUrl(String url, @Nullable String quality) {
        // Step 1: Normalize to HTTPS using our utility
        String normalized = UrlUtils.normalizeToHttps(url);
        if (normalized == null) {
            return null;
        }
        
        // Step 2: Remove fife parameter (causes quality issues)
        // Note: Using regex here because Spring's UriComponentsBuilder
        // doesn't handle malformed URLs well, and fife params are often malformed
        normalized = FIFE_PATTERN.matcher(normalized).replaceAll("");
        
        // Step 3: Optimize zoom using Spring's UriComponentsBuilder
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(normalized);

            String host = builder.build().getHost();
            String path = builder.build().getPath();

            if ("books.google.com".equalsIgnoreCase(host) && path != null && path.startsWith("/books/publisher/content")) {
                builder.replacePath("/books/content");
            }

            builder.replaceQueryParam("imgtk");

            // Determine optimal zoom level based on quality
            Integer optimalZoom = determineOptimalZoom(quality, normalized);
            
            if (optimalZoom != null) {
                // Spring Boot: Built-in query parameter manipulation
                builder.replaceQueryParam("zoom", optimalZoom);
            }
            
            // Build and clean
            String result = builder.build().toUriString();
            return UrlUtils.cleanTrailingSeparators(result);
            
        } catch (IllegalArgumentException e) {
            // Fallback if URL is malformed
            return UrlUtils.cleanTrailingSeparators(normalized);
        }
    }
    
    /**
     * Determines optimal zoom level using modern pattern matching.
     * <p>
     * Rules:
     * - Establish minimum zoom levels per Google size tier.
     * - Preserve or upgrade (never downgrade) any existing zoom parameter.
     * 
     * @param quality Size qualifier
     * @param currentUrl Current URL (to check existing zoom)
     * @return Optimal zoom level, or null to remove parameter
     */
    @Nullable
    private static Integer determineOptimalZoom(@Nullable String quality, String currentUrl) {
        if (quality == null) {
            return null;
        }

        int desiredZoom = switch (quality.toLowerCase()) {
            case "smallthumbnail" -> 5;
            case "thumbnail" -> 1;
            case "small" -> 2;
            case "medium" -> 3;
            case "large", "high" -> 4;
            case "extralarge" -> 6;
            default -> -1;
        };

        if (desiredZoom < 0) {
            return null;
        }

        Integer existingZoom = extractZoomValue(currentUrl);
        if (existingZoom == null) {
            return desiredZoom;
        }

        if (existingZoom < desiredZoom) {
            return desiredZoom;
        }

        return null;
    }
    
    /**
     * Extracts zoom value from URL using Java 21 pattern matching.
     * 
     * @param url URL containing zoom parameter
     * @return Zoom value or null if not present/invalid
     */
    private static Integer extractZoomValue(String url) {
        int zoomIndex = url.indexOf("zoom=");
        if (zoomIndex == -1) {
            return null;
        }
        
        String remaining = url.substring(zoomIndex + 5);
        int ampIndex = remaining.indexOf('&');
        String zoomValue = ampIndex != -1 
            ? remaining.substring(0, ampIndex) 
            : remaining;

        try {
            return Integer.parseInt(zoomValue);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    
    /**
     * Convenience method for thumbnail enhancement.
     * 
     * @param url Thumbnail URL
     * @return Enhanced thumbnail URL with zoom=1
     */
    @Nullable
    public static String enhanceThumbnail(@Nullable String url) {
        return enhanceGoogleImageUrl(url, "thumbnail");
    }
    
    /**
     * Convenience method for high-resolution image enhancement.
     * 
     * @param url High-res image URL
     * @return Enhanced URL with zoom limited to 2
     */
    @Nullable
    public static String enhanceHighResolution(@Nullable String url) {
        return enhanceGoogleImageUrl(url, "large");
    }
}
