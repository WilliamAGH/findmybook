package com.williamcallahan.book_recommendation_engine.util;

import com.williamcallahan.book_recommendation_engine.model.Book;

import java.util.Collection;
import java.util.Map;

/**
 * Utility helpers for common null/blank/empty validation checks (per user DRY request).
 */
public final class ValidationUtils {
    private ValidationUtils() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static boolean isNullOrEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    public static boolean anyNull(Object... values) {
        if (values == null) {
            return true;
        }
        for (Object value : values) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    public static boolean allNotNull(Object... values) {
        if (values == null) {
            return false;
        }
        for (Object value : values) {
            if (value == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Remove a single pair of wrapping quotes from the provided value.
     * This preserves embedded apostrophes and internal quotes while
     * guarding against upstream sources that defensively wrap strings
     * in quotes for transport.
     *
     * @param value raw string from an external source or database
     * @return value without outer quotes, or the original value when no wrapping quotes are present
     */
    public static String stripWrappingQuotes(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        if (length < 2) {
            return value;
        }
        int start = 0;
        int end = length - 1;
        if (isQuoteCharacter(value.charAt(start)) && isQuoteCharacter(value.charAt(end))) {
            return value.substring(start + 1, end);
        }
        return value;
    }

    private static boolean isQuoteCharacter(char c) {
        return c == '"' || c == '\u201C' || c == '\u201D';
    }

    /**
     * Book-specific validation utilities.
     */
    public static class BookValidator {

        /**
         * Get a non-null identifier for a book (ISBN13, ISBN10, or ID).
         * 
         * @deprecated Use {@link com.williamcallahan.book_recommendation_engine.util.cover.CoverIdentifierResolver#resolve(Book)} instead.
         * This method duplicates identifier resolution logic. CoverIdentifierResolver is the canonical
         * implementation that provides consistent identifier prioritization across the application.
         * Will be removed in version 1.0.0.
         * 
         * <p><b>Migration Example:</b></p>
         * <pre>{@code
         * // Old:
         * String identifier = ValidationUtils.BookValidator.getIdentifier(book);
         * if (!identifier.equals("unknown")) {
         *     processIdentifier(identifier);
         * }
         * 
         * // New:
         * String identifier = CoverIdentifierResolver.resolve(book);
         * if (ValidationUtils.hasText(identifier)) {
         *     processIdentifier(identifier);
         * }
         * // Note: New method returns null instead of "unknown" for invalid books
         * }</pre>
         */
        /**
         * Check if a book has basic required fields.
         */
        public static boolean hasRequiredFields(Book book) {
            return book != null &&
                   hasText(book.getTitle()) &&
                   (hasText(book.getIsbn13()) || hasText(book.getIsbn10()) || hasText(book.getId()));
        }

        /**
         * Get the preferred ISBN (13 over 10).
         * 
         * @deprecated Use {@link com.williamcallahan.book_recommendation_engine.util.cover.CoverIdentifierResolver#resolve(Book)} instead.
         * This method is part of the old identifier resolution logic. The new resolver provides comprehensive
         * identifier prioritization and is the single source of truth for book identification.
         * Will be removed in version 1.0.0.
         * 
         * <p><b>Migration Example:</b></p>
         * <pre>{@code
         * // Old:
         * String isbn = ValidationUtils.BookValidator.getPreferredIsbn(book);
         * 
         * // New:
         * String identifier = CoverIdentifierResolver.resolve(book);
         * // Note: The new method may return ID if no ISBN exists, matching the full resolution logic
         * }</pre>
         */
        /**
         * Check if book has cover images.
         */
        public static boolean hasCoverImages(Book book) {
            return book != null &&
                   book.getCoverImages() != null &&
                   hasText(book.getCoverImages().getPreferredUrl());
        }

        /**
         * Get cover URL safely.
         */
        public static String getCoverUrl(Book book) {
            if (book == null || book.getCoverImages() == null) {
                return null;
            }
            return book.getCoverImages().getPreferredUrl();
        }
    }
}
