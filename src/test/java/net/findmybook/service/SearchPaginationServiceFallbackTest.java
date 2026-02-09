package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.model.Book;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchPaginationServiceFallbackTest extends AbstractSearchPaginationServiceTest {

    @Test
    @DisplayName("search() triggers Google API fallback when Postgres returns no matches")
    void searchInvokesFallbackWhenPostgresEmpty() {
        UUID fallbackId = UUID.randomUUID();

        when(bookSearchService.searchBooks("fallback", 24)).thenReturn(List.of());
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("fallback", 24, "newest", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-1", "Fallback Title")));
        when(googleApiFetcher.streamSearchItems("fallback", 24, "newest", null, false))
            .thenReturn(Flux.empty());
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(true);

        BookAggregate aggregate = BookAggregate.builder()
            .title("Fallback Title")
            .authors(List.of("Google Author"))
            .slugBase("fallback-title")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId(fallbackId.toString())
                .imageLinks(Map.of("thumbnail", "https://example.test/fallback.jpg"))
                .build())
            .build();
        when(googleBooksMapper.map(argThat(node -> "google-vol-1".equals(node.path("id").asText())))).thenReturn(aggregate);

        SearchPaginationService fallbackService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            java.util.Optional.of(googleApiFetcher),
            java.util.Optional.of(googleBooksMapper)
        );

        SearchPaginationService.SearchPage page = fallbackService.search(searchRequest("fallback", 0, 12, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly(fallbackId.toString());
        verify(googleApiFetcher, times(1)).streamSearchItems("fallback", 24, "newest", null, true);
    }

    @Test
    @DisplayName("search() should use Open Library as primary fallback when Postgres returns no matches")
    void should_UseOpenLibraryPrimaryFallback_When_PostgresReturnsNoMatches() {
        Book openLibraryOne = buildOpenLibraryCandidate("OL-PRIMARY-1", "Open Primary One");
        Book openLibraryTwo = buildOpenLibraryCandidate("OL-PRIMARY-2", "Open Primary Two");

        when(bookSearchService.searchBooks("fallback", 2)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("fallback"), anyString(), eq(0), eq(2)))
            .thenReturn(Flux.just(openLibraryOne, openLibraryTwo));

        SearchPaginationService openLibraryPrimaryService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(searchRequest("fallback", 0, 1, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-PRIMARY-1");
        verify(openLibraryBookDataService).queryBooksByEverything("fallback", "newest", 0, 2);
        verifyNoInteractions(googleApiFetcher);
        verifyNoInteractions(googleBooksMapper);
    }

    @Test
    @DisplayName("search() should query Google as secondary fallback when Open Library underfills")
    void should_QueryGoogleSecondaryFallback_When_OpenLibraryUnderfills() {
        Book openLibraryOnly = buildOpenLibraryCandidate("OL-PRIMARY-1", "Open Primary One");

        when(bookSearchService.searchBooks("fallback", 4)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("fallback"), anyString(), eq(0), eq(4)))
            .thenReturn(Flux.just(openLibraryOnly));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("fallback", 4, "newest", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-2", "Google Secondary")));
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);
        when(googleBooksMapper.map(argThat(node -> "google-vol-2".equals(node.path("id").asText()))))
            .thenReturn(googleAggregate("GOOGLE-SECONDARY-1", "Google Secondary", "https://example.test/google-secondary.jpg"));

        SearchPaginationService openLibraryPrimaryService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(searchRequest("fallback", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactlyInAnyOrder("OL-PRIMARY-1", "GOOGLE-SECONDARY-1");
        verify(googleApiFetcher, times(1)).streamSearchItems("fallback", 4, "newest", null, true);
    }

    @Test
    @DisplayName("search() preserves Open Library description and page count metadata in fallback results")
    void should_PreserveOpenLibraryMetadata_When_FallbackResultsReturned() {
        Book openLibraryCandidate = buildOpenLibraryCandidate("OL-PRIMARY-1", "The Partner");
        openLibraryCandidate.setDescription("Full Open Library description text");
        openLibraryCandidate.setPageCount(416);
        openLibraryCandidate.setPublisher("Doubleday");
        openLibraryCandidate.setLanguage("eng");

        when(bookSearchService.searchBooks("john grisham", 2)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString(), eq(0), eq(2)))
            .thenReturn(Flux.just(openLibraryCandidate));

        SearchPaginationService openLibraryPrimaryService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(searchRequest("john grisham", 0, 1, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).hasSize(1);
        Book first = page.pageItems().getFirst();
        assertThat(first.getDescription()).isEqualTo("Full Open Library description text");
        assertThat(first.getPageCount()).isEqualTo(416);
        assertThat(first.getPublisher()).isEqualTo("Doubleday");
        assertThat(first.getLanguage()).isEqualTo("eng");
    }

    @Test
    @DisplayName("search() should keep Open Library fallback results when Google secondary fails")
    void should_KeepOpenLibraryResults_When_GoogleSecondaryFails() {
        Book openLibraryOnly = buildOpenLibraryCandidate("OL-PRIMARY-1", "Open Primary One");

        when(bookSearchService.searchBooks("fallback", 4)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("fallback"), anyString(), eq(0), eq(4)))
            .thenReturn(Flux.just(openLibraryOnly));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("fallback", 4, "newest", null, true))
            .thenReturn(Flux.error(new IllegalStateException("rate limited")));
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);

        SearchPaginationService openLibraryPrimaryService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(searchRequest("fallback", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-PRIMARY-1");
    }

    @Test
    @DisplayName("search() should merge Open Library candidates and sort by relevance score")
    void should_MergeOpenLibraryCandidates_When_PostgresHasCoverGaps() {
        UUID postgresCoveredId = UUID.randomUUID();
        UUID postgresSuppressedId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 6)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresCoveredId, 0.98, "FULLTEXT"),
            new BookSearchService.SearchResult(postgresSuppressedId, 0.88, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(postgresCoveredId, "Postgres Covered", 600, 900, true, "https://example.test/postgres-covered.jpg"),
            buildListItem(postgresSuppressedId, "Postgres Suppressed", 1200, 120, false, "https://example.test/postgres-wide.jpg?w=1200&h=120")
        ));

        Book openLibraryCandidate = buildOpenLibraryCandidate("OL-OPEN-1", "The Firm");
        openLibraryCandidate.setExternalImageUrl("https://covers.openlibrary.org/b/id/9330593-L.jpg");
        openLibraryCandidate.setCoverImageWidth(600);
        openLibraryCandidate.setCoverImageHeight(900);
        openLibraryCandidate.setIsCoverHighResolution(true);
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString(), eq(0), eq(6)))
            .thenReturn(Flux.just(openLibraryCandidate));

        SearchPaginationService augmentingService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = augmentingService.search(searchRequest("john grisham", 0, 3, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId)
            .containsExactly(postgresSuppressedId.toString(), postgresCoveredId.toString(), "OL-OPEN-1");
        verifyNoInteractions(googleApiFetcher);
    }

    @Test
    @DisplayName("search() triggers Open Library metadata refresh when Postgres page metadata is incomplete")
    void should_TriggerMetadataRefresh_When_PostgresMissingDescriptionOrPageCount() {
        UUID postgresId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 4)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresId, 0.98, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                postgresId,
                "The Partner",
                List.of("John Grisham"),
                600,
                900,
                true,
                "https://covers.openlibrary.org/b/id/9323420-L.jpg"
            )
        ));

        Book openLibraryCandidate = buildOpenLibraryCandidate("OL77004W", "The Partner");
        openLibraryCandidate.setAuthors(List.of("John Grisham"));
        openLibraryCandidate.setDescription("A fuller Open Library description for The Partner.");
        openLibraryCandidate.setPageCount(416);
        openLibraryCandidate.setPublisher("Doubleday");
        openLibraryCandidate.setLanguage("eng");
        openLibraryCandidate.setExternalImageUrl("https://covers.openlibrary.org/b/id/9323420-L.jpg");
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString(), eq(0), eq(4)))
            .thenReturn(Flux.just(openLibraryCandidate));

        SearchPaginationService metadataRefreshingService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = metadataRefreshingService.search(searchRequest("john grisham", 0, 2, "relevance")).block();

        assertThat(page).isNotNull();
        verify(openLibraryBookDataService).queryBooksByEverything("john grisham", "relevance", 0, 4);
        verify(bookDataOrchestrator).persistBooksAsync(
            argThat(books -> books != null
                && books.size() == 1
                && "OL77004W".equals(books.getFirst().getId())
                && Integer.valueOf(416).equals(books.getFirst().getPageCount())),
            eq("SEARCH_METADATA_REFRESH")
        );
        verifyNoInteractions(googleApiFetcher);
    }

    @Test
    @DisplayName("search() uses unauthenticated Google fallback when API key is unavailable")
    void should_UseUnauthenticatedGoogleFallback_When_ApiKeyMissing() {
        when(bookSearchService.searchBooks("distributed systems", 24)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString(), eq(0), eq(24)))
            .thenReturn(Flux.empty());

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(false);
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("distributed systems", 24, "relevance", null, false))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-unauth", "Pragmatic Distributed Systems")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-unauth".equals(node.path("id").asText()))))
            .thenReturn(googleAggregate("google-vol-unauth", "Pragmatic Distributed Systems", "https://example.test/google-unauth.jpg"));

        SearchPaginationService fallbackService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = fallbackService.search(searchRequest("distributed systems", 0, 12, "author")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("google-vol-unauth");
        verify(googleApiFetcher, times(0))
            .streamSearchItems("distributed systems", 24, "relevance", null, true);
        verify(googleApiFetcher, times(1))
            .streamSearchItems("distributed systems", 24, "relevance", null, false);
    }
}
