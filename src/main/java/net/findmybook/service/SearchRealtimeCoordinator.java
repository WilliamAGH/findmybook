package net.findmybook.service;

import tools.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.SearchQueryUtils;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

@Slf4j
final class SearchRealtimeCoordinator {

    private final Optional<GoogleApiFetcher> googleApiFetcher;
    private final Optional<GoogleBooksMapper> googleBooksMapper;
    private final Optional<OpenLibraryBookDataService> openLibraryBookDataService;
    private final Optional<BookDataOrchestrator> bookDataOrchestrator;
    private final Optional<ApplicationEventPublisher> eventPublisher;
    private final boolean persistSearchResultsEnabled;
    private final Cache<String, SearchRealtimeState> realtimeStates = Caffeine.newBuilder()
        .maximumSize(2_000)
        .expireAfterAccess(Duration.ofMinutes(15))
        .build();

    SearchRealtimeCoordinator(Optional<GoogleApiFetcher> googleApiFetcher,
                              Optional<GoogleBooksMapper> googleBooksMapper,
                              Optional<OpenLibraryBookDataService> openLibraryBookDataService,
                              Optional<BookDataOrchestrator> bookDataOrchestrator,
                              Optional<ApplicationEventPublisher> eventPublisher,
                              boolean persistSearchResultsEnabled) {
        this.googleApiFetcher = googleApiFetcher != null ? googleApiFetcher : Optional.empty();
        this.googleBooksMapper = googleBooksMapper != null ? googleBooksMapper : Optional.empty();
        this.openLibraryBookDataService = openLibraryBookDataService != null ? openLibraryBookDataService : Optional.empty();
        this.bookDataOrchestrator = bookDataOrchestrator != null ? bookDataOrchestrator : Optional.empty();
        this.eventPublisher = eventPublisher != null ? eventPublisher : Optional.empty();
        this.persistSearchResultsEnabled = persistSearchResultsEnabled;
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

        if (googleApiFetcher.isEmpty() && openLibraryBookDataService.isEmpty()) {
            return;
        }

        String queryHash = SearchQueryUtils.topicKey(request.query());
        String signature = realtimeSignature(request);
        SearchRealtimeState state = realtimeStates.get(queryHash, unused -> new SearchRealtimeState());

        if (!state.tryAcquireAndPrepare(signature, page.totalUnique(), page.uniqueResults())) {
            return;
        }

        publishProgress(
            request.query(),
            SearchProgressEvent.SearchStatus.STARTING,
            "Searching external providers for additional matches",
            queryHash,
            null
        );

        Flux<RealtimeCandidate> candidates = Flux.merge(
            googleRealtimeCandidates(request, queryHash),
            openLibraryRealtimeCandidates(request, queryHash)
        );

        candidates
            .filter(candidate -> candidate.book() != null)
            .filter(candidate -> StringUtils.hasText(candidate.book().getId()))
            .filter(candidate -> state.registerCandidate(candidate.book()))
            .doOnNext(candidate -> {
                persistSearchCandidates(List.of(candidate.book()), "SEARCH");
                int totalNow = state.incrementTotalAndGet();
                publishResults(
                    request.query(),
                    List.of(candidate.book()),
                    candidate.source(),
                    totalNow,
                    queryHash,
                    false
                );
            })
            .doOnComplete(() -> {
                publishProgress(
                    request.query(),
                    SearchProgressEvent.SearchStatus.COMPLETE,
                    "External search complete",
                    queryHash,
                    "EXTERNAL"
                );
            })
            .doFinally(signalType -> state.markIdle())
            .subscribe(
                ignored -> { },
                ex -> publishProgress(
                    request.query(),
                    SearchProgressEvent.SearchStatus.ERROR,
                    "External search failed: " + ex.getMessage(),
                    queryHash,
                    "EXTERNAL"
                )
            );
    }

