package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import net.findmybook.util.SearchExternalProviderUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SearchPaginationServiceOrderingTest extends AbstractSearchPaginationServiceTest {

    @Test
    void should_UsePersistedDirectMatchWithoutProviders_When_RelevanceQueryIsResolved() {
        UUID bookId = UUID.randomUUID();
        String query = "snow crash neal stephenson";
        when(bookSearchService.searchBooks(query, SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP))
            .thenReturn(List.of(new BookSearchService.SearchResult(bookId, 1.0d, "EXACT_TITLE")));
        when(bookQueryRepository.fetchBookListItems(anyList()))
            .thenReturn(List.of(buildListItem(bookId, "Snow Crash", List.of("Neal Stephenson"),
                600, 900, true, "https://example.test/snow-crash.jpg")));

        SearchPaginationService.SearchPage page = fallbackEnabledService()
            .search(searchRequest(query, 0, 12, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly(bookId.toString());
        verify(openLibraryBookDataService, never())
            .queryBooksByEverything(anyString(), anyString(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 198})
    void should_PreserveProviderIdentityOrder_When_PersistedTitlesHaveDifferentAuthors(int unrelatedCount) {
        UUID expectedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        var searchRows = new ArrayList<>(List.of(new BookSearchService.SearchResult(otherId, 1.0d, "EXACT_TITLE"),
            new BookSearchService.SearchResult(expectedId, 1.0d, "EXACT_TITLE")));
        var books = new ArrayList<>(List.of(
            buildListItem(otherId, "Snow Crash", List.of("Prima Games"), 600, 900, true,
                "https://example.test/game.jpg"),
            buildListItem(expectedId, "Snow Crash", List.of("Neal Stephenson"), 0, 0, false, null)));
        for (int index = 0; index < unrelatedCount; index++) {
            UUID unrelatedId = UUID.randomUUID();
            searchRows.add(new BookSearchService.SearchResult(unrelatedId, 0.9d, "FULLTEXT"));
            books.add(buildListItem(unrelatedId, "Unrelated title " + index));
        }
        when(bookSearchService.searchBooks("snow crash", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP))
            .thenReturn(searchRows);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(books);
        Book expected = buildOpenLibraryCandidate("OL38501W", "Snow Crash");
        expected.setAuthors(List.of("Neal Stephenson"));
        Book other = buildOpenLibraryCandidate("OL8495480W", "Snow Crash");
        other.setAuthors(List.of("Prima Games"));
        when(openLibraryBookDataService.queryBooksByEverything(
            eq("snow crash"), anyString(), eq(0), eq(SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)))
            .thenReturn(reactor.core.publisher.Flux.just(expected, other));

        var page = fallbackEnabledService().search(searchRequest("snow crash", 0, 12, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId).startsWith(expectedId.toString(), otherId.toString());
        assertThat(page.totalUnique()).isEqualTo(unrelatedCount + 2);
    }

    @Test
    void should_ConsultPrimaryProvider_When_PunctuationDistinguishesTitles() {
        UUID unrelatedId = UUID.randomUUID();
        String query = "c# in depth";
        when(bookSearchService.searchBooks(query, SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP))
            .thenReturn(List.of(new BookSearchService.SearchResult(unrelatedId, 1.0d, "EXACT_TITLE")));
        when(bookQueryRepository.fetchBookListItems(anyList()))
            .thenReturn(List.of(buildListItem(unrelatedId, "C++ in Depth")));
        when(openLibraryBookDataService.queryBooksByEverything(
            eq(query), anyString(), eq(0), eq(SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)))
            .thenReturn(reactor.core.publisher.Flux.just(buildOpenLibraryCandidate("OL-CSHARP", "C# in Depth")));

        var page = fallbackEnabledService().search(searchRequest(query, 0, 12, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getTitle).startsWith("C# in Depth");
    }

    @Test
    @DisplayName("search() preserves the primary provider's exact-title result before unrelated cached matches")
    void should_RankCorrectTitleFirst_When_ColdProviderCandidateCompetesWithCachedNoise() {
        UUID unrelatedId = UUID.randomUUID();
        when(bookSearchService.searchBooks("snow crash", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP))
            .thenReturn(List.of(new BookSearchService.SearchResult(unrelatedId, 0.0035d, "FULLTEXT")));
        when(bookQueryRepository.fetchBookListItems(anyList()))
            .thenReturn(List.of(buildListItem(unrelatedId, "Pathfinder Roleplaying Game")));
        Book expectedBook = buildOpenLibraryCandidate("OL-NEAL", "Snow Crash");
        expectedBook.setAuthors(List.of("Neal Stephenson"));
        Book otherTitle = buildOpenLibraryCandidate("OL-GAME", "Snow Crash");
        otherTitle.setAuthors(List.of("Prima Games"));
        otherTitle.setExternalImageUrl("https://covers.openlibrary.org/b/id/1-L.jpg");
        otherTitle.setPublishedDate(java.sql.Date.valueOf("2025-01-01"));
        when(openLibraryBookDataService.queryBooksByEverything(
            eq("snow crash"), anyString(), eq(0), eq(SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)))
            .thenReturn(reactor.core.publisher.Flux.just(expectedBook, otherTitle));

        var page = fallbackEnabledService().search(searchRequest("snow crash", 0, 12, "relevance")).block();
        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId).startsWith("OL-NEAL");
    }

    @Test
    @DisplayName("search() ranks a title and author match before a high-scoring unrelated title")
    void should_RankTitleAndAuthorFirst_When_QueryIncludesBoth() {
        UUID unrelatedId = UUID.randomUUID();
        UUID expectedId = UUID.randomUUID();
        String query = "snow crash neal stephenson";
        when(bookSearchService.searchBooks(query, SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP))
            .thenReturn(List.of(new BookSearchService.SearchResult(unrelatedId, 0.99d, "FULLTEXT"),
                new BookSearchService.SearchResult(expectedId, 0.65d, "FULLTEXT")));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(unrelatedId, "A Guide to the Metaverse"),
            buildListItem(expectedId, "Snow Crash", List.of("Neal Stephenson"), 600, 900, true,
                "https://example.test/snow-crash.jpg")));
        var page = service.search(searchRequest(query, 0, 12, "relevance")).block();
        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId).startsWith(expectedId.toString());
    }

    @Test
    @DisplayName("search() defaults omitted orderBy to relevance")
    void should_OrderByRelevance_When_OrderByIsOmitted() {
        UUID higherRelevanceId = UUID.randomUUID();
        UUID lowerRelevanceId = UUID.randomUUID();

        when(bookSearchService.searchBooks("default-relevance", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)).thenReturn(List.of(
            new BookSearchService.SearchResult(higherRelevanceId, 0.95d, "TSVECTOR"),
            new BookSearchService.SearchResult(lowerRelevanceId, 0.65d, "TSVECTOR")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(lowerRelevanceId, "Lower Relevance", 600, 900, true, "https://cdn.test/lower.jpg"),
            buildListItem(higherRelevanceId, "Higher Relevance", 600, 900, true, "https://cdn.test/higher.jpg")
        ));

        SearchPaginationService.SearchPage page = service.search(
            searchRequest("default-relevance", 0, 12, null)
        ).block();

        assertThat(page).isNotNull();
        assertThat(page.orderBy()).isEqualTo(SearchExternalProviderUtils.DEFAULT_ORDER_BY);
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(higherRelevanceId.toString(), lowerRelevanceId.toString());
    }

    @Test
    @DisplayName("search() keeps non-color results behind color covers even when orderBy=newest")
    void searchNewestOrderingStillDemotesNonColorCovers() {
        UUID newerLowQualityId = UUID.randomUUID();
        UUID olderHighQualityId = UUID.randomUUID();

        when(bookSearchService.searchBooks("cover-newest", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)).thenReturn(List.of(
            new BookSearchService.SearchResult(newerLowQualityId, 0.80, "TSVECTOR"),
            new BookSearchService.SearchResult(olderHighQualityId, 0.60, "TSVECTOR")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                newerLowQualityId,
                "Newest Low Quality",
                1200,
                120,
                false,
                "https://example.test/low.jpg?w=1200&h=120",
                LocalDate.parse("2025-01-10")
            ),
            buildListItem(
                olderHighQualityId,
                "Older High Quality",
                900,
                1400,
                true,
                "https://cdn.test/high.jpg",
                LocalDate.parse("2020-06-01")
            )
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("cover-newest", 0, 12, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(olderHighQualityId.toString(), newerLowQualityId.toString());
    }

    @Test
    @DisplayName("search() retains suppressed covers but ranks them after valid covers")
    void searchKeepsSuppressedButRanksLast() {
        UUID validCoverId = UUID.randomUUID();
        UUID suppressedCoverId = UUID.randomUUID();

        when(bookSearchService.searchBooks("suppressed", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)).thenReturn(List.of(
            new BookSearchService.SearchResult(validCoverId, 0.95, "TSVECTOR"),
            new BookSearchService.SearchResult(suppressedCoverId, 0.90, "TSVECTOR")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(validCoverId, "Valid Cover", 600, 900, true, "https://cdn.test/high.jpg"),
            buildListItem(suppressedCoverId, "Too Wide", 1200, 120, false, "https://example.test/wide.jpg?w=1200&h=120")
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("suppressed", 0, 12, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(validCoverId.toString(), suppressedCoverId.toString());
        assertThat(page.pageItems().get(1).getQualifiers())
            .containsEntry("cover.suppressed", true);
        assertThat(page.pageItems().get(1).getExternalImageUrl()).isNull();
    }

    @Test
    @DisplayName("search() treats null-equivalent cover values as no-cover and ranks them last")
    void searchDemotesNullEquivalentCoverValues() {
        UUID nullEquivalentCoverId = UUID.randomUUID();
        UUID validCoverId = UUID.randomUUID();

        when(bookSearchService.searchBooks("cover-null-values", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)).thenReturn(List.of(
            new BookSearchService.SearchResult(nullEquivalentCoverId, 0.99, "TSVECTOR"),
            new BookSearchService.SearchResult(validCoverId, 0.70, "TSVECTOR")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildNullEquivalentCoverListItem(nullEquivalentCoverId, "Null Equivalent"),
            buildListItem(validCoverId, "Valid Cover", 700, 1100, true, "https://cdn.test/valid.jpg")
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("cover-null-values", 0, 12, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(validCoverId.toString(), nullEquivalentCoverId.toString());
    }

    @Test
    @DisplayName("search() ranks grayscale covers after color covers even when grayscale has higher relevance")
    void searchDemotesGrayscaleBelowColor() {
        UUID grayscaleId = UUID.randomUUID();
        UUID colorId = UUID.randomUUID();

        when(bookSearchService.searchBooks("grayscale-priority", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)).thenReturn(List.of(
            new BookSearchService.SearchResult(grayscaleId, 0.99, "TSVECTOR"),
            new BookSearchService.SearchResult(colorId, 0.70, "TSVECTOR")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            new BookListItem(
                grayscaleId.toString(),
                "slug-" + grayscaleId,
                "Grayscale Candidate",
                "grayscale description",
                List.of("Author Gray"),
                List.of("Category"),
                "https://example.test/grayscale.jpg",
                "s3://covers/grayscale.jpg",
                "https://example.test/grayscale.jpg",
                640,
                960,
                true,
                4.2,
                30,
                Map.of(),
                null,
                Boolean.TRUE
            ),
            buildListItem(
                colorId,
                "Color Candidate",
                640,
                960,
                true,
                "https://cdn.test/color.jpg"
            )
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("grayscale-priority", 0, 12, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(colorId.toString(), grayscaleId.toString());
    }

    @Test
    @DisplayName("search() demotes non-author exact-title rows for likely author queries")
    void should_DemoteExactTitleRows_When_QueryLooksLikeAuthorAndAuthorsDoNotMatch() {
        UUID noisyExactTitleId = UUID.randomUUID();
        UUID authoredWorkId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)).thenReturn(List.of(
            new BookSearchService.SearchResult(noisyExactTitleId, 1.0d, "EXACT_TITLE"),
            new BookSearchService.SearchResult(authoredWorkId, 0.99d, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                noisyExactTitleId,
                "John Grisham",
                List.of("Nancy Best"),
                600,
                900,
                true,
                "https://covers.openlibrary.org/b/id/1368891-L.jpg"
            ),
            buildListItem(
                authoredWorkId,
                "The Firm",
                List.of("John Grisham"),
                600,
                900,
                true,
                "https://covers.openlibrary.org/b/id/9330593-L.jpg"
            )
        ));

        SearchPaginationService.SearchPage page = service.search(searchRequest("john grisham", 0, 2, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId)
            .containsExactly(authoredWorkId.toString(), noisyExactTitleId.toString());
    }
}
