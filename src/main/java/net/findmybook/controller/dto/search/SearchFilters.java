package net.findmybook.controller.dto.search;

import net.findmybook.util.SearchExternalProviderUtils;

/**
 * Offset-based pagination and search filter contract for {@code /api/books/search}.
 *
 * <p>Search uses {@code startIndex} as a zero-based absolute offset and
 * {@code maxResults} as a page size. This API does not use Spring Data
 * page-number pagination semantics.</p>
 *
 * @param startIndex optional zero-based absolute offset
 * @param maxResults optional page size
 * @param orderBy optional requested ordering key
 * @param publishedYear optional publication-year filter
 * @param coverSource optional preferred cover source
 * @param resolution optional preferred cover resolution
 */
public record SearchFilters(Integer startIndex,
                            Integer maxResults,
                            String orderBy,
                            Integer publishedYear,
                            String coverSource,
                            String resolution) {

    /**
     * Returns a normalized start index defaulting to {@code 0}.
     */
    public int effectiveStartIndex() {
        return startIndex != null ? startIndex : 0;
    }

    /**
     * Returns a normalized page size defaulting to {@code 12}.
     */
    public int effectiveMaxResults() {
        return maxResults != null ? maxResults : 12;
    }

    /**
     * Returns an order-by value normalized to supported external-provider semantics.
     */
    public String effectiveOrderBy() {
        return SearchExternalProviderUtils.normalizeOrderBy(orderBy);
    }

    /**
     * Returns a normalized cover source defaulting to {@code ANY}.
     */
    public String effectiveCoverSource() {
        return coverSource != null && !coverSource.isBlank() ? coverSource : "ANY";
    }

    /**
     * Returns a normalized resolution defaulting to {@code ANY}.
     */
    public String effectiveResolution() {
        return resolution != null && !resolution.isBlank() ? resolution : "ANY";
    }
}
