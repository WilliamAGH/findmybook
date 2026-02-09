package net.findmybook.service;

import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.support.search.GoogleExternalSearchFlow;
import net.findmybook.support.search.PostgresSearchResultHydrator;
import net.findmybook.support.search.CandidateKeyResolver;
import net.findmybook.support.search.SearchCandidatePersistence;
import net.findmybook.support.search.SearchPageAssembler;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.cover.CoverQuality;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates paginated search using repository-backed DTO projections.
 * Executes postgres-first search with deterministic ordering and deduplication.
 */
@Service
@Slf4j
public class SearchPaginationService {

    private static final int EXTERNAL_PROVIDER_WINDOW_CAP = ApplicationConstants.Paging.MAX_TIERED_LIMIT;

    private final BookSearchService bookSearchService;
    private final PostgresSearchResultHydrator postgresSearchResultHydrator;
    private final SearchPageAssembler searchPageAssembler;
    private final Optional<OpenLibraryBookDataService> openLibraryBookDataService;
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
        this.openLibraryBookDataService = openLibraryBookDataService != null ? openLibraryBookDataService : Optional.empty();
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
     * Executes a postgres-first paginated search and optionally supplements the requested
     * result window with external providers.
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
     * Attempts to backfill empty search results using external providers while respecting runtime
     * feature flags and rate-limit guards. Open Library is the primary external provider and
     * Google Books supplements remaining slots when Open Library does not satisfy the request.
     */
    private Mono<SearchPage> maybeFallback(SearchRequest request,
                                           PagingUtils.Window window,
                                           SearchPage currentPage) {
        boolean openLibraryAvailable = openLibraryBookDataService.isPresent();
        boolean googleAvailable = googleExternalSearchFlow.isAvailable();
        int requestedWindow = requestedExternalWindow(window);
        boolean canUseExternalProviders = (openLibraryAvailable || googleAvailable)
            && !SearchQueryUtils.isWildcard(request.query())
            && requestedWindow > 0;

        if (!canUseExternalProviders) {
            return Mono.just(currentPage);
        }

        boolean shouldSupplementCurrentPage = currentPage.totalUnique() == 0
            || (window.startIndex() > 0 && currentPage.pageItems().size() < window.limit());
        boolean shouldAugmentWithOpenLibrary = window.startIndex() == 0
            && openLibraryAvailable
            && (hasCoverGap(currentPage, window.limit()) || hasMetadataGap(currentPage, window.limit()));

        if (!shouldSupplementCurrentPage && !shouldAugmentWithOpenLibrary) {
            return Mono.just(currentPage);
        }

        // Always hydrate from offset 0 to keep merged sorting/slicing deterministic for later pages.
        return streamOpenLibraryCandidates(request, 0, requestedWindow)
            .collectList()
            .flatMap(primaryCandidates -> {
                if (!shouldFetchGoogleSecondary(
                    requestedWindow,
                    currentPage.uniqueResults(),
                    primaryCandidates,
                    googleAvailable,
                    shouldSupplementCurrentPage
                )) {
                    return Mono.just(mergeFallbackResults(primaryCandidates, currentPage, window, request));
                }

                return googleExternalSearchFlow
                    .streamCandidates(request.query(), request.orderBy(), request.publishedYear(), requestedWindow)
                    .onErrorResume(ex -> {
                        log.warn("Google fallback failed for '{}': {}", request.query(), ex.getMessage());
                        return Flux.empty();
                    })
                    .collectList()
                    .map(secondaryCandidates -> {
                        List<Book> combined = new ArrayList<>(primaryCandidates.size() + secondaryCandidates.size());
                        combined.addAll(primaryCandidates);
                        combined.addAll(secondaryCandidates);
                        return mergeFallbackResults(combined, currentPage, window, request);
                    });
            });
    }

    private int requestedExternalWindow(PagingUtils.Window window) {
        int minimumWindow = Math.max(1, window.limit());
        int desiredWindow = Math.max(minimumWindow, window.totalRequested());
        return Math.min(desiredWindow, EXTERNAL_PROVIDER_WINDOW_CAP);
    }

    private boolean shouldFetchGoogleSecondary(int requestedWindow,
                                               List<Book> existingResults,
                                               List<Book> openLibraryCandidates,
                                               boolean googleAvailable,
                                               boolean shouldSupplementResultWindow) {
        if (!googleAvailable || !shouldSupplementResultWindow) {
            return false;
        }
        if (openLibraryBookDataService.isEmpty()) {
            return true;
        }
        int projectedUnique = projectedUniqueCount(existingResults, openLibraryCandidates);
        return projectedUnique < requestedWindow;
    }

    private int projectedUniqueCount(List<Book> existingResults, List<Book> fallbackCandidates) {
        Set<String> projectedKeys = ConcurrentHashMap.newKeySet();
        if (existingResults != null) {
            for (Book existing : existingResults) {
                CandidateKeyResolver.resolve(existing).ifPresent(projectedKeys::add);
            }
        }
        if (fallbackCandidates != null) {
            for (Book candidate : fallbackCandidates) {
                CandidateKeyResolver.resolve(candidate).ifPresent(projectedKeys::add);
            }
        }
        return projectedKeys.size();
    }

