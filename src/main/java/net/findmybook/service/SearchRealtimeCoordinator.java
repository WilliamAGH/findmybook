package net.findmybook.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.support.search.CandidateKeyResolver;
import net.findmybook.support.search.GoogleExternalSearchFlow;
import net.findmybook.support.search.SearchCandidatePersistence;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.SearchQueryUtils;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/**
 * Streams book results from external providers (Google Books, Open Library) into the
 * active search session via application events, deduplicating against already-emitted results.
 */
@Slf4j
final class SearchRealtimeCoordinator {

    private final Optional<OpenLibraryBookDataService> openLibraryBookDataService;
    private final Optional<ApplicationEventPublisher> eventPublisher;
    private final GoogleExternalSearchFlow googleExternalSearchFlow;
    private final SearchCandidatePersistence searchCandidatePersistence;
    private static final int REALTIME_STATE_CACHE_MAX_SIZE = 2_000;
    private static final Duration REALTIME_STATE_CACHE_TTL = Duration.ofMinutes(15);

    private final Cache<String, SearchRealtimeState> realtimeStates = Caffeine.newBuilder()
        .maximumSize(REALTIME_STATE_CACHE_MAX_SIZE)
        .expireAfterAccess(REALTIME_STATE_CACHE_TTL)
        .build();

    SearchRealtimeCoordinator(Optional<GoogleApiFetcher> googleApiFetcher, Optional<GoogleBooksMapper> googleBooksMapper,
                              Optional<OpenLibraryBookDataService> openLibraryBookDataService, Optional<BookDataOrchestrator> bookDataOrchestrator,
                              Optional<ApplicationEventPublisher> eventPublisher, boolean persistSearchResultsEnabled) {
        this.openLibraryBookDataService = openLibraryBookDataService != null ? openLibraryBookDataService : Optional.empty();
        this.eventPublisher = eventPublisher != null ? eventPublisher : Optional.empty();
        this.googleExternalSearchFlow = new GoogleExternalSearchFlow(googleApiFetcher, googleBooksMapper);
        this.searchCandidatePersistence = new SearchCandidatePersistence(bookDataOrchestrator, persistSearchResultsEnabled);
    }

    void trigger(SearchPaginationService.SearchRequest request, SearchPaginationService.SearchPage page) {
        if (request.startIndex() != 0
            || SearchQueryUtils.isWildcard(request.query())
            || eventPublisher.isEmpty()) {
            return;
        }
        boolean hasPostgresResults = page.uniqueResults().stream()
            .anyMatch(book -> book != null && Boolean.TRUE.equals(book.getInPostgres()));
        if (!hasPostgresResults) {
            return;
        }

        if (!googleExternalSearchFlow.isAvailable() && openLibraryBookDataService.isEmpty()) {
            return;
        }

        String queryHash = SearchQueryUtils.topicKey(request.query());
        String signature = realtimeSignature(request);
        SearchRealtimeState state = realtimeStates.get(queryHash, unused -> new SearchRealtimeState());

        if (!state.tryAcquireAndPrepare(signature, page.totalUnique(), page.uniqueResults())) {
            return;
        }

        publishProgress(request.query(), SearchProgressEvent.SearchStatus.STARTING,
            "Searching Open Library (primary) with Google Books in parallel", queryHash, null);

        Flux<RealtimeCandidate> candidates = Flux.merge(
            openLibraryRealtimeCandidates(request, queryHash),
            googleRealtimeCandidates(request, queryHash)
        );

        candidates
            .filter(candidate -> candidate.book() != null)
            .filter(candidate -> StringUtils.hasText(candidate.book().getId()))
            .filter(candidate -> state.registerCandidate(candidate.book()))
            .doOnNext(candidate -> {
                searchCandidatePersistence.persist(List.of(candidate.book()), "SEARCH");
                int totalNow = state.incrementTotalAndGet();
                publishResults(request.query(), List.of(candidate.book()), candidate.source(), totalNow, queryHash, false);
            })
            .doOnComplete(() -> {
                publishProgress(request.query(), SearchProgressEvent.SearchStatus.COMPLETE,
                    "External search complete", queryHash, "EXTERNAL");
            })
            .doFinally(signalType -> state.markIdle())
            .subscribe(
                ignored -> { },
                ex -> publishProgress(request.query(), SearchProgressEvent.SearchStatus.ERROR,
                    "External search failed: " + ex.getMessage(), queryHash, "EXTERNAL")
            );
    }

