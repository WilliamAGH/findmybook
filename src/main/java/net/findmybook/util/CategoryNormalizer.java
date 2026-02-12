package net.findmybook.util;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single source of truth for category validation, normalization, and deduplication.
 *
 * <p>This utility ensures consistent category handling across the entire application:
 * <ul>
 *   <li>Splits compound categories on "/" delimiter (e.g., "Fiction / Science Fiction")</li>
 *   <li>Normalizes whitespace and removes empty/blank entries</li>
 *   <li>Deduplicates using the canonical database slug, not just display text</li>
 *   <li>Rejects garbage categories (Dewey decimal, MARC codes, purely numeric)</li>
 *   <li>Preserves original casing in display names while normalizing for comparison</li>
 * </ul>
 *
 * @see net.findmybook.service.RecommendationService
 * @see net.findmybook.service.BookCollectionPersistenceService
 */
public final class CategoryNormalizer {

    /** Regex pattern for splitting compound categories with flexible whitespace. */
    private static final String SPLIT_PATTERN = "\\s*/\\s*";

    /**
     * Regex pattern for collapsing multiple whitespace characters into a single space.
     * Used to normalize internal whitespace so "Science  Fiction" equals "Science Fiction".
     */
    private static final String WHITESPACE_COLLAPSE_PATTERN = "\\s+";

    /** Matches strings that are purely numeric, Dewey Decimal, or numeric codes (e.g., "823.7", "3248", ".001"). */
    private static final Pattern GARBAGE_NUMERIC = Pattern.compile("^[.\\d][\\d.\\-\\s]*[a-z]?$", Pattern.CASE_INSENSITIVE);

    /** Matches MARC-like codes (e.g., "700=aacr2", "04b044sinebk") but not human-readable subjects like "20th Century". */
    private static final Pattern GARBAGE_MARC = Pattern.compile("^\\d+[=a-z]\\S*$", Pattern.CASE_INSENSITIVE);

    /** Matches classification-style codes with numeric prefix (e.g., "89.70 international relations"). */
    private static final Pattern GARBAGE_CLASSIFICATION = Pattern.compile("^\\d+\\.\\d+\\s+\\S.*$");

    /** Minimum number of Latin letters required for a category to be meaningful. */
    private static final int MIN_LATIN_LETTERS = 3;

    private static final int MIN_DISPLAY_LENGTH = 2;
    private static final int MAX_DISPLAY_LENGTH = 255;

    private CategoryNormalizer() {
        // Utility class - no instantiation
    }

    /**
     * Normalizes and deduplicates a list of categories.
     *
     * <p>This is the primary method for processing category lists. It uses the canonical
     * database slug ({@link #normalizeForDatabase}) as the deduplication key, so categories
     * that differ only in punctuation or casing are collapsed. For example,
     * "Fiction, anthologies (multiple authors)" and "Fiction / Anthologies (Multiple Authors)"
     * both produce slug {@code fiction-anthologies-multiple-authors} and are treated as one.
     *
     * <p><strong>Example:</strong>
     * <pre>
     * Input:  ["Fiction / Science Fiction", "fiction", "History", " ", "history / military"]
     * Output: ["Fiction", "Science Fiction", "History", "Military"]
     * </pre>
     *
     * @param categories Raw category list from external source (Google Books, user input, etc.)
     * @return Normalized, deduplicated, valid categories preserving insertion order and original casing
     */
    public static List<String> normalizeAndDeduplicate(List<String> categories) {
        if (ValidationUtils.isNullOrEmpty(categories)) {
            return List.of();
        }

        // Use canonical slug as dedup key so "Fiction, anthologies" and
        // "Fiction / Anthologies" collapse to one entry.
        Map<String, String> slugToDisplay = new LinkedHashMap<>();

        for (String category : categories) {
            if (!StringUtils.hasText(category)) {
                continue;
            }

            // Split compound categories (e.g., "Fiction / Science Fiction")
            String[] parts = category.split(SPLIT_PATTERN);

            for (String part : parts) {
                String trimmed = part.trim().replaceAll(WHITESPACE_COLLAPSE_PATTERN, " ");
                if (trimmed.isEmpty() || !isValid(trimmed)) {
                    continue;
                }

                String slug = normalizeForDatabase(trimmed);
                // Keep first occurrence's display name
                slugToDisplay.putIfAbsent(slug, trimmed);
            }
        }

        return new ArrayList<>(slugToDisplay.values());
    }

