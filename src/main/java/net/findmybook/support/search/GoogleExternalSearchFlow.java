package net.findmybook.support.search;

import tools.jackson.databind.JsonNode;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.service.GoogleApiFetcher;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.SearchQueryUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Optional;

/**
 * Shared Google Books fallback flow used by paginated and realtime search pipelines.
 *
 * <p>This class keeps provider normalization, mapping, and filtering behavior aligned so
 * multiple services do not re-implement the same pipeline differently.</p>
 */
public final class GoogleExternalSearchFlow {

    private final Optional<GoogleApiFetcher> googleApiFetcher;
    private final Optional<GoogleBooksMapper> googleBooksMapper;

    /**
     * Builds a reusable Google Books external search flow.
     *
     * @param googleApiFetcher optional Google API fetcher dependency
     * @param googleBooksMapper optional mapper dependency for volume payloads
     */
    public GoogleExternalSearchFlow(Optional<GoogleApiFetcher> googleApiFetcher,
                                    Optional<GoogleBooksMapper> googleBooksMapper) {
        this.googleApiFetcher = googleApiFetcher != null ? googleApiFetcher : Optional.empty();
        this.googleBooksMapper = googleBooksMapper != null ? googleBooksMapper : Optional.empty();
    }

    /**
     * Indicates whether Google fallback dependencies are available.
     *
     * @return true when both fetcher and mapper are present
     */
    public boolean isAvailable() {
        return googleApiFetcher.isPresent() && googleBooksMapper.isPresent();
    }

    /**
     * Streams normalized Google Books candidates for search features.
     *
     * @param query search query string
     * @param orderBy requested sort order
     * @param publishedYear optional year filter
     * @param maxResults maximum results to return
     * @return candidate books tagged as Google external fallback hits
     */
    public Flux<Book> streamCandidates(String query,
                                       String orderBy,
                                       Integer publishedYear,
                                       int maxResults) {
        if (!isAvailable()
            || !StringUtils.hasText(query)
            || SearchQueryUtils.isWildcard(query)
            || maxResults <= 0) {
            return Flux.empty();
        }

        GoogleApiFetcher fetcher = googleApiFetcher.get();
        GoogleBooksMapper mapper = googleBooksMapper.get();
        String externalOrderBy = SearchExternalProviderUtils.normalizeGoogleOrderBy(orderBy);

        Flux<JsonNode> authenticated = fetcher.streamSearchItems(query, maxResults, externalOrderBy, null, true);
        Flux<JsonNode> unauthenticated = fetcher.isFallbackAllowed()
            ? fetcher.streamSearchItems(query, maxResults, externalOrderBy, null, false)
            : Flux.empty();

        return Flux.concat(authenticated, unauthenticated)
            .map(mapper::map)
            .filter(Objects::nonNull)
            .map(BookDomainMapper::fromAggregate)
            .filter(Objects::nonNull)
            .filter(book -> StringUtils.hasText(book.getId()))
            .map(SearchExternalProviderUtils::tagGoogleFallback)
            .filter(book -> SearchExternalProviderUtils.matchesPublishedYear(book, publishedYear))
            .take(maxResults);
    }
}
