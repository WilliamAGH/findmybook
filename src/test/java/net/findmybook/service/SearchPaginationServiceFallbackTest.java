package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.model.Book;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("fallback", 24, "newest", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-1", "Fallback Title")));

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
        when(googleBooksMapper.map(argThat(node -> "google-vol-1".equals(node.path("id").asString(""))))).thenReturn(aggregate);

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
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(openLibraryBookDataService.queryBooksByEverything(eq("fallback"), anyString(), eq(0), eq(2)))
            .thenReturn(Flux.just(openLibraryOne, openLibraryTwo));

        SearchPaginationService openLibraryPrimaryService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(searchRequest("fallback", 0, 1, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-PRIMARY-1");
        verify(openLibraryBookDataService).queryBooksByEverything("fallback", "newest", 0, 2);
        verify(googleApiFetcher, never())
            .streamSearchItems(anyString(), anyInt(), anyString(), any(), anyBoolean());
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
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems("fallback", 4, "newest", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-2", "Google Secondary")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-2".equals(node.path("id").asString("")))))
            .thenReturn(googleAggregate("GOOGLE-SECONDARY-1", "Google Secondary", "https://example.test/google-secondary.jpg"));

        SearchPaginationService openLibraryPrimaryService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(searchRequest("fallback", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactlyInAnyOrder("OL-PRIMARY-1", "GOOGLE-SECONDARY-1");
        verify(googleApiFetcher, times(1)).streamSearchItems("fallback", 4, "newest", null, true);
    }

    @Test
    @DisplayName("search() deduplicates Open Library ISBN-10 and Google ISBN-13 fallback rows")
    void should_DeduplicateExternalFallbackRows_When_IsbnFormatsAreEquivalent() {
        Book openLibraryCandidate = buildOpenLibraryCandidate("OL-ISBN-10", "Provider Primary Title");
        openLibraryCandidate.setAuthors(List.of("Open Library Author"));
        openLibraryCandidate.setIsbn10("0061120081");

        BookAggregate googleCandidate = BookAggregate.builder()
            .title("Provider Secondary Title")
            .authors(List.of("Google Author"))
            .isbn13("9780061120084")
            .slugBase("provider-secondary-title")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("google-vol-isbn")
                .imageLinks(Map.of())
                .build())
            .build();

        when(bookSearchService.searchBooks("0061120081", 4)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("0061120081"), anyString(), eq(0), eq(4)))
            .thenReturn(Flux.just(openLibraryCandidate));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems("0061120081", 4, "newest", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-isbn", "Provider Secondary Title")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-isbn".equals(node.path("id").asString("")))))
            .thenReturn(googleCandidate);

        SearchPaginationService fallbackService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = fallbackService.search(searchRequest("0061120081", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-ISBN-10");
        verify(googleApiFetcher, times(1)).streamSearchItems("0061120081", 4, "newest", null, true);
    }

    @Test
    @DisplayName("search() deduplicates fallback rows when one provider lacks ISBN metadata")
    void should_DeduplicateExternalFallbackRows_When_OneProviderOnlyHasTitleAuthorIdentity() {
        Book openLibraryCandidate = buildOpenLibraryCandidate("OL-TITLE-AUTHOR", "To Kill a Mockingbird");
        openLibraryCandidate.setAuthors(List.of("Harper Lee"));
        openLibraryCandidate.setIsbn10("0061120081");

        BookAggregate googleCandidate = BookAggregate.builder()
            .title("To Kill a Mockingbird")
            .authors(List.of("Harper Lee"))
            .slugBase("to-kill-a-mockingbird-harper-lee")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("google-vol-title-author")
                .imageLinks(Map.of())
                .build())
            .build();

        when(bookSearchService.searchBooks("0061120081", 4)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("0061120081"), anyString(), eq(0), eq(4)))
            .thenReturn(Flux.just(openLibraryCandidate));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems("0061120081", 4, "newest", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-title-author", "To Kill a Mockingbird")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-title-author".equals(node.path("id").asString("")))))
            .thenReturn(googleCandidate);

        SearchPaginationService fallbackService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = fallbackService.search(searchRequest("0061120081", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-TITLE-AUTHOR");
        verify(googleApiFetcher, times(1)).streamSearchItems("0061120081", 4, "newest", null, true);
    }

    @Test
    @DisplayName("search() preserves fallback editions when title-author matches but ISBN differs")
    void should_PreserveFallbackRows_When_TitleAuthorMatchesButIsbnDiffers() {
        Book openLibraryCandidate = buildOpenLibraryCandidate("OL-DIFFERENT-ISBN", "Same Title");
        openLibraryCandidate.setAuthors(List.of("Shared Author"));
        openLibraryCandidate.setIsbn13("9780306406157");

        BookAggregate googleCandidate = BookAggregate.builder()
            .title("Same Title")
            .authors(List.of("Shared Author"))
            .isbn13("9780132350884")
            .slugBase("same-title-shared-author")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("google-vol-different-isbn")
                .imageLinks(Map.of())
                .build())
            .build();

        when(bookSearchService.searchBooks("same title", 4)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("same title"), anyString(), eq(0), eq(4)))
            .thenReturn(Flux.just(openLibraryCandidate));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems("same title", 4, "newest", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-different-isbn", "Same Title")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-different-isbn".equals(node.path("id").asString("")))))
            .thenReturn(googleCandidate);

        SearchPaginationService fallbackService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = fallbackService.search(searchRequest("same title", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly("OL-DIFFERENT-ISBN", "google-vol-different-isbn");
    }

    @Test
    @DisplayName("search() does not add Open Library fallback duplicates for persisted title-author matches")
    void should_DeduplicateOpenLibraryFallback_When_PersistedBookMatchesTitleAndAuthor() {
        UUID postgresId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 4)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresId, 1.0, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                postgresId,
                "The Widow",
                List.of("by John Grisham", "John Grisham"),
                600,
                900,
                true,
                "https://example.test/the-widow-postgres.jpg"
            )
        ));

        Book duplicateFallback = buildOpenLibraryCandidate("OL44328974W", "The Widow");
        duplicateFallback.setAuthors(List.of("John Grisham"));
        Book distinctFallback = buildOpenLibraryCandidate("OL37836170W", "Camino Ghosts");
        distinctFallback.setAuthors(List.of("John Grisham"));
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString(), eq(0), eq(4)))
            .thenReturn(Flux.just(duplicateFallback, distinctFallback));

        SearchPaginationService fallbackService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = fallbackService.search(searchRequest("john grisham", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(postgresId.toString(), "OL37836170W");
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
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
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
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems("fallback", 4, "newest", null, true))
            .thenReturn(Flux.error(new IllegalStateException("rate limited")));

        SearchPaginationService openLibraryPrimaryService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(searchRequest("fallback", 0, 2, "newest")).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-PRIMARY-1");
    }

    @Test
    @DisplayName("search() supplements an underfilled Postgres page and keeps non-color covers last")
    void should_MergeOpenLibraryCandidates_When_PostgresPageIsUnderfilled() {
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
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString(), eq(0), eq(6)))
            .thenReturn(Flux.just(openLibraryCandidate));

        SearchPaginationService augmentingService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = augmentingService.search(searchRequest("john grisham", 0, 3, "relevance")).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId)
            .containsExactly(postgresCoveredId.toString(), "OL-OPEN-1", postgresSuppressedId.toString());
        verify(googleApiFetcher, times(0)).streamSearchItems(anyString(), anyInt(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("search() returns Postgres page without waiting for metadata refresh")
    void should_ReturnPostgresPageWithoutWaiting_When_MetadataRefreshDoesNotComplete() {
        UUID postgresId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 2)).thenReturn(List.of(
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

        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString()))
            .thenReturn(Flux.never());

        SearchPaginationService metadataRefreshingService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = metadataRefreshingService
            .search(searchRequest("john grisham", 0, 1, "relevance"))
            .block(Duration.ofSeconds(1));

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly(postgresId.toString());
        verify(openLibraryBookDataService).queryBooksByEverything("john grisham", "relevance");
        verify(openLibraryBookDataService, never())
            .queryBooksByEverything("john grisham", "relevance", 0, 2);
        verify(bookDataOrchestrator, never()).persistBooksAsync(anyList(), anyString());
        verify(googleApiFetcher, times(0)).streamSearchItems(anyString(), anyInt(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("search() uses unauthenticated Google fallback when API key is unavailable")
    void should_UseUnauthenticatedGoogleFallback_When_ApiKeyMissing() {
        when(bookSearchService.searchBooks("distributed systems", 24)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString(), eq(0), eq(24)))
            .thenReturn(Flux.empty());

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(false);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("distributed systems", 24, "relevance", null, false))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-unauth", "Pragmatic Distributed Systems")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-unauth".equals(node.path("id").asString("")))))
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
