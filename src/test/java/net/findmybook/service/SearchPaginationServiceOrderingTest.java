package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class SearchPaginationServiceOrderingTest extends AbstractSearchPaginationServiceTest {

    @Test
    @DisplayName("search() keeps non-color results behind color covers even when orderBy=newest")
    void searchNewestOrderingStillDemotesNonColorCovers() {
        UUID newerLowQualityId = UUID.randomUUID();
        UUID olderHighQualityId = UUID.randomUUID();

        when(bookSearchService.searchBooks("cover-newest", 24)).thenReturn(List.of(
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

        when(bookSearchService.searchBooks("suppressed", 24)).thenReturn(List.of(
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

        when(bookSearchService.searchBooks("cover-null-values", 24)).thenReturn(List.of(
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

        when(bookSearchService.searchBooks("grayscale-priority", 24)).thenReturn(List.of(
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

        when(bookSearchService.searchBooks("john grisham", 4)).thenReturn(List.of(
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
