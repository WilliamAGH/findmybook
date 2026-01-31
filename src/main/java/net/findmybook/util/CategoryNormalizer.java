package net.findmybook.util;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Single source of truth for category validation, normalization, and deduplication.
 * 
 * <p>This utility ensures consistent category handling across the entire application:
 * <ul>
 *   <li>Splits compound categories (e.g., "Fiction / Science Fiction" → ["Fiction", "Science Fiction"])</li>
 *   <li>Normalizes whitespace and removes empty/blank entries</li>
 *   <li>Deduplicates categories (case-insensitive)</li>
 *   <li>Preserves original casing in display names while normalizing for comparison</li>
 * </ul>
 * 
 * <p><strong>Usage Locations:</strong>
 * <ul>
 *   <li>{@code RecommendationService} - for category-based book matching</li>
 *   <li>{@code BookCollectionPersistenceService} - for database persistence</li>
 *   <li>{@code BookSupplementalPersistenceService} - for category deduplication before save</li>
 *   <li>{@code GoogleBooksMapper} - for API response parsing</li>
 * </ul>
 * 
 * @see net.findmybook.service.RecommendationService
 * @see net.findmybook.service.BookCollectionPersistenceService
 */
public final class CategoryNormalizer {

    /**
     * Regex pattern for splitting compound categories with flexible whitespace
     */
    private static final String SPLIT_PATTERN = "\\s*/\\s*";

    /**
     * Regex pattern for collapsing multiple whitespace characters into a single space.
     * Used to normalize internal whitespace so "Science  Fiction" equals "Science Fiction".
     */
    private static final String WHITESPACE_COLLAPSE_PATTERN = "\\s+";

    private CategoryNormalizer() {
        // Utility class - no instantiation
    }

    /**
     * Normalizes and deduplicates a list of categories.
     * 
     * <p>This is the primary method for processing category lists:
     * <ul>
     *   <li>Splits compound categories on "/" delimiter</li>
     *   <li>Trims whitespace from each part</li>
     *   <li>Removes empty/blank entries</li>
     *   <li>Deduplicates based on case-insensitive comparison</li>
     *   <li>Preserves original casing of first occurrence</li>
     *   <li>Maintains insertion order</li>
     * </ul>
     * 
     * <p><strong>Example:</strong>
     * <pre>
     * Input:  ["Fiction / Science Fiction", "fiction", "History", " ", "history / military"]
     * Output: ["Fiction", "Science Fiction", "History", "Military"]
     * </pre>
     * 
     * @param categories Raw category list from external source (Google Books, user input, etc.)
     * @return Normalized, deduplicated list preserving insertion order and original casing
     */
    public static List<String> normalizeAndDeduplicate(List<String> categories) {
        if (ValidationUtils.isNullOrEmpty(categories)) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>(); // Preserves insertion order
        Map<String, String> lowercaseToOriginal = new LinkedHashMap<>();

        for (String category : categories) {
            if (!ValidationUtils.hasText(category)) {
                continue;
            }

            // Split compound categories (e.g., "Fiction / Science Fiction")
            String[] parts = category.split(SPLIT_PATTERN);
            
            for (String part : parts) {
                // Trim leading/trailing whitespace and collapse internal whitespace to single spaces
                // This ensures "Science  Fiction" and "Science Fiction" are treated as identical
                String trimmed = part.trim().replaceAll(WHITESPACE_COLLAPSE_PATTERN, " ");
                if (trimmed.isEmpty()) {
                    continue;
                }

                String lowercase = trimmed.toLowerCase(Locale.ROOT);
                
                // Only add if not seen before (case-insensitive deduplication)
                if (!lowercaseToOriginal.containsKey(lowercase)) {
                    lowercaseToOriginal.put(lowercase, trimmed);
                    seen.add(trimmed);
                }
            }
        }

        return new ArrayList<>(seen);
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
            if (!ValidationUtils.hasText(category)) {
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
        if (!ValidationUtils.hasText(displayName)) {
            return "";
        }

        return displayName.toLowerCase(Locale.ROOT)
                         .replaceAll("[^a-z0-9]+", "-")
                         .replaceAll("^-+|-+$", ""); // Remove leading/trailing hyphens
    }

    /**
     * Validates that a category name meets basic requirements.
     * 
     * <p>Requirements:
     * <ul>
     *   <li>Not null or blank</li>
     *   <li>At least 2 characters (after trimming)</li>
     *   <li>Does not exceed 255 characters</li>
     * </ul>
     * 
     * @param category Category name to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValid(String category) {
        if (!ValidationUtils.hasText(category)) {
            return false;
        }

        String trimmed = category.trim();
        return trimmed.length() >= 2 && trimmed.length() <= 255;
    }

    /**
     * Filters a list of categories to only valid entries.
     * 
     * <p>Removes invalid categories based on {@link #isValid(String)} criteria.
     * 
     * @param categories Category list to filter
     * @return List containing only valid categories
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
     * Checks if two categories are equivalent (case-insensitive).
     * 
     * @param category1 First category
     * @param category2 Second category
     * @return true if categories are equivalent (ignoring case and whitespace)
     */
    public static boolean areEquivalent(String category1, String category2) {
        if (!ValidationUtils.hasText(category1) || !ValidationUtils.hasText(category2)) {
            return false;
        }

        // Collapse internal whitespace for consistent comparison
        String normalized1 = category1.trim().replaceAll(WHITESPACE_COLLAPSE_PATTERN, " ");
        String normalized2 = category2.trim().replaceAll(WHITESPACE_COLLAPSE_PATTERN, " ");
        return normalized1.equalsIgnoreCase(normalized2);
    }
}