    private Flux<Book> streamOpenLibraryCandidates(SearchRequest request, int startIndex, int maxResults) {
        if (openLibraryBookDataService.isEmpty()) {
            return Flux.empty();
        }
        String query = SearchExternalProviderUtils.normalizeExternalQuery(request.query());
        if (!StringUtils.hasText(query) || SearchQueryUtils.isWildcard(query) || maxResults <= 0) {
            return Flux.empty();
        }

        OpenLibraryBookDataService service = openLibraryBookDataService.get();
        Set<String> seenKeys = ConcurrentHashMap.newKeySet();
        return service.queryBooksByEverything(query, request.orderBy(), startIndex, maxResults)
            .onErrorResume(ex -> {
                log.warn("Open Library fallback failed for '{}': {}", request.query(), ex.getMessage());
                return Flux.empty();
            })
            .filter(Objects::nonNull)
            .map(SearchExternalProviderUtils::tagOpenLibraryFallback)
            .filter(book -> SearchExternalProviderUtils.matchesPublishedYear(book, request.publishedYear()))
            .filter(book -> CandidateKeyResolver.resolve(book)
                .filter(seenKeys::add)
                .isPresent())
            .take(maxResults);
    }

    private SearchPage mergeFallbackResults(List<Book> fallbackBooks,
                                            SearchPage currentPage,
                                            PagingUtils.Window window,
                                            SearchRequest request) {
        List<Book> deduplicatedCandidates = deduplicateCandidates(fallbackBooks);
        if (deduplicatedCandidates.isEmpty()) {
            return currentPage;
        }

        List<Book> netNewCandidates = filterNetNewCandidates(deduplicatedCandidates, currentPage.uniqueResults());
        List<Book> metadataRefreshCandidates = filterMetadataRefreshCandidates(deduplicatedCandidates, currentPage.uniqueResults());
        if (!metadataRefreshCandidates.isEmpty()) {
            searchCandidatePersistence.persist(metadataRefreshCandidates, "SEARCH_METADATA_REFRESH");
        }

        netNewCandidates = netNewCandidates.stream()
            .filter(candidate -> !metadataRefreshCandidates.contains(candidate))
            .toList();
        if (!netNewCandidates.isEmpty()) {
            searchCandidatePersistence.persist(netNewCandidates, "SEARCH");
        }

        List<Book> mergedCandidates = new ArrayList<>(currentPage.uniqueResults().size() + deduplicatedCandidates.size());
        mergedCandidates.addAll(currentPage.uniqueResults());
        mergedCandidates.addAll(deduplicatedCandidates);

        return searchPageAssembler.buildPage(
            request.query(),
            request.orderBy(),
            request.coverSource(),
            request.resolutionPreference(),
            deduplicateCandidates(mergedCandidates),
            window
        );
    }

    private List<Book> deduplicateCandidates(List<Book> fallbackBooks) {
        if (fallbackBooks == null || fallbackBooks.isEmpty()) {
            return List.of();
        }
        Map<String, Book> uniqueCandidates = new LinkedHashMap<>();
        for (Book candidate : fallbackBooks) {
            if (candidate == null) {
                continue;
            }
            CandidateKeyResolver.resolve(candidate)
                .ifPresent(key -> uniqueCandidates.putIfAbsent(key, candidate));
        }
        return List.copyOf(uniqueCandidates.values());
    }

    private List<Book> filterNetNewCandidates(List<Book> candidates, List<Book> existingResults) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> existingKeys = ConcurrentHashMap.newKeySet();
        if (existingResults != null) {
            for (Book existing : existingResults) {
                CandidateKeyResolver.resolve(existing).ifPresent(existingKeys::add);
            }
        }