    private Flux<RealtimeCandidate> googleRealtimeCandidates(SearchPaginationService.SearchRequest request,
                                                              String queryHash) {
        if (googleApiFetcher.isEmpty() || googleBooksMapper.isEmpty()) {
            return Flux.empty();
        }

        GoogleApiFetcher fetcher = googleApiFetcher.get();
        GoogleBooksMapper mapper = googleBooksMapper.get();
        String query = request.query();
        String orderBy = SearchExternalProviderUtils.normalizeGoogleOrderBy(request.orderBy());
        int limit = Math.max(1, Math.min(20, request.maxResults()));

        Flux<JsonNode> authenticated = Flux.defer(() -> {
            publishProgress(
                query,
                SearchProgressEvent.SearchStatus.SEARCHING_GOOGLE,
                "Searching Google Books",
                queryHash,
                "GOOGLE_BOOKS"
            );
            return fetcher.streamSearchItems(query, limit, orderBy, null, true);
        });

        Flux<JsonNode> unauthenticated = fetcher.isFallbackAllowed()
            ? fetcher.streamSearchItems(query, limit, orderBy, null, false)
            : Flux.empty();

        return Flux.concat(authenticated, unauthenticated)
            .map(mapper::map)
            .filter(Objects::nonNull)
            .map(BookDomainMapper::fromAggregate)
            .filter(Objects::nonNull)
            .filter(book -> SearchExternalProviderUtils.matchesPublishedYear(book, request.publishedYear()))
            .map(book -> {
                book.addQualifier("search.source", "EXTERNAL_FALLBACK");
                book.addQualifier("search.matchType", "GOOGLE_API");
                book.setRetrievedFrom("GOOGLE_BOOKS");
                book.setDataSource("GOOGLE_BOOKS");
                return new RealtimeCandidate("GOOGLE_BOOKS", book);
            })
            .take(limit)
            .onErrorMap(ex -> {
                SearchProgressEvent.SearchStatus errorStatus = classifyProviderError(ex);
                log.warn("Realtime Google search failed for '{}' (status={}): {}", query, errorStatus, ex.getMessage());
                publishProgress(
                    query,
                    errorStatus,
                    "Google Books unavailable, continuing with other providers",
                    queryHash,
                    "GOOGLE_BOOKS"
                );
                return new IllegalStateException("Realtime Google search failed for query '" + query + "'", ex);
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
        int limitPerStrategy = Math.max(1, Math.min(10, request.maxResults()));

        Flux<Book> byTitle = Flux.defer(() -> {
            publishProgress(
                request.query(),
                SearchProgressEvent.SearchStatus.SEARCHING_OPENLIBRARY,
                "Searching Open Library",
                queryHash,
                "OPEN_LIBRARY"
            );
            return service.searchBooksByTitle(query);
        });

        Flux<Book> byAuthor = service.searchBooksByAuthor(query);

        return Flux.merge(byTitle, byAuthor)
            .filter(Objects::nonNull)
            .filter(book -> SearchExternalProviderUtils.matchesPublishedYear(book, request.publishedYear()))
            .take(limitPerStrategy * 2L)
            .map(book -> {
                book.addQualifier("search.source", "EXTERNAL_FALLBACK");
                book.addQualifier("search.matchType", "OPEN_LIBRARY_API");
                book.setRetrievedFrom("OPEN_LIBRARY");
                book.setDataSource("OPEN_LIBRARY");
                return new RealtimeCandidate("OPEN_LIBRARY", book);
            })
            .onErrorMap(ex -> {
                SearchProgressEvent.SearchStatus errorStatus = classifyProviderError(ex);
                log.warn("Realtime Open Library search failed for '{}' (status={}): {}", request.query(), errorStatus, ex.getMessage());
                publishProgress(
                    request.query(),
                    errorStatus,
                    "Open Library unavailable, continuing with other providers",
                    queryHash,
                    "OPEN_LIBRARY"
                );
                return new IllegalStateException(
                    "Realtime Open Library search failed for query '" + request.query() + "'",
                    ex
                );
            });
    }

    private void persistSearchCandidates(List<Book> books, String context) {
        if (!persistSearchResultsEnabled || books == null || books.isEmpty() || bookDataOrchestrator.isEmpty()) {
            return;
        }
        bookDataOrchestrator.get().persistBooksAsync(books, context);
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
        eventPublisher.get().publishEvent(
            new SearchResultsUpdatedEvent(searchQuery, newResults, source, totalResultsNow, queryHash, isComplete)
        );
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

    private String candidateKey(Book book) {
        if (book == null) {
            return null;
        }
        String isbn13 = IsbnUtils.sanitize(book.getIsbn13());
        if (StringUtils.hasText(isbn13)) {
            return "ISBN13:" + isbn13;
        }
        String isbn10 = IsbnUtils.sanitize(book.getIsbn10());
        if (StringUtils.hasText(isbn10)) {
            return "ISBN10:" + isbn10;
        }
        String id = book.getId();
        if (StringUtils.hasText(id)) {
            return "ID:" + id;
        }
        String title = Optional.ofNullable(book.getTitle()).orElse("").trim().toLowerCase(Locale.ROOT);
        String firstAuthor = (book.getAuthors() == null || book.getAuthors().isEmpty())
            ? ""
            : Optional.ofNullable(book.getAuthors().get(0)).orElse("").trim().toLowerCase(Locale.ROOT);
        if (!title.isBlank() || !firstAuthor.isBlank()) {
            return "TITLE_AUTHOR:" + title + "::" + firstAuthor;
        }
        return null;
    }

    private static SearchProgressEvent.SearchStatus classifyProviderError(Throwable ex) {
        if (ex instanceof WebClientResponseException webEx && webEx.getStatusCode().value() == 429) {
            return SearchProgressEvent.SearchStatus.RATE_LIMITED;
        }
        return SearchProgressEvent.SearchStatus.PROVIDER_UNAVAILABLE;
    }

    private record RealtimeCandidate(String source, Book book) {
    }

    private final class SearchRealtimeState {
        private final Set<String> emittedKeys = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean streaming = new AtomicBoolean(false);
        private final AtomicInteger totalResults = new AtomicInteger(0);
        private volatile String signature = "";

        /**
         * Atomically resets state for a new signature, seeds existing results,
         * and acquires the streaming lock. Returns true if this call acquired
         * the lock and the caller should proceed with streaming.
         */
        synchronized boolean tryAcquireAndPrepare(String newSignature, int baselineTotal, List<Book> existingResults) {
            if (!Objects.equals(signature, newSignature)) {
                emittedKeys.clear();
                signature = newSignature;
            }
            totalResults.set(Math.max(0, baselineTotal));

            if (existingResults != null) {
                for (Book existing : existingResults) {
                    String key = candidateKey(existing);
                    if (StringUtils.hasText(key)) {
                        emittedKeys.add(key);
                    }
                }
            }

            return streaming.compareAndSet(false, true);
        }

        boolean registerCandidate(Book candidate) {
            String key = candidateKey(candidate);
            return StringUtils.hasText(key) && emittedKeys.add(key);
        }

        void markIdle() {
            streaming.set(false);
        }

        int incrementTotalAndGet() {
            return totalResults.incrementAndGet();
        }
    }
}
