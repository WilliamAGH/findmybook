package net.findmybook.service;

import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.support.search.GoogleExternalSearchFlow;
import net.findmybook.support.search.PostgresSearchResultHydrator;
import net.findmybook.support.search.SearchCandidatePersistence;
import net.findmybook.support.search.SearchPageAssembler;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.SearchQueryUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates paginated search using repository-backed DTO projections.
 * Executes postgres-first search with deterministic ordering and deduplication.
 */
@Service
@Slf4j
public class SearchPaginationService {

    private final BookSearchService bookSearchService;
    private final PostgresSearchResultHydrator postgresSearchResultHydrator;
    private final SearchPageAssembler searchPageAssembler;
    private final GoogleExternalSearchFlow googleExternalSearchFlow;
    private final SearchCandidatePersistence searchCandidatePersistence;
    private final SearchRealtimeCoordinator searchRealtimeCoordinator;

    SearchPaginationService(BookSearchService bookSearchService,
                            BookQueryRepository bookQueryRepository,
                            Optional<GoogleApiFetcher> googleApiFetcher,
                            Optional<GoogleBooksMapper> googleBooksMapper) {
        this(
            bookSearchService,
            bookQueryRepository,
            googleApiFetcher,
            googleBooksMapper,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            true
        );
    }

    @Autowired
    public SearchPaginationService(BookSearchService bookSearchService,
                                   BookQueryRepository bookQueryRepository,
                                   Optional<GoogleApiFetcher> googleApiFetcher,
                                   Optional<GoogleBooksMapper> googleBooksMapper,
                                   Optional<OpenLibraryBookDataService> openLibraryBookDataService,
                                   Optional<BookDataOrchestrator> bookDataOrchestrator,
                                   Optional<ApplicationEventPublisher> eventPublisher,
                                   @Value("${app.features.persist-search-results:true}") boolean persistSearchResultsEnabled) {
        this.bookSearchService = Objects.requireNonNull(bookSearchService, "bookSearchService");
        this.postgresSearchResultHydrator = new PostgresSearchResultHydrator(bookQueryRepository);
        this.searchPageAssembler = new SearchPageAssembler();
        this.googleExternalSearchFlow = new GoogleExternalSearchFlow(googleApiFetcher, googleBooksMapper);
        this.searchCandidatePersistence = new SearchCandidatePersistence(bookDataOrchestrator, persistSearchResultsEnabled);
        this.searchRealtimeCoordinator = new SearchRealtimeCoordinator(
            googleApiFetcher,
            googleBooksMapper,
            openLibraryBookDataService,
            bookDataOrchestrator,
            eventPublisher,
            persistSearchResultsEnabled
        );
    }

    /**
     * Executes a postgres-first paginated search and optionally backfills empty pages from Google Books.
     *
     * @param request normalized search request
     * @return page payload containing ordered items and pagination metadata
     */
    public Mono<SearchPage> search(SearchRequest request) {
        PagingUtils.Window window = PagingUtils.window(
            request.startIndex(),
            request.maxResults(),
            ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT,
            ApplicationConstants.Paging.MIN_SEARCH_LIMIT,
            ApplicationConstants.Paging.MAX_SEARCH_LIMIT,
            0
        );

        return performSearch(request, window, System.nanoTime());
    }

    private Mono<SearchPage> performSearch(SearchRequest request,
                                           PagingUtils.Window window,
                                           long startNanos) {
        return Mono.fromCallable(() -> bookSearchService.searchBooks(request.query(), window.totalRequested()))
            .subscribeOn(Schedulers.boundedElastic())
            .map(results -> postgresSearchResultHydrator.filterByPublishedYear(results, request.publishedYear()))
            .map(postgresSearchResultHydrator::mapOrderedResults)
            .map(list -> searchPageAssembler.buildPage(
                request.query(),
                request.orderBy(),
                request.coverSource(),
                request.resolutionPreference(),
                list,
                window
            ))
            .flatMap(page -> maybeFallback(request, window, page))
            .doOnNext(page -> searchRealtimeCoordinator.trigger(request, page))
            .doOnNext(page -> logPageMetrics(request, window, page, startNanos));
    }

