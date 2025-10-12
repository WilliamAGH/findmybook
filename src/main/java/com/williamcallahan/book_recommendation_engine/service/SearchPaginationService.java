package com.williamcallahan.book_recommendation_engine.service;

import com.williamcallahan.book_recommendation_engine.dto.BookListItem;
import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.repository.BookQueryRepository;
import com.williamcallahan.book_recommendation_engine.util.ApplicationConstants;
import com.williamcallahan.book_recommendation_engine.util.BookDomainMapper;
import com.williamcallahan.book_recommendation_engine.util.PagingUtils;
import com.williamcallahan.book_recommendation_engine.util.ValidationUtils;
import com.williamcallahan.book_recommendation_engine.util.cover.CoverUrlResolver;
import com.williamcallahan.book_recommendation_engine.util.cover.ImageDimensionUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    public SearchPaginationService(BookSearchService bookSearchService,
                                   BookQueryRepository bookQueryRepository) {
        this.bookSearchService = bookSearchService;
        this.bookQueryRepository = bookQueryRepository;
    }

    public Mono<SearchPage> search(SearchRequest request) {
        PagingUtils.Window window = PagingUtils.window(
            request.startIndex(),
            request.maxResults(),
            ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT,
            ApplicationConstants.Paging.MIN_SEARCH_LIMIT,
            ApplicationConstants.Paging.MAX_SEARCH_LIMIT,
            ApplicationConstants.Paging.MAX_TIERED_LIMIT
        );

        // Use repository-backed DTO pattern for all searches (replaces deprecated tieredBookSearchService.streamSearch)
        return performPostgresOnlySearch(request, window);
    }

    private Mono<SearchPage> performPostgresOnlySearch(SearchRequest request, PagingUtils.Window window) {
        long start = System.nanoTime();
        return Mono.fromCallable(() -> bookSearchService.searchBooks(request.query(), window.totalRequested()))
            .subscribeOn(Schedulers.boundedElastic())
            .map(results -> results == null ? List.<BookSearchService.SearchResult>of() : results)
            .map(results -> mapPostgresResults(results))
            .map(list -> dedupeAndSlice(list, window, request))
            .doOnNext(page -> logPageMetrics(request, window, page, start));
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
            book.addQualifier("search.matchType", result.matchTypeNormalised());
            book.addQualifier("search.relevanceScore", result.relevanceScore());
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
        uniqueResults.sort(buildSearchResultComparator(insertionOrder));
        List<Book> pageItems = PagingUtils.slice(uniqueResults, window.startIndex(), window.limit());
        int totalUnique = uniqueResults.size();
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
            uniqueResults,
            hasMore,
            nextStartIndex,
            prefetched,
            request.orderBy()
        );
    }

    private Comparator<Book> buildSearchResultComparator(Map<String, Integer> insertionOrder) {
        Comparator<Book> insertionComparator = Comparator.comparingInt(
            book -> insertionOrder.getOrDefault(book.getId(), Integer.MAX_VALUE)
        );

        return Comparator
            .comparingInt(this::coverQualityRank).reversed()
            .thenComparingLong(book -> ImageDimensionUtils.totalPixels(book.getCoverImageWidth(), book.getCoverImageHeight())).reversed()
            .thenComparingInt(book -> Optional.ofNullable(book.getCoverImageHeight()).orElse(0)).reversed()
            .thenComparingInt(book -> Optional.ofNullable(book.getCoverImageWidth()).orElse(0)).reversed()
            .thenComparing((Book book) -> Boolean.TRUE.equals(book.getInPostgres()) ? 1 : 0, Comparator.reverseOrder())
            .thenComparing(insertionComparator);
    }

    private int coverQualityRank(Book book) {
        if (book == null) {
            return 0;
        }

        String externalUrl = book.getExternalImageUrl();
        boolean hasVisual = ValidationUtils.hasText(externalUrl)
            && !externalUrl.contains("placeholder-book-cover.svg");

        if (!hasVisual) {
            return 0;
        }

        Integer width = book.getCoverImageWidth();
        Integer height = book.getCoverImageHeight();
        boolean hasCdn = CoverUrlResolver.isCdnUrl(externalUrl)
            || ValidationUtils.hasText(book.getS3ImagePath());
        boolean highRes = Boolean.TRUE.equals(book.getIsCoverHighResolution())
            || ImageDimensionUtils.isHighResolution(width, height);
        boolean meetsDisplay = ImageDimensionUtils.meetsSearchDisplayThreshold(width, height);

        if (hasCdn && highRes) return 4;
        if (highRes) return 3;
        if (hasCdn || meetsDisplay) return 2;
        return 1;
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

    public record SearchRequest(String query, int startIndex, int maxResults, String orderBy) {
        public SearchRequest {
            Objects.requireNonNull(query, "query");
            orderBy = Optional.ofNullable(orderBy).orElse("newest");
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
                             String orderBy) {
    }
}
