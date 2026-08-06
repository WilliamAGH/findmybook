package net.findmybook.util;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import net.findmybook.model.Book;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Shared normalization and validation rules for external book-provider search flows.
 * Keeps Google/Open Library request shaping and year matching behavior consistent
 * across fallback and realtime enrichment pipelines.
 */
public final class SearchExternalProviderUtils {

    private static final String SEARCH_SOURCE_QUALIFIER = "search.source";
    private static final String EXTERNAL_FALLBACK_SOURCE = "EXTERNAL_FALLBACK";

    public static final String DEFAULT_ORDER_BY = "relevance";
    private static final List<String> SUPPORTED_ORDER_BY = List.of("relevance", "newest", "title", "author");

    private SearchExternalProviderUtils() {
        // Utility class
    }

    /**
     * Returns whether the provided orderBy value is supported by findmybook search APIs.
     *
     * @param orderBy raw orderBy parameter
     * @return true when orderBy is one of relevance/newest/title/author
     */
    public static boolean isSupportedOrderBy(String orderBy) {
        if (!StringUtils.hasText(orderBy)) {
            return false;
        }
        String normalized = orderBy.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_ORDER_BY.contains(normalized);
    }

    /**
     * Normalizes orderBy values for internal search orchestration.
     *
     * @param orderBy raw orderBy value
     * @return supported orderBy value, defaulting to relevance
     */
    public static String normalizeOrderBy(String orderBy) {
        if (!StringUtils.hasText(orderBy)) {
            return DEFAULT_ORDER_BY;
        }
        String normalized = orderBy.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_ORDER_BY.contains(normalized) ? normalized : DEFAULT_ORDER_BY;
    }

    /**
     * Lists supported public orderBy values for validation error messages.
     *
     * @return immutable ordered list of accepted orderBy values
     */
    public static List<String> supportedOrderByValues() {
        return SUPPORTED_ORDER_BY;
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
     * Maps findmybook orderBy values to Open Library search sort facets.
     *
     * <p>Open Library supports provider-specific sort facet names (for example {@code new}),
     * so this method exposes only known-safe mappings and leaves unsupported values empty.</p>
     *
     * @param orderBy requested orderBy value from client or internal caller
     * @return optional Open Library sort facet
     */
    public static Optional<String> normalizeOpenLibrarySortFacet(String orderBy) {
        String normalized = Optional.ofNullable(orderBy).orElse("").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "newest" -> Optional.of("new");
            default -> Optional.empty();
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
        return StringUtils.hasText(withoutQualifiers) ? withoutQualifiers : normalized;
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

    /**
     * Tags a book as a Google Books external fallback candidate.
     *
     * @param book candidate book
     * @return the same book instance with search-source qualifiers set
     */
    public static Book tagGoogleFallback(Book book) {
        return tagExternalFallback(book, "GOOGLE_API", "GOOGLE_BOOKS");
    }

    /**
     * Tags a book as an Open Library external fallback candidate.
     *
     * @param book candidate book
     * @return the same book instance with search-source qualifiers set
     */
    public static Book tagOpenLibraryFallback(Book book) {
        return tagExternalFallback(book, "OPEN_LIBRARY_API", "OPEN_LIBRARY");
    }

    /**
     * Identifies candidates discovered through an external search fallback.
     *
     * @param book candidate to inspect
     * @return true when the canonical search-source qualifier marks an external fallback
     */
    public static boolean isExternalFallback(Book book) {
        if (book == null || book.getQualifiers() == null) {
            return false;
        }
        Object source = book.getQualifiers().get(SEARCH_SOURCE_QUALIFIER);
        return source != null && EXTERNAL_FALLBACK_SOURCE.equalsIgnoreCase(source.toString());
    }

    /**
     * Detects local search-admission denials anywhere in a failure graph.
     *
     * <p>Provider fallback joins may retain one failure as a suppressed exception, while
     * other provider branches wrap it as a cause. Traversing both relationships ensures
     * local capacity rejection always reaches the HTTP 429 boundary instead of being
     * swallowed as a provider failure or reported as an internal error.</p>
     *
     * @param failure root failure emitted by a search flow
     * @return true when the failure graph contains a local rate-limit or bulkhead denial
     */
    public static boolean isLocalAdmissionDenied(Throwable failure) {
        if (failure == null) {
            return false;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof RequestNotPermitted || current instanceof BulkheadFullException) {
                return true;
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null) {
                    pending.addLast(suppressed);
                }
            }
        }
        return false;
    }

    private static Book tagExternalFallback(Book book, String matchType, String source) {
        if (book == null) {
            return null;
        }
        book.addQualifier(SEARCH_SOURCE_QUALIFIER, EXTERNAL_FALLBACK_SOURCE);
        book.addQualifier("search.provider", source);
        book.addQualifier("search.matchType", matchType);
        book.setRetrievedFrom(source);
        book.setDataSource(source);
        return book;
    }
}