    /**
     * Attempts to backfill empty search results using the Google Books tier while respecting all
     * runtime feature flags and rate-limit guards. Authenticated calls are always attempted first
     * and the unauthenticated fallback only runs when the shared {@link ApiCircuitBreakerService}
     * reports that the public quota is still available.
     */
    private Mono<SearchPage> maybeFallback(SearchRequest request,
                                           PagingUtils.Window window,
                                           SearchPage currentPage) {
        boolean shouldFallback = currentPage.totalUnique() == 0
            && googleExternalSearchFlow.isAvailable()
            && !SearchQueryUtils.isWildcard(request.query())
            && window.totalRequested() > 0;

        if (!shouldFallback) {
            return Mono.just(currentPage);
        }

        return googleExternalSearchFlow
            .streamCandidates(request.query(), request.orderBy(), request.publishedYear(), window.totalRequested())
            .collectList()
            .map(fallbackBooks -> mergeFallbackResults(fallbackBooks, currentPage, window, request))
            .onErrorResume(ex -> {
                log.warn("Fallback search processing failed for '{}': {}", request.query(), ex.getMessage());
                return Mono.error(new IllegalStateException(
                    "Fallback search processing failed for query '" + request.query() + "'",
                    ex
                ));
            });
    }

    private SearchPage mergeFallbackResults(List<Book> fallbackBooks,
                                            SearchPage currentPage,
                                            PagingUtils.Window window,
                                            SearchRequest request) {
        if (fallbackBooks == null || fallbackBooks.isEmpty()) {
            return currentPage;
        }
        searchCandidatePersistence.persist(fallbackBooks, "SEARCH");
        return searchPageAssembler.buildPage(
            request.query(),
            request.orderBy(),
            request.coverSource(),
            request.resolutionPreference(),
            fallbackBooks,
            window
        );
    }

    private void logPageMetrics(SearchRequest request,
                                PagingUtils.Window window,
                                SearchPage page,
                                long startNanos) {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        log.info(
            "Paginated search '{}' start={} size={} fetched={} hasMore={} prefetched={} ({} ms)",
            request.query(),
            window.startIndex(),
            window.limit(),
            page.totalUnique(),
            page.hasMore(),
            page.prefetchedCount(),
            elapsedMillis
        );
    }

    /**
     * Immutable request contract for paginated search.
     */
    public record SearchRequest(String query,
                                int startIndex,
                                int maxResults,
                                String orderBy,
                                CoverImageSource coverSource,
                                ImageResolutionPreference resolutionPreference,
                                Integer publishedYear) {
        public SearchRequest(String query,
                             int startIndex,
                             int maxResults,
                             String orderBy,
                             CoverImageSource coverSource,
                             ImageResolutionPreference resolutionPreference) {
            this(query, startIndex, maxResults, orderBy, coverSource, resolutionPreference, null);
        }

        public SearchRequest {
            Objects.requireNonNull(query, "query");
            orderBy = Optional.ofNullable(orderBy).orElse("newest");
            coverSource = Optional.ofNullable(coverSource).orElse(CoverImageSource.ANY);
            resolutionPreference = Optional.ofNullable(resolutionPreference).orElse(ImageResolutionPreference.ANY);
            publishedYear = publishedYear != null && publishedYear > 0 ? publishedYear : null;
        }
    }

    /**
     * Immutable paginated response payload for search endpoints.
     */
    public record SearchPage(String query,
                             int startIndex,
                             int maxResults,
                             int totalRequested,
                             int totalUnique,
                             List<Book> pageItems,
                             List<Book> uniqueResults,
                             boolean hasMore,
                             int nextStartIndex,
                             int prefetchedCount,
                             String orderBy,
                             CoverImageSource coverSource,
                             ImageResolutionPreference resolutionPreference) {
    }
}
