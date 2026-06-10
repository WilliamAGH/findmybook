package net.findmybook.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared helpers for normalizing ISBN input before lookups or persistence.
 */
public final class IsbnUtils {

    public static final int ISBN_10_LENGTH = 10;
    public static final int ISBN_13_LENGTH = 13;

    private static final Pattern NON_ISBN_CHARACTERS = Pattern.compile("[^0-9Xx]");
    private static final String ISBN_13_BOOK_PREFIX = "978";

    private IsbnUtils() {
    }

    /**
     * Normalizes an ISBN by removing non-numeric characters (except the X check digit) and
     * uppercasing the result.
     *
     * @param raw user-provided ISBN input
     * @return cleaned ISBN string, or {@code null} if nothing usable remains
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = NON_ISBN_CHARACTERS.matcher(raw).replaceAll("");
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.toUpperCase(Locale.ROOT);
    }

    /**
     * Validate if a string is a valid ISBN-13.
     */
    public static boolean isValidIsbn13(String isbn) {
        if (isbn == null) {
            return false;
        }
        String cleaned = sanitize(isbn);
        return cleaned != null && cleaned.length() == ISBN_13_LENGTH && cleaned.matches("\\d{13}");
    }

    /**
     * Validate if a string is a valid ISBN-10.
     */
    public static boolean isValidIsbn10(String isbn) {
        if (isbn == null) {
            return false;
        }
        String cleaned = sanitize(isbn);
        return cleaned != null && cleaned.length() == ISBN_10_LENGTH && cleaned.matches("\\d{9}[\\dX]");
    }

    /**
     * Converts a syntactically valid ISBN-10 into the equivalent ISBN-13 used for modern book identity.
     *
     * @param rawIsbn10 user-provided ISBN-10 input
     * @return equivalent ISBN-13 value, or {@code null} when the input is not ISBN-10 shaped
     */
    public static String toIsbn13(String rawIsbn10) {
        String cleaned = sanitize(rawIsbn10);
        if (cleaned == null || cleaned.length() != ISBN_10_LENGTH || !cleaned.matches("\\d{9}[\\dX]")) {
            return null;
        }
        String firstTwelveDigits = ISBN_13_BOOK_PREFIX + cleaned.substring(0, ISBN_10_LENGTH - 1);
        return firstTwelveDigits + isbn13CheckDigit(firstTwelveDigits);
    }

    /**
     * Converts a 978-prefixed ISBN-13 into the equivalent ISBN-10 representation.
     *
     * @param rawIsbn13 user-provided ISBN-13 input
     * @return equivalent ISBN-10 value, or {@code null} when the input cannot be represented as ISBN-10
     */
    public static String toIsbn10(String rawIsbn13) {
        String cleaned = sanitize(rawIsbn13);
        if (cleaned == null
            || cleaned.length() != ISBN_13_LENGTH
            || !cleaned.matches("\\d{13}")
            || !cleaned.startsWith(ISBN_13_BOOK_PREFIX)) {
            return null;
        }
        String firstNineDigits = cleaned.substring(ISBN_13_BOOK_PREFIX.length(), ISBN_13_LENGTH - 1);
        return firstNineDigits + isbn10CheckDigit(firstNineDigits);
    }

    /**
     * Resolves either ISBN-10 or ISBN-13 input to the ISBN-13 identity key used for matching.
     *
     * @param raw user-provided ISBN input
     * @return ISBN-13 identity value, or {@code null} when no ISBN-13 equivalent can be produced
     */
    public static String isbn13Identity(String raw) {
        String cleaned = sanitize(raw);
        if (cleaned == null) {
            return null;
        }
        if (cleaned.length() == ISBN_13_LENGTH && cleaned.matches("\\d{13}")) {
            return cleaned;
        }
        if (cleaned.length() == ISBN_10_LENGTH) {
            return toIsbn13(cleaned);
        }
        return null;
    }

    private static int isbn13CheckDigit(String firstTwelveDigits) {
        int sum = 0;
        for (int index = 0; index < firstTwelveDigits.length(); index++) {
            int digit = Character.digit(firstTwelveDigits.charAt(index), 10);
            sum += digit * (index % 2 == 0 ? 1 : 3);
        }
        return (10 - (sum % 10)) % 10;
    }

    private static char isbn10CheckDigit(String firstNineDigits) {
        int sum = 0;
        for (int index = 0; index < firstNineDigits.length(); index++) {
            int digit = Character.digit(firstNineDigits.charAt(index), 10);
            sum += digit * (10 - index);
        }
        int remainder = 11 - (sum % 11);
        if (remainder == 10) {
            return 'X';
        }
        if (remainder == 11) {
            return '0';
        }
        return (char) ('0' + remainder);
    }
}
