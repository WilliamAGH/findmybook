package com.williamcallahan.book_recommendation_engine.util.cover;

import com.williamcallahan.book_recommendation_engine.util.ValidationUtils;

import java.util.Set;

/**
 * Validates book cover image URLs to detect title pages and interior content.
 * 
 * Google Books API sometimes returns internal pages (title pages, copyright pages, table of contents)
 * instead of actual book covers, even when requesting "frontcover". This utility helps identify
 * such problematic URLs.
 * 
 * Key Detection Strategies:
 * - URL printsec parameter patterns (titlepage, copyright, toc)
 * - Presence of edge=curl parameter (indicates actual cover)
 * - URL structure analysis
 * 
 * @author William Callahan
 */
public final class CoverUrlValidator {
    
    // Patterns in URLs that indicate title pages or interior content (not actual covers)
    private static final Set<String> SUSPICIOUS_PATTERNS = Set.of(
        "printsec=titlepage",
        "printsec=copyright", 
        "printsec=toc",
        "printsec=index",
        "printsec=contents"
    );
    
    private CoverUrlValidator() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Checks if a URL is likely to be an actual book cover rather than a title page or interior content.
     * 
     * <p>Detection Logic:</p>
     * <ul>
     *   <li>Rejects URLs with suspicious printsec parameters (titlepage, copyright, toc, etc.)</li>
     *   <li>For Google Books URLs: requires edge=curl parameter (indicates cover binding/edge)</li>
     *   <li>Accepts non-Google Books URLs by default (S3, Open Library, etc.)</li>
     * </ul>
     * 
     * @param url The image URL to validate (may be null or empty)
     * @return true if URL is likely a valid cover image, false if likely a title page or interior content
     * 
     * @example
     * <pre>{@code
     * // Valid cover (has edge=curl)
     * boolean valid = CoverUrlValidator.isLikelyCoverImage(
     *     "https://books.google.com/books/content?id=ABC&printsec=frontcover&edge=curl");
     * // Returns: true
     * 
     * // Title page (no edge=curl)
     * boolean invalid = CoverUrlValidator.isLikelyCoverImage(
     *     "https://books.google.com/books/content?id=ABC&printsec=titlepage");
     * // Returns: false
     * 
     * // S3 cover (not Google Books)
     * boolean valid = CoverUrlValidator.isLikelyCoverImage(
     *     "https://s3.amazonaws.com/covers/book123.jpg");
     * // Returns: true
     * }</pre>
     */
    public static boolean isLikelyCoverImage(String url) {
        if (!ValidationUtils.hasText(url)) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        
        // Check for explicit suspicious patterns (title pages, copyright pages, etc.)
        for (String pattern : SUSPICIOUS_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return false; // Definitely not a cover
            }
        }
        
        // Google Books specific validation:
        // The "edge=curl" parameter indicates a cover image showing the book's spine/edge
        // Images without this parameter are often interior pages
        if (lowerUrl.contains("books.google.com")) {
            // Require edge=curl for Google Books URLs
            // Exception: Some very old books or special editions may not have edge=curl
            // but we prefer to be strict and use S3 covers when available
            return lowerUrl.contains("edge=curl");
        }
        
        // Non-Google Books URLs (S3, Open Library, NYT, etc.) are accepted by default
        return true;
    }
    
    /**
     * Checks if a URL points to a Google Books image.
     * 
     * @param url The URL to check
     * @return true if URL is from Google Books, false otherwise
     */
    public static boolean isGoogleBooksUrl(String url) {
        if (!ValidationUtils.hasText(url)) {
            return false;
        }
        return url.toLowerCase().contains("books.google.com");
    }
    
    /**
     * Extracts the reason why a URL was rejected (for logging/debugging).
     * 
     * @param url The URL to analyze
     * @return Rejection reason or null if URL would be accepted
     */
    public static String getRejectionReason(String url) {
        if (!ValidationUtils.hasText(url)) {
            return "Empty or null URL";
        }
        
        String lowerUrl = url.toLowerCase();
        
        // Check for suspicious patterns
        for (String pattern : SUSPICIOUS_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return "Contains suspicious pattern: " + pattern;
            }
        }
        
        // Check for missing edge=curl on Google Books
        if (lowerUrl.contains("books.google.com") && !lowerUrl.contains("edge=curl")) {
            return "Google Books URL missing edge=curl parameter (likely interior page)";
        }
        
        return null; // Would be accepted
    }
}

