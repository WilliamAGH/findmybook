package net.findmybook.service;

import com.fasterxml.jackson.databind.JsonNode;
import net.findmybook.dto.BookListItem;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.ValidationUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import net.findmybook.util.cover.ImageDimensionUtils;
import net.findmybook.util.cover.UrlSourceDetector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Coordinates paginated search using repository-backed DTO projections.
 * Executes postgres-first search with deterministic ordering and deduplication.
 */
@Service
@Slf4j
public class SearchPaginationService {

    private final BookSearchService bookSearchService;
    private final BookQueryRepository bookQueryRepository;
    private final Optional<GoogleApiFetcher> googleApiFetcher;
    private final Optional<GoogleBooksMapper> googleBooksMapper;

    public SearchPaginationService(BookSearchService bookSearchService,
                                   BookQueryRepository bookQueryRepository,
                                   Optional<GoogleApiFetcher> googleApiFetcher,
                                   Optional<GoogleBooksMapper> googleBooksMapper) {
        this.bookSearchService = bookSearchService;
        this.bookQueryRepository = bookQueryRepository;
        this.googleApiFetcher = googleApiFetcher != null ? googleApiFetcher : Optional.empty();
        this.googleBooksMapper = googleBooksMapper != null ? googleBooksMapper : Optional.empty();
    }

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
            .map(results -> results == null ? List.<BookSearchService.SearchResult>of() : results)
            .map(this::mapPostgresResults)
            .map(list -> dedupeAndSlice(list, window, request))
            .flatMap(page -> maybeFallback(request, window, startNanos, page))
            .doOnNext(page -> logPageMetrics(request, window, page, startNanos));
    }

    private List<Book> mapPostgresResults(List<BookSearchService.SearchResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        List<UUID> bookIds = results.stream()
            .map(BookSearchService.SearchResult::bookId)
            .filter(Objects::nonNull)
            .toList();
        if (bookIds.isEmpty()) {
            return List.of();
        }
        List<BookListItem> items = bookQueryRepository.fetchBookListItems(bookIds);
        Map<String, BookListItem> itemsById = items.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(BookListItem::id, item -> item, (first, second) -> first, LinkedHashMap::new));
        List<Book> ordered = new ArrayList<>(bookIds.size());
        for (BookSearchService.SearchResult result : results) {
            if (result.bookId() == null) {
                continue;
            }
            BookListItem item = itemsById.get(result.bookId().toString());
            if (item == null) {
                continue;
            }
            Book book = BookDomainMapper.fromListItem(item);
            if (book == null) {
                continue;
            }
            book.addQualifier("search.matchType", result.matchTypeNormalized());
            book.addQualifier("search.relevanceScore", result.relevanceScore());
            book.addQualifier("search.editionCount", result.editionCount());
            if (result.clusterId() != null) {
                book.addQualifier("search.clusterId", result.clusterId().toString());
            }
            ordered.add(book);
        }
        return ordered;
    }

    private SearchPage dedupeAndSlice(List<Book> rawResults,
                                      PagingUtils.Window window,
                                      SearchRequest request) {
        LinkedHashMap<String, Book> ordered = new LinkedHashMap<>();
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        int position = 0;
        for (Book book : rawResults) {
            if (book == null) {
                continue;
            }
            String id = book.getId();
            if (!ValidationUtils.hasText(id)) {
                continue;
            }
            if (!ordered.containsKey(id)) {
                ordered.put(id, book);
                insertionOrder.put(id, position++);
            }
        }

        List<Book> uniqueResults = new ArrayList<>(ordered.values());
        List<Book> filtered = applyCoverPreferences(uniqueResults, request.coverSource(), request.resolutionPreference());

        filtered.sort(buildSearchResultComparator(insertionOrder, request));
        List<Book> pageItems = PagingUtils.slice(filtered, window.startIndex(), window.limit());
        int totalUnique = filtered.size();
        boolean hasMore = PagingUtils.hasMore(totalUnique, window.startIndex(), window.limit());
        int prefetched = PagingUtils.prefetchedCount(totalUnique, window.startIndex(), window.limit());
        int nextStartIndex = hasMore ? window.startIndex() + window.limit() : window.startIndex();

        return new SearchPage(
            request.query(),
            window.startIndex(),
            window.limit(),
            window.totalRequested(),
            totalUnique,
            pageItems,
            filtered,
            hasMore,
            nextStartIndex,
            prefetched,
            request.orderBy(),
            request.coverSource(),
            request.resolutionPreference()
        );
    }

    /**
     * Attempts to backfill empty search results using the Google Books tier while respecting all
     * runtime feature flags and rate-limit guards. Authenticated calls are always attempted first
     * and the unauthenticated fallback only runs when the shared {@link ApiCircuitBreakerService}
     * reports that the public quota is still available.
     */
    private Mono<SearchPage> maybeFallback(SearchRequest request,
                                           PagingUtils.Window window,
                                           long startNanos,
                                           SearchPage currentPage) {
        boolean shouldFallback = currentPage.totalUnique() == 0
            && googleApiFetcher.isPresent()
            && googleBooksMapper.isPresent()
            && !SearchQueryUtils.isWildcard(request.query())
            && window.totalRequested() > 0;

        if (!shouldFallback) {
            return Mono.just(currentPage);
        }

        GoogleApiFetcher fetcher = googleApiFetcher.get();
        GoogleBooksMapper mapper = googleBooksMapper.get();
        int desired = window.totalRequested();

        Flux<JsonNode> authenticated = fetcher.streamSearchItems(request.query(), desired, request.orderBy(), null, true)
            .onErrorResume(ex -> {
                log.warn("Authenticated Google fallback failed for '{}': {}", request.query(), ex.getMessage());
                return Flux.empty();
            });

        Flux<JsonNode> unauthenticated = fetcher.isFallbackAllowed()
            ? fetcher.streamSearchItems(request.query(), desired, request.orderBy(), null, false)
                .onErrorResume(ex -> {
                    log.warn("Unauthenticated Google fallback failed for '{}': {}", request.query(), ex.getMessage());
                    return Flux.empty();
                })
            : Flux.defer(() -> {
                log.info("Skipping unauthenticated Google fallback for '{}' because fallback circuit is open", request.query());
                return Flux.empty();
            });

        return Flux.concat(authenticated, unauthenticated)
            .map(mapper::map)
            .filter(Objects::nonNull)
            .map(BookDomainMapper::fromAggregate)
            .filter(Objects::nonNull)
            .filter(book -> ValidationUtils.hasText(book.getId()))
            .map(book -> {
                book.addQualifier("search.source", "EXTERNAL_FALLBACK");
                book.addQualifier("search.matchType", "GOOGLE_API");
                return book;
            })
            .take(desired)
            .collectList()
            .map(fallbackBooks -> fallbackBooks.isEmpty() ? currentPage : dedupeAndSlice(fallbackBooks, window, request))
            .onErrorResume(ex -> {
                log.warn("Fallback search processing failed for '{}': {}", request.query(), ex.getMessage());
                return Mono.just(currentPage);
            });
    }

    private Comparator<Book> buildSearchResultComparator(Map<String, Integer> insertionOrder,
                                                         SearchRequest request) {
        if (request == null || request.orderBy() == null) {
            return CoverPrioritizer.bookComparator(insertionOrder, null);
        }

        String orderBy = request.orderBy().toLowerCase(Locale.ROOT);
        Comparator<Book> orderSpecific = switch (orderBy) {
            case "newest" -> Comparator
                .comparing(Book::getPublishedDate, Comparator.nullsLast(java.util.Date::compareTo))
                .reversed();
            case "title" -> Comparator.comparing(
                book -> Optional.ofNullable(book.getTitle()).orElse(""),
                String.CASE_INSENSITIVE_ORDER
            );
            case "author" -> Comparator.comparing(
                book -> {
                    if (book == null || book.getAuthors() == null || book.getAuthors().isEmpty()) {
                        return "";
                    }
                    return Optional.ofNullable(book.getAuthors().get(0)).orElse("");
                },
                String.CASE_INSENSITIVE_ORDER
            );
            case "rating" -> Comparator
                .comparing((Book book) -> Optional.ofNullable(book.getAverageRating()).orElse(0.0), Comparator.reverseOrder())
                .thenComparing(book -> Optional.ofNullable(book.getRatingsCount()).orElse(0), Comparator.reverseOrder());
            case "cover-quality", "quality", "relevance" -> null;
            default -> null;
        };
        return CoverPrioritizer.bookComparator(insertionOrder, orderSpecific);
    }

    private List<Book> applyCoverPreferences(List<Book> books,
                                             CoverImageSource coverSource,
                                             ImageResolutionPreference resolutionPreference) {
        if (books.isEmpty()) {
            return books;
        }

        CoverImageSource effectiveSource = coverSource == null ? CoverImageSource.ANY : coverSource;
        ImageResolutionPreference effectiveResolution = resolutionPreference == null
            ? ImageResolutionPreference.ANY
            : resolutionPreference;

        return books.stream()
            .filter(book -> matchesSourcePreference(book, effectiveSource))
            .filter(book -> matchesResolutionPreference(book, effectiveResolution))
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean isCoverSuppressed(Book book) {
        if (book == null || book.getQualifiers() == null) {
            return false;
        }
        Object suppressed = book.getQualifiers().get("cover.suppressed");
        if (suppressed instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (suppressed instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    private boolean matchesSourcePreference(Book book, CoverImageSource preference) {
        if (preference == null || preference == CoverImageSource.ANY) {
            return true;
        }

        CoverImageSource source = null;
        if (book.getCoverImages() != null) {
            source = book.getCoverImages().getSource();
        }

        if (source == null || source == CoverImageSource.UNDEFINED || source == CoverImageSource.ANY) {
            source = UrlSourceDetector.detectSource(book.getExternalImageUrl());
        }

        return source == preference;
    }

    private boolean matchesResolutionPreference(Book book, ImageResolutionPreference preference) {
        if (preference == null || preference == ImageResolutionPreference.ANY || preference == ImageResolutionPreference.HIGH_FIRST) {
            return true;
        }

        Integer width = book.getCoverImageWidth();
        Integer height = book.getCoverImageHeight();
        boolean highResolution = Boolean.TRUE.equals(book.getIsCoverHighResolution())
            || ImageDimensionUtils.isHighResolution(width, height);

        return switch (preference) {
            case HIGH_ONLY -> highResolution;
            case LARGE -> ImageDimensionUtils.meetsThreshold(width, height, ImageDimensionUtils.MIN_ACCEPTABLE_NON_GOOGLE);
            case MEDIUM -> ImageDimensionUtils.meetsThreshold(width, height, ImageDimensionUtils.MIN_ACCEPTABLE_CACHED);
            case SMALL -> width != null && height != null
                && width < ImageDimensionUtils.MIN_ACCEPTABLE_CACHED
                && height < ImageDimensionUtils.MIN_ACCEPTABLE_CACHED;
            case ORIGINAL -> highResolution;
            case UNKNOWN -> false;
            case ANY, HIGH_FIRST -> true;
        };
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

    public record SearchRequest(String query,
                                int startIndex,
                                int maxResults,
                                String orderBy,
                                CoverImageSource coverSource,
                                ImageResolutionPreference resolutionPreference) {
        public SearchRequest {
            Objects.requireNonNull(query, "query");
            orderBy = Optional.ofNullable(orderBy).orElse("newest");
            coverSource = Optional.ofNullable(coverSource).orElse(CoverImageSource.ANY);
            resolutionPreference = Optional.ofNullable(resolutionPreference).orElse(ImageResolutionPreference.ANY);
        }
    }

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