    /**
     * Normalizes categories for comparison purposes (e.g., recommendation matching).
     * 
     * <p>Returns a Set of lowercase category strings with compound categories split.
     * This is useful for fuzzy matching and overlap calculations.
     * 
     * <p><strong>Example:</strong>
     * <pre>
     * Input:  ["Fiction / Science Fiction", "History"]
     * Output: {"fiction", "science fiction", "history"}
     * </pre>
     * 
     * @param categories Category list to normalize
     * @return Set of lowercase, split category strings (no duplicates)
     */
    public static Set<String> normalizeForComparison(List<String> categories) {
        if (ValidationUtils.isNullOrEmpty(categories)) {
            return Set.of();
        }

        Set<String> normalized = new HashSet<>();
        
        for (String category : categories) {
            if (!StringUtils.hasText(category)) {
                continue;
            }

            // Split compound categories and add each part
            for (String part : category.split(SPLIT_PATTERN)) {
                // Collapse internal whitespace for consistent comparison
                String trimmed = part.trim().replaceAll(WHITESPACE_COLLAPSE_PATTERN, " ");
                if (!trimmed.isEmpty()) {
                    normalized.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
        }

        return normalized;
    }

    /**
     * Generates a database-safe normalized name for a category.
     * 
     * <p>Converts display name to lowercase and replaces non-alphanumeric characters with hyphens.
     * This is used for unique constraints and URL slugs in the database.
     * 
     * <p><strong>Example:</strong>
     * <pre>
     * Input:  "Science Fiction & Fantasy"
     * Output: "science-fiction-fantasy"
     * </pre>
     * 
     * @param displayName Human-readable category name
     * @return Database-safe normalized name (lowercase, hyphens, no special chars)
     */
    public static String normalizeForDatabase(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return "";
        }

        String normalized = displayName.toLowerCase(Locale.ROOT)
                         .replaceAll("[^\\p{L}\\p{N}]+", "-")
                         .replaceAll("^-+|-+$", ""); // Remove leading/trailing hyphens
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return "category-" + Integer.toUnsignedString(displayName.hashCode(), 16);
    }

    /**
     * Validates that a category name is meaningful and not a classification code.
     *
     * <p>Rejects:
     * <ul>
     *   <li>Null, blank, or too short (&lt; 2 chars) / too long (&gt; 255 chars)</li>
     *   <li>Dewey Decimal codes (e.g., "823.7", ".001", "940.53")</li>
     *   <li>MARC record codes (e.g., "700=aacr2", "04b044sinebk")</li>
     *   <li>Classification-prefixed labels (e.g., "89.70 international relations")</li>
     *   <li>Strings with fewer than 3 Latin letters (catches pure-numeric and code-like entries)</li>
     * </ul>
     *
     * @param category Category name to validate
     * @return true if the category represents a meaningful human-readable subject
     */
    public static boolean isValid(String category) {
        if (!StringUtils.hasText(category)) {
            return false;
        }

        String trimmed = category.trim();
        if (trimmed.length() < MIN_DISPLAY_LENGTH || trimmed.length() > MAX_DISPLAY_LENGTH) {
            return false;
        }

        // Reject Dewey Decimal / purely numeric codes
        if (GARBAGE_NUMERIC.matcher(trimmed).matches()) {
            return false;
        }

        // Reject MARC-style codes (digit-prefixed alphanumeric strings)
        if (GARBAGE_MARC.matcher(trimmed).matches()) {
            return false;
        }

        // Reject classification-prefixed labels ("89.70 international relations")
        if (GARBAGE_CLASSIFICATION.matcher(trimmed).matches()) {
            return false;
        }

        // Must contain at least MIN_LATIN_LETTERS Latin letters to be a meaningful subject
        long latinLetterCount = trimmed.codePoints()
                .filter(Character::isLetter)
                .count();
        return latinLetterCount >= MIN_LATIN_LETTERS;
    }

    /**
     * Filters a list of categories to only valid entries.
     *
     * @param categories Category list to filter
     * @return List containing only valid categories; empty list if input is null/empty
     */
    public static List<String> filterValid(List<String> categories) {
        if (ValidationUtils.isNullOrEmpty(categories)) {
            return List.of();
        }

        return categories.stream()
                         .filter(CategoryNormalizer::isValid)
                         .collect(Collectors.toList());
    }

    /**
     * Checks if two categories are equivalent using the canonical database slug.
     *
     * <p>This catches equivalence across punctuation differences:
     * "Fiction, anthologies" and "Fiction / Anthologies" are equivalent
     * because both normalize to {@code fiction-anthologies}.
     *
     * @param category1 First category
     * @param category2 Second category
     * @return true if categories produce the same canonical slug
     */
    public static boolean areEquivalent(String category1, String category2) {
        if (!StringUtils.hasText(category1) || !StringUtils.hasText(category2)) {
            return false;
        }

        return normalizeForDatabase(category1).equals(normalizeForDatabase(category2));
    }
}
