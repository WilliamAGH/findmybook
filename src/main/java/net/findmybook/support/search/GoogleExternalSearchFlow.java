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
import java.util.concurrent.atomic.AtomicBoolean;

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
     * @return true when both dependencies are present and an authenticated or fallback tier is enabled
     */
    public boolean isAvailable() {
        return googleApiFetcher.isPresent()
            && googleBooksMapper.isPresent()
            && (googleApiFetcher.get().isApiKeyAvailable() || googleApiFetcher.get().isGoogleFallbackEnabled());
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

        Flux<JsonNode> providerItems;
        if (!fetcher.isApiKeyAvailable()) {
            providerItems = fetcher.isGoogleFallbackEnabled()
                ? fetcher.streamSearchItems(query, maxResults, externalOrderBy, null, false)
                : Flux.empty();
        } else {
            Flux<JsonNode> authenticated = fetcher.streamSearchItems(query, maxResults, externalOrderBy, null, true);
            if (!fetcher.isGoogleFallbackEnabled()) {
                providerItems = authenticated;
            } else if (!fetcher.isFallbackAllowed()) {
                providerItems = authenticated.onErrorMap(failure -> new IllegalStateException(
                    "Google Books authenticated search failed while unauthenticated fallback is unavailable",
                    failure
                ));
            } else {
                Flux<JsonNode> unauthenticated = fetcher.streamSearchItems(query, maxResults, externalOrderBy, null, false);
                providerItems = streamAuthenticatedThenFallback(authenticated, unauthenticated);
            }
        }

        return providerItems
            .map(mapper::map)
            .filter(Objects::nonNull)
            .map(BookDomainMapper::fromAggregate)
            .filter(Objects::nonNull)
            .filter(book -> StringUtils.hasText(book.getId()))
            .map(SearchExternalProviderUtils::tagGoogleFallback)
            .filter(book -> SearchExternalProviderUtils.matchesPublishedYear(book, publishedYear))
            .take(maxResults);
    }

    private Flux<JsonNode> streamAuthenticatedThenFallback(Flux<JsonNode> authenticated,
                                                            Flux<JsonNode> unauthenticated) {
        return Flux.defer(() -> {
            AtomicBoolean fallbackConsumed = new AtomicBoolean(false);
            Flux<JsonNode> authenticatedOrFallback = authenticated.onErrorResume(authenticatedFailure -> {
                fallbackConsumed.set(true);
                return unauthenticated.onErrorMap(fallbackFailure -> {
                    authenticatedFailure.addSuppressed(fallbackFailure);
                    return authenticatedFailure;
                });
            });
            return authenticatedOrFallback.concatWith(Flux.defer(
                () -> fallbackConsumed.get() ? Flux.empty() : unauthenticated
            ));
        });
    }
}
