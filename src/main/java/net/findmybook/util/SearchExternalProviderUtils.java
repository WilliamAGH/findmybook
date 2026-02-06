package net.findmybook.util;

import net.findmybook.model.Book;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared normalization and validation rules for external book-provider search flows.
 * Keeps Google/Open Library request shaping and year matching behavior consistent
 * across fallback and realtime enrichment pipelines.
 */
public final class SearchExternalProviderUtils {

    private SearchExternalProviderUtils() {
        // Utility class
    }

    /**
     * Normalizes sort order values for Google Books search endpoints.
     *
     * @param orderBy requested orderBy value from client or internal caller
     * @return provider-compatible value accepted by Google Books API
     */
    public static String normalizeGoogleOrderBy(String orderBy) {
        String normalized = Optional.ofNullable(orderBy).orElse("").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "newest", "relevance" -> normalized;
            default -> "relevance";
        };
    }

    /**
     * Normalizes query strings before dispatching to Open Library.
     * Removes Google-style field qualifiers that Open Library does not support.
     *
     * @param query raw query
     * @return cleaned query suitable for Open Library search endpoints
     */
    public static String normalizeExternalQuery(String query) {
        String normalized = SearchQueryUtils.normalize(query);
        if (SearchQueryUtils.isWildcard(normalized)) {
            return normalized;
        }
        String withoutQualifiers = normalized
            .replace("intitle:", " ")
            .replace("inauthor:", " ")
            .replace("isbn:", " ")
            .trim();
        return ValidationUtils.hasText(withoutQualifiers) ? withoutQualifiers : normalized;
    }

    /**
     * Applies published-year filtering against a hydrated book.
     *
     * @param book candidate book
     * @param publishedYear optional year constraint
     * @return true when the candidate matches the year constraint (or no constraint exists)
     */
    public static boolean matchesPublishedYear(Book book, Integer publishedYear) {
        if (publishedYear == null) {
            return true;
        }
        if (book == null || book.getPublishedDate() == null) {
            return false;
        }
        int year = book.getPublishedDate().toInstant()
            .atZone(ZoneOffset.UTC)
            .getYear();
        return year == publishedYear;
    }
}
