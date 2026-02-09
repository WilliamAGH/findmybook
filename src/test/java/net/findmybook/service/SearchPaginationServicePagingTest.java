package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchPaginationServicePagingTest extends AbstractSearchPaginationServiceTest {

    @Test
    @DisplayName("search() deduplicates results, preserves Postgres ordering, and returns paginated results")
    void searchDeduplicatesAndPersistsExternal() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        List<BookSearchService.SearchResult> searchResults = List.of(
            new BookSearchService.SearchResult(firstId, 0.95, "TSVECTOR"),
            new BookSearchService.SearchResult(secondId, 0.87, "TSVECTOR"),
            new BookSearchService.SearchResult(firstId, 0.95, "TSVECTOR")
        );

        when(bookSearchService.searchBooks("java", 24)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(firstId, "Postgres One"),
            buildListItem(secondId, "Postgres Two")
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("java", 0, 12, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(firstId.toString(), secondId.toString());
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.prefetchedCount()).isZero();
        assertThat(page.nextStartIndex()).isZero();
    }

    @Test
    @DisplayName("search() slices with start offsets and computes prefetch metadata")
    void searchRespectsOffsetsAndPrefetch() {
        List<UUID> bookIds = new ArrayList<>();
        List<BookSearchService.SearchResult> searchResults = new ArrayList<>();
        List<BookListItem> listItems = new ArrayList<>();

        for (int index = 0; index < 29; index++) {
            UUID id = UUID.randomUUID();
            bookIds.add(id);
            searchResults.add(new BookSearchService.SearchResult(id, 0.9 - (index * 0.01), "TSVECTOR"));
            listItems.add(buildListItem(id, String.format("Book %02d", index)));
        }

        when(bookSearchService.searchBooks("java", 36)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(listItems);

        SearchPaginationService.SearchPage page = service.search(searchRequest("java", 12, 12, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).hasSize(12);
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(
                bookIds.get(12).toString(),
                bookIds.get(13).toString(),
                bookIds.get(14).toString(),
                bookIds.get(15).toString(),
                bookIds.get(16).toString(),
                bookIds.get(17).toString(),
                bookIds.get(18).toString(),
                bookIds.get(19).toString(),
                bookIds.get(20).toString(),
                bookIds.get(21).toString(),
                bookIds.get(22).toString(),
                bookIds.get(23).toString()
            );
        assertThat(page.hasMore()).isTrue();
        assertThat(page.prefetchedCount()).isEqualTo(5);
        assertThat(page.nextStartIndex()).isEqualTo(24);
    }

    @Test
    @DisplayName("search() handles high start indexes correctly")
    void searchPostgresOnlyHonoursHighStartIndexes() {
        SearchPaginationService postgresOnlyService = postgresOnlyService();

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();

        List<BookSearchService.SearchResult> results = List.of(
            new BookSearchService.SearchResult(firstId, 0.91, "TSVECTOR"),
            new BookSearchService.SearchResult(secondId, 0.87, "TSVECTOR"),
            new BookSearchService.SearchResult(thirdId, 0.72, "TSVECTOR")
        );

        when(bookSearchService.searchBooks("miss", 34)).thenReturn(results);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(firstId, "First"),
            buildListItem(secondId, "Second"),
            buildListItem(thirdId, "Third")
        ));

        SearchPaginationService.SearchPage page = postgresOnlyService.search(searchRequest("miss", 10, 12, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).isEmpty();
        assertThat(page.uniqueResults()).hasSize(3);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.prefetchedCount()).isZero();
        assertThat(page.nextStartIndex()).isEqualTo(10);
    }

    @Test
    @DisplayName("search() supplements startIndex>0 pages with external fallback when Postgres underfills")
    void should_SupplementSecondPage_When_PostgresPageUnderfilled() {
        UUID postgresOnlyId = UUID.randomUUID();

        when(bookSearchService.searchBooks("spring boot", 36)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresOnlyId, 0.99, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(postgresOnlyId, "Postgres Only")
        ));

        List<Book> openLibraryCandidates = new ArrayList<>();
        for (int index = 1; index <= 40; index++) {
            openLibraryCandidates.add(buildOpenLibraryCandidate("OL-PAGE-" + index, "Open Candidate " + index));
        }
        when(openLibraryBookDataService.queryBooksByEverything(eq("spring boot"), anyString(), eq(0), eq(36)))
            .thenReturn(Flux.fromIterable(openLibraryCandidates));

        SearchPaginationService pageSupplementService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = pageSupplementService.search(searchRequest("spring boot", 12, 12, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).hasSize(12);
        assertThat(page.totalUnique()).isGreaterThan(12);
        verify(openLibraryBookDataService).queryBooksByEverything("spring boot", "newest", 0, 36);
    }

    @Test
    @DisplayName("search() applies published year filtering using repository-backed year metadata")
    void searchFiltersByPublishedYear() {
        UUID matchingYearId = UUID.randomUUID();
        UUID differentYearId = UUID.randomUUID();

        when(bookSearchService.searchBooks("history", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(matchingYearId, 0.91, "FULLTEXT"),
            new BookSearchService.SearchResult(differentYearId, 0.83, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchPublishedYears(anyList())).thenReturn(Map.of(
            matchingYearId, 2024,
            differentYearId, 1999
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(matchingYearId, "Modern History")
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("history", 0, 12, "relevance", 2024)).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly(matchingYearId.toString());
    }
}
