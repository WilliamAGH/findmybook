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
import static org.mockito.Mockito.times;
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
    @DisplayName("search() removes unclustered Postgres rows with the same normalized title and author")
    void should_DeduplicateTitleAuthorMatches_When_PostgresRowsAreUnclustered() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(firstId, 1.0, "FULLTEXT"),
            new BookSearchService.SearchResult(secondId, 0.99, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                firstId,
                "Sycamore Row",
                List.of("by John Grisham", "John Grisham"),
                600,
                900,
                true,
                "https://example.test/sycamore-row-primary.jpg"
            ),
            buildListItem(
                secondId,
                "Sycamore Row",
                List.of("John Grisham"),
                326,
                495,
                false,
                "https://example.test/sycamore-row-duplicate.jpg"
            )
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("john grisham", 0, 12, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly(firstId.toString());
    }

    @Test
    @DisplayName("search() keeps one ordered snapshot while repository results mutate between pages")
    void should_KeepStableSnapshot_When_RepositoryMutatesBetweenPages() {
        List<UUID> bookIds = new ArrayList<>();
        List<BookSearchService.SearchResult> searchResults = new ArrayList<>();
        List<BookListItem> listItems = new ArrayList<>();

        for (int index = 0; index < 29; index++) {
            UUID id = UUID.randomUUID();
            bookIds.add(id);
            searchResults.add(new BookSearchService.SearchResult(id, 0.9 - (index * 0.01), "TSVECTOR"));
            listItems.add(buildListItem(id, String.format("Book %02d", index)));
        }

        when(bookSearchService.searchBooks("java", 24)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(listItems);

        SearchPaginationService.SearchPage firstPage = service.search(searchRequest("java", 0, 12, "newest")).block();

        searchResults.clear();
        listItems.clear();
        UUID replacementId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        searchResults.add(new BookSearchService.SearchResult(replacementId, 1.0, "TSVECTOR"));
        listItems.add(buildListItem(replacementId, "Repository Mutation"));

        SearchPaginationService.SearchPage secondPage = service.search(searchRequest("java", 12, 12, "newest")).block();

        assertThat(firstPage).isNotNull();
        assertThat(secondPage).isNotNull();
        assertThat(firstPage.totalUnique()).isEqualTo(29);
        assertThat(secondPage.totalUnique()).isEqualTo(firstPage.totalUnique());
        assertThat(secondPage.pageItems()).hasSize(12);
        assertThat(secondPage.pageItems())
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
        assertThat(secondPage.pageItems()).doesNotContainAnyElementsOf(firstPage.pageItems());
        assertThat(secondPage.hasMore()).isTrue();
        assertThat(secondPage.prefetchedCount()).isEqualTo(5);
        assertThat(secondPage.nextStartIndex()).isEqualTo(24);
        verify(bookSearchService, times(1)).searchBooks("java", 24);
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

        when(bookSearchService.searchBooks("miss", 24)).thenReturn(results);
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
    @DisplayName("search() shares one fallback snapshot across the first and second pages")
    void should_NotRerunFallback_When_SecondPageUsesCachedSnapshot() {
        UUID postgresOnlyId = UUID.randomUUID();

        when(bookSearchService.searchBooks("spring boot", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresOnlyId, 0.99, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(postgresOnlyId, "Postgres Only")
        ));

        List<Book> openLibraryCandidates = new ArrayList<>();
        for (int index = 1; index <= 40; index++) {
            openLibraryCandidates.add(buildOpenLibraryCandidate("OL-PAGE-" + index, "Open Candidate " + index));
        }
        when(openLibraryBookDataService.queryBooksByEverything(eq("spring boot"), anyString(), eq(0), eq(24)))
            .thenReturn(Flux.fromIterable(openLibraryCandidates));

        SearchPaginationService pageSupplementService = fallbackEnabledService();
        SearchPaginationService.SearchPage firstPage = pageSupplementService
            .search(searchRequest("spring boot", 0, 12, "newest"))
            .block();
        SearchPaginationService.SearchPage secondPage = pageSupplementService
            .search(searchRequest("spring boot", 12, 12, "newest"))
            .block();

        assertThat(firstPage).isNotNull();
        assertThat(secondPage).isNotNull();
        assertThat(firstPage.totalUnique()).isEqualTo(secondPage.totalUnique());
        assertThat(secondPage.pageItems()).hasSize(12);
        assertThat(secondPage.pageItems()).doesNotContainAnyElementsOf(firstPage.pageItems());
        verify(openLibraryBookDataService, times(1))
            .queryBooksByEverything("spring boot", "newest", 0, 24);
    }

    @Test
    @DisplayName("search() isolates snapshots by page size and publication-year filter")
    void should_LoadDistinctSnapshots_When_PageSizeOrFilterDiffers() {
        UUID baselineId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID pageSizeId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID publishedYearId = UUID.fromString("00000000-0000-0000-0000-000000000103");

        when(bookSearchService.searchBooks("snapshot boundary", 24))
            .thenReturn(List.of(new BookSearchService.SearchResult(baselineId, 0.9, "TSVECTOR")))
            .thenReturn(List.of(new BookSearchService.SearchResult(publishedYearId, 0.95, "TSVECTOR")));
        when(bookSearchService.searchBooks("snapshot boundary", 12)).thenReturn(
            List.of(new BookSearchService.SearchResult(pageSizeId, 0.92, "TSVECTOR"))
        );
        when(bookQueryRepository.fetchPublishedYears(anyList())).thenReturn(Map.of(publishedYearId, 2024));
        when(bookQueryRepository.fetchBookListItems(anyList()))
            .thenReturn(List.of(buildListItem(baselineId, "Baseline Snapshot")))
            .thenReturn(List.of(buildListItem(pageSizeId, "Page Size Snapshot")))
            .thenReturn(List.of(buildListItem(publishedYearId, "Filtered Snapshot")));

        SearchPaginationService.SearchPage baseline = service
            .search(searchRequest("snapshot boundary", 0, 12, "newest"))
            .block();
        SearchPaginationService.SearchPage differentPageSize = service
            .search(searchRequest("snapshot boundary", 0, 6, "newest"))
            .block();
        SearchPaginationService.SearchPage differentFilter = service
            .search(searchRequest("snapshot boundary", 0, 12, "newest", 2024))
            .block();

        assertThat(baseline).isNotNull();
        assertThat(differentPageSize).isNotNull();
        assertThat(differentFilter).isNotNull();
        assertThat(baseline.pageItems()).extracting(Book::getId).containsExactly(baselineId.toString());
        assertThat(differentPageSize.pageItems()).extracting(Book::getId).containsExactly(pageSizeId.toString());
        assertThat(differentFilter.pageItems()).extracting(Book::getId).containsExactly(publishedYearId.toString());
        verify(bookSearchService, times(2)).searchBooks("snapshot boundary", 24);
        verify(bookSearchService, times(1)).searchBooks("snapshot boundary", 12);
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