        List<Book> netNew = new ArrayList<>();
        for (Book candidate : candidates) {
            CandidateKeyResolver.resolve(candidate)
                .filter(key -> !existingKeys.contains(key))
                .ifPresent(key -> {
                    existingKeys.add(key);
                    netNew.add(candidate);
                });
        }
        return List.copyOf(netNew);
    }

    private boolean hasCoverGap(SearchPage page, int pageSize) {
        if (page == null || page.pageItems() == null || page.pageItems().isEmpty()) {
            return true;
        }
        int inspected = Math.min(Math.max(pageSize, 0), page.pageItems().size());
        if (inspected == 0) {
            return true;
        }

        long coveredCount = page.pageItems().stream()
            .limit(inspected)
            .filter(this::hasRenderableCover)
            .count();
        return coveredCount < inspected;
    }

    private boolean hasMetadataGap(SearchPage page, int pageSize) {
        if (page == null || page.pageItems() == null || page.pageItems().isEmpty()) {
            return true;
        }
        int inspected = Math.min(Math.max(pageSize, 0), page.pageItems().size());
        if (inspected == 0) {
            return true;
        }

        long fullyDescribedCount = page.pageItems().stream()
            .limit(inspected)
            .filter(book -> book != null)
            .filter(book -> StringUtils.hasText(book.getDescription()))
            .filter(book -> book.getPageCount() != null && book.getPageCount() > 0)
            .count();
        return fullyDescribedCount < inspected;
    }

    private List<Book> filterMetadataRefreshCandidates(List<Book> candidates, List<Book> existingResults) {
        if (candidates == null || candidates.isEmpty() || existingResults == null || existingResults.isEmpty()) {
            return List.of();
        }

        Map<String, Book> existingByFingerprint = new LinkedHashMap<>();
        for (Book existing : existingResults) {
            String fingerprint = contentFingerprint(existing);
            if (StringUtils.hasText(fingerprint)) {
                existingByFingerprint.putIfAbsent(fingerprint, existing);
            }
        }

        if (existingByFingerprint.isEmpty()) {
            return List.of();
        }

        List<Book> refreshCandidates = new ArrayList<>();
        for (Book candidate : candidates) {
            String fingerprint = contentFingerprint(candidate);
            if (!StringUtils.hasText(fingerprint)) {
                continue;
            }
            Book existing = existingByFingerprint.get(fingerprint);
            if (existing == null) {
                continue;
            }
            if (candidateImprovesMetadata(candidate, existing)) {
                refreshCandidates.add(candidate);
            }
        }
        return List.copyOf(refreshCandidates);
    }

    private boolean candidateImprovesMetadata(Book candidate, Book existing) {
        if (candidate == null || existing == null) {
            return false;
        }

        boolean improvesDescription = StringUtils.hasText(candidate.getDescription())
            && (!StringUtils.hasText(existing.getDescription())
            || candidate.getDescription().length() > existing.getDescription().length());

        boolean improvesPageCount = candidate.getPageCount() != null
            && candidate.getPageCount() > 0
            && (existing.getPageCount() == null || existing.getPageCount() <= 0);

        boolean improvesPublisher = StringUtils.hasText(candidate.getPublisher())
            && !StringUtils.hasText(existing.getPublisher());

        boolean improvesLanguage = StringUtils.hasText(candidate.getLanguage())
            && !StringUtils.hasText(existing.getLanguage());

        boolean improvesCover = hasRenderableCover(candidate) && !hasRenderableCover(existing);

        return improvesDescription || improvesPageCount || improvesPublisher || improvesLanguage || improvesCover;
    }

    private String contentFingerprint(Book book) {
        if (book == null) {
            return null;
        }
        String normalizedTitle = normalizeForFingerprint(book.getTitle());
        if (!StringUtils.hasText(normalizedTitle)) {
            return null;
        }
        String firstAuthor = (book.getAuthors() == null || book.getAuthors().isEmpty())
            ? ""
            : normalizeForFingerprint(book.getAuthors().getFirst());
        return normalizedTitle + "|" + firstAuthor;
    }

    private String normalizeForFingerprint(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private boolean hasRenderableCover(Book book) {
        if (book == null) {
            return false;
        }
        return CoverQuality.rank(
            book.getS3ImagePath(),
            book.getExternalImageUrl(),
            book.getCoverImageWidth(),
            book.getCoverImageHeight(),
            book.getIsCoverHighResolution()
        ) > 0;
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
     *
     * @param query normalized search text (non-null)
     * @param startIndex zero-based absolute offset into the result window (not a one-based page number)
     * @param maxResults requested page size for this response window
     * @param orderBy normalized sort key used by internal/external providers
     * @param coverSource preferred cover source filter
     * @param resolutionPreference preferred cover resolution filter
     * @param publishedYear optional publication-year filter
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
            orderBy = SearchExternalProviderUtils.normalizeOrderBy(orderBy);
            coverSource = Optional.ofNullable(coverSource).orElse(CoverImageSource.ANY);
            resolutionPreference = Optional.ofNullable(resolutionPreference).orElse(ImageResolutionPreference.ANY);
            publishedYear = publishedYear != null && publishedYear > 0 ? publishedYear : null;
        }
    }

    /**
     * Immutable paginated response payload for search endpoints.
     *
     * @param query normalized search text
     * @param startIndex zero-based absolute offset echoed from the request
     * @param maxResults effective page size used for this response
     * @param totalRequested internal fetch window size (includes prefetch room)
     * @param totalUnique total unique candidates after filtering/deduplication
     * @param pageItems current page slice (derived from startIndex/maxResults)
     * @param uniqueResults full unique candidate window used to produce pageItems
     * @param hasMore whether another page exists after this slice
     * @param nextStartIndex next zero-based absolute offset when hasMore is true
     * @param prefetchedCount number of extra candidates already fetched beyond pageItems
     * @param orderBy normalized ordering key applied by the assembler
     * @param coverSource effective cover source filter applied by the assembler
     * @param resolutionPreference effective resolution preference applied by the assembler
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