    private Flux<RealtimeCandidate> googleRealtimeCandidates(SearchPaginationService.SearchRequest request,
                                                              String queryHash) {
        if (!googleExternalSearchFlow.isAvailable()) {
            return Flux.empty();
        }

        String query = request.query();
        int limit = Math.max(1, Math.min(20, request.maxResults()));

        return Flux.defer(() -> {
            publishProgress(query, SearchProgressEvent.SearchStatus.SEARCHING_GOOGLE,
                "Searching Google Books", queryHash, "GOOGLE_BOOKS");
            return googleExternalSearchFlow.streamCandidates(
                request.query(),
                request.orderBy(),
                request.publishedYear(),
                limit
            );
        })
            .map(book -> new RealtimeCandidate("GOOGLE_BOOKS", book))
            .take(limit)
            .onErrorResume(ex -> {
                SearchProgressEvent.SearchStatus errorStatus = classifyProviderError(ex);
                log.warn("Realtime Google search failed for '{}' (status={}): {}", query, errorStatus, ex.getMessage());
                publishProgress(query, errorStatus, "Google Books unavailable, continuing with other providers",
                    queryHash, "GOOGLE_BOOKS");
                return Flux.empty();
            });
    }

    private Flux<RealtimeCandidate> openLibraryRealtimeCandidates(SearchPaginationService.SearchRequest request,
                                                                   String queryHash) {
        if (openLibraryBookDataService.isEmpty()) {
            return Flux.empty();
        }

        String query = SearchExternalProviderUtils.normalizeExternalQuery(request.query());
        if (!StringUtils.hasText(query) || SearchQueryUtils.isWildcard(query)) {
            return Flux.empty();
        }

        OpenLibraryBookDataService service = openLibraryBookDataService.get();
        int limit = Math.max(1, Math.min(20, request.maxResults()));

        return Flux.defer(() -> {
            publishProgress(request.query(), SearchProgressEvent.SearchStatus.SEARCHING_OPENLIBRARY,
                "Searching Open Library", queryHash, "OPEN_LIBRARY");
            return service.queryBooksByEverything(query);
        })
            .filter(Objects::nonNull)
            .filter(book -> SearchExternalProviderUtils.matchesPublishedYear(book, request.publishedYear()))
            .take(limit)
            .map(SearchExternalProviderUtils::tagOpenLibraryFallback)
            .map(book -> new RealtimeCandidate("OPEN_LIBRARY", book))
            .onErrorResume(ex -> {
                SearchProgressEvent.SearchStatus errorStatus = classifyProviderError(ex);
                log.warn("Realtime Open Library search failed for '{}' (status={}): {}", request.query(), errorStatus, ex.getMessage());
                publishProgress(request.query(), errorStatus, "Open Library unavailable, continuing with other providers",
                    queryHash, "OPEN_LIBRARY");
                return Flux.empty();
            });
    }

    private void publishProgress(String searchQuery,
                                 SearchProgressEvent.SearchStatus status,
                                 String message,
                                 String queryHash,
                                 String source) {
        if (eventPublisher.isEmpty()) {
            return;
        }
        eventPublisher.get().publishEvent(new SearchProgressEvent(searchQuery, status, message, queryHash, source));
    }

    private void publishResults(String searchQuery,
                                List<Book> newResults,
                                String source,
                                int totalResultsNow,
                                String queryHash,
                                boolean isComplete) {
        if (eventPublisher.isEmpty()) {
            return;
        }
        eventPublisher.get().publishEvent(new SearchResultsUpdatedEvent(searchQuery, newResults, source, totalResultsNow, queryHash, isComplete));
    }

    private String realtimeSignature(SearchPaginationService.SearchRequest request) {
        String source = request.coverSource() != null ? request.coverSource().name() : "ANY";
        String resolution = request.resolutionPreference() != null ? request.resolutionPreference().name() : "ANY";
        return request.query()
            + "|"
            + Optional.ofNullable(request.orderBy()).orElse("relevance")
            + "|"
            + source
            + "|"
            + resolution
            + "|"
            + Optional.ofNullable(request.publishedYear()).map(String::valueOf).orElse("-");
    }

    private static SearchProgressEvent.SearchStatus classifyProviderError(Throwable ex) {
        if (ex instanceof WebClientResponseException webEx && webEx.getStatusCode().value() == 429) {
            return SearchProgressEvent.SearchStatus.RATE_LIMITED;
        }
        return SearchProgressEvent.SearchStatus.PROVIDER_UNAVAILABLE;
    }

    private record RealtimeCandidate(String source, Book book) {}

    private final class SearchRealtimeState {
        private final Set<String> emittedKeys = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean streaming = new AtomicBoolean(false);
        private final AtomicInteger totalResults = new AtomicInteger(0);
        private volatile String signature = "";

        synchronized boolean tryAcquireAndPrepare(String newSignature, int baselineTotal, List<Book> existingResults) {
            if (!Objects.equals(signature, newSignature)) {
                emittedKeys.clear();
                signature = newSignature;
            }
            totalResults.set(Math.max(0, baselineTotal));

            if (existingResults != null) {
                for (Book existing : existingResults) {
                    CandidateKeyResolver.resolve(existing).ifPresent(emittedKeys::add);
                }
            }

            return streaming.compareAndSet(false, true);
        }

        boolean registerCandidate(Book candidate) {
            return CandidateKeyResolver.resolve(candidate)
                .filter(emittedKeys::add)
                .isPresent();
        }

        void markIdle() {
            streaming.set(false);
        }

        int incrementTotalAndGet() {
            return totalResults.incrementAndGet();
        }
    }
}
