package net.findmybook.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility for generating non-persisted title-derived slug bases.
 * Persisted book slugs are allocated by PostgreSQL's {@code public.generate_slug}.
 */
public final class SlugGenerator {
    private static final Pattern NON_LATIN = Pattern.compile("[^\\w\\s-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s_]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final Pattern MULTIPLE_DASHES = Pattern.compile("-{2,}");
    private static final String FALLBACK_BOOK_SLUG = "book";

    private static final int MAX_BASE_SLUG_LENGTH = 60;

    private SlugGenerator() {}

    /**
     * Generates a stable slug from a book title.
     * Author identity is intentionally excluded because PostgreSQL owns author
     * canonicalization after the book slug is allocated.
     *
     * @param title The book title
     * @return SEO-friendly slug
     */
    public static String generateBookSlug(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }

        String titleSlug = slugify(title);
        if (titleSlug.isBlank()) {
            titleSlug = fallbackBookSlug(title);
        }
        return boundBaseSlug(titleSlug);
    }

    /**
     * Convert any string to a slug format.
     * Handles Unicode, accents, and special characters.
     */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }

        // Convert to lowercase
        String slug = input.toLowerCase(Locale.ROOT).trim();

        // Normalize Unicode characters (é -> e, etc.)
        slug = Normalizer.normalize(slug, Normalizer.Form.NFD);
        slug = slug.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");

        // Replace common contractions and special cases
        slug = slug.replace("&", "and");
        slug = slug.replace("'", "");
        // Remove left and right typographic double quotes
        slug = slug.replace("\u201C", "");
        slug = slug.replace("\u201D", "");

        // Remove all non-word characters except spaces and hyphens
        slug = NON_LATIN.matcher(slug).replaceAll("");

        // Replace spaces and underscores with hyphens
        slug = WHITESPACE.matcher(slug).replaceAll("-");

        // Remove multiple consecutive hyphens
        slug = MULTIPLE_DASHES.matcher(slug).replaceAll("-");

        // Remove leading and trailing hyphens
        slug = EDGE_DASHES.matcher(slug).replaceAll("");

        return slug;
    }

    private static String fallbackBookSlug(String title) {
        String source = title == null ? "" : title.trim();
        String hash = Integer.toUnsignedString(source.hashCode(), Character.MAX_RADIX);
        return FALLBACK_BOOK_SLUG + "-" + hash;
    }

    private static String boundBaseSlug(String slugBase) {
        return slugBase.length() > MAX_BASE_SLUG_LENGTH
            ? truncateAtWordBoundary(slugBase, MAX_BASE_SLUG_LENGTH)
            : slugBase;
    }

    /**
     * Truncate a slug at the nearest word boundary.
     */
    private static String truncateAtWordBoundary(String slug, int maxLength) {
        if (slug.length() <= maxLength) {
            return slug;
        }

        // Find the last dash before maxLength
        int lastDash = slug.lastIndexOf('-', maxLength);

        // If no dash found or it's too short, just truncate
        if (lastDash <= 0 || lastDash < maxLength / 2) {
            return slug.substring(0, maxLength);
        }

        return slug.substring(0, lastDash);
    }

}
