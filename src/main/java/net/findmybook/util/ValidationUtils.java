package net.findmybook.util;

import java.util.Collection;
import java.util.Map;

/**
 * Utility helpers for common null/blank/empty validation checks (per user DRY request).
 */
public final class ValidationUtils {
    private ValidationUtils() {
    }

    public static boolean isNullOrEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
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
}
