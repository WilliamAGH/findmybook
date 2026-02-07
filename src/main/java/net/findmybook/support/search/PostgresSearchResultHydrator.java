package net.findmybook.support.search;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.BookDomainMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates postgres search hits into ordered domain books and applies year constraints.
 *
 * <p>Search services delegate to this class to keep repository hydration and search-hit tagging
 * consistent while reducing service-level orchestration complexity.</p>
 */
public final class PostgresSearchResultHydrator {

    private final BookQueryRepository bookQueryRepository;

    /**
     * Creates a hydrator for postgres-backed search results.
     *
     * @param bookQueryRepository repository used for book list-item projections
     */
    public PostgresSearchResultHydrator(BookQueryRepository bookQueryRepository) {
        this.bookQueryRepository = Objects.requireNonNull(bookQueryRepository, "bookQueryRepository");
    }

    /**
     * Filters search results by published year using repository-backed year metadata.
     *
     * @param results raw search results
     * @param publishedYear optional year to filter by
     * @return filtered results preserving source ordering
     */
    public List<BookSearchService.SearchResult> filterByPublishedYear(List<BookSearchService.SearchResult> results,
                                                                      Integer publishedYear) {
        if (results == null || results.isEmpty() || publishedYear == null) {
            return results == null ? List.of() : results;
        }

        List<UUID> ids = results.stream()
            .map(BookSearchService.SearchResult::bookId)
            .filter(Objects::nonNull)
            .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> yearsByBookId = bookQueryRepository.fetchPublishedYears(ids);
        if (yearsByBookId.isEmpty()) {
            return List.of();
        }

        return results.stream()
            .filter(result -> result != null && result.bookId() != null)
            .filter(result -> Objects.equals(yearsByBookId.get(result.bookId()), publishedYear))
            .toList();
    }

    /**
     * Hydrates ordered domain books from search result identifiers.
     *
     * @param results search hits in relevance order
     * @return hydrated books with search qualifiers attached
     */
    public List<Book> mapOrderedResults(List<BookSearchService.SearchResult> results) {
        if (results == null || results.isEmpty()) {
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
            UUID bookId = result.bookId();
            if (bookId == null) {
                continue;
            }
            BookListItem item = itemsById.get(bookId.toString());
            if (item == null) {
                continue;
            }

            Book book = BookDomainMapper.fromListItem(item);
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
}
