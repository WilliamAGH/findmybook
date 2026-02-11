package net.findmybook.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.dto.EditionSummary;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.RecentBookViewRepository;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.util.cover.CoverUrlResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookControllerTest extends AbstractBookControllerMvcTest {

    @Test
    @DisplayName("GET /api/books/search returns DTO results")
    void searchBooks_returnsDtos() throws Exception {
        fixtureBook.addQualifier("search.matchType", "POSTGRES");
        fixtureBook.addQualifier("search.relevanceScore", 0.92);

        SearchPaginationService.SearchPage page = new SearchPaginationService.SearchPage(
            "Fixture",
            0,
            5,
            10,
            1,
            List.of(fixtureBook),
            List.of(fixtureBook),
            false,
            0,
            0,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        when(searchPaginationService.search(any(SearchPaginationService.SearchRequest.class)))
            .thenReturn(Mono.just(page));

        String expectedPreferred = fixtureBook.getCoverImages().getPreferredUrl();

        performAsync(get("/api/books/search")
            .param("query", "Fixture")
            .param("maxResults", "5"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.query", equalTo("Fixture")))
            .andExpect(jsonPath("$.results", hasSize(1)))
            .andExpect(jsonPath("$.results[0].id", equalTo(fixtureBook.getId())))
            .andExpect(jsonPath("$.results[0].slug", equalTo(fixtureBook.getSlug())))
            .andExpect(jsonPath("$.results[0].cover.preferredUrl", equalTo(expectedPreferred)))
            .andExpect(jsonPath("$.results[0].tags[*].key", hasItems("search.matchType", "search.relevanceScore", "nytBestseller")))
            .andExpect(jsonPath("$.results[0].tags[*].attributes.rank", hasItem(1)))
            .andExpect(jsonPath("$.results[0].matchType", equalTo("POSTGRES")))
            .andExpect(jsonPath("$.hasMore", equalTo(false)))
            .andExpect(jsonPath("$.nextStartIndex", equalTo(0)))
            .andExpect(jsonPath("$.prefetchedCount", equalTo(0)))
            .andExpect(jsonPath("$.orderBy", equalTo("newest")))
            .andExpect(jsonPath("$.coverSource", equalTo("ANY")))
            .andExpect(jsonPath("$.resolution", equalTo("ANY")));
    }

    @Test
    @DisplayName("GET /api/books/search returns 400 for unsupported orderBy values")
    void searchBooks_rejectsUnsupportedOrderBy() throws Exception {
        performAsync(get("/api/books/search")
            .param("query", "Fixture")
            .param("orderBy", "rating"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/books/search returns 500 ProblemDetail status when search service fails")
    void searchBooks_returnsInternalServerErrorWhenSearchServiceFails() throws Exception {
        when(searchPaginationService.search(any(SearchPaginationService.SearchRequest.class)))
            .thenReturn(Mono.error(new IllegalStateException("Search backend unavailable")));

        performAsync(get("/api/books/search")
            .param("query", "Fixture"))
            .andExpect(status().isInternalServerError())
            .andExpect(result -> {
                assertNotNull(result.getResolvedException());
                assertTrue(result.getResolvedException() instanceof ResponseStatusException);
                ResponseStatusException exception = (ResponseStatusException) result.getResolvedException();
                assertEquals(500, exception.getStatusCode().value());
            });
    }

    @Test
    @DisplayName("GET /api/books/{id} returns mapped DTO")
    void getBookByIdentifier_returnsDto() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.id", equalTo(fixtureBook.getId())))
            .andExpect(jsonPath("$.slug", equalTo(fixtureBook.getSlug())))
            .andExpect(jsonPath("$.authors[0].name", equalTo("Fixture Author")))
            .andExpect(jsonPath("$.cover.preferredUrl", equalTo(
                CoverUrlResolver.resolve(
                    detail.coverUrl(),
                    detail.thumbnailUrl(),
                    detail.coverWidth(),
                    detail.coverHeight(),
                    detail.coverHighResolution()
                ).url()
            )))
            .andExpect(jsonPath("$.tags[0].key", equalTo("nytBestseller")))
            .andExpect(jsonPath("$.descriptionContent.raw", equalTo("Fixture Description")))
            .andExpect(jsonPath("$.descriptionContent.format", equalTo("PLAIN_TEXT")))
            .andExpect(jsonPath("$.descriptionContent.html", equalTo("<p>Fixture Description</p>")))
            .andExpect(jsonPath("$.descriptionContent.text", equalTo("Fixture Description")));

        verify(recentlyViewedService).addToRecentlyViewed(argThat(book ->
            fixtureBook.getId().equals(book.getId())
                && fixtureBook.getSlug().equals(book.getSlug())
                && fixtureBook.getTitle().equals(book.getTitle())
        ));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} returns view metrics when window is requested")
    void getBookByIdentifier_includesViewMetricsForRequestedWindow() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));
        when(recentlyViewedService.fetchViewCount(
            fixtureBook.getId(),
            RecentBookViewRepository.ViewWindow.LAST_90_DAYS
        )).thenReturn(42L);

        performAsync(get("/api/books/" + fixtureBook.getSlug())
            .param("viewWindow", "90d"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewMetrics.window", equalTo("90d")))
            .andExpect(jsonPath("$.viewMetrics.totalViews", equalTo(42)));

        verify(recentlyViewedService).fetchViewCount(
            fixtureBook.getId(),
            RecentBookViewRepository.ViewWindow.LAST_90_DAYS
        );
    }

    @Test
    @DisplayName("GET /api/books/{identifier} rejects unsupported viewWindow")
    void getBookByIdentifier_rejectsInvalidViewWindow() throws Exception {
        performAsync(get("/api/books/" + fixtureBook.getSlug())
            .param("viewWindow", "7d"))
            .andExpect(status().isBadRequest());

        verify(recentlyViewedService, never()).addToRecentlyViewed(any(Book.class));
    }

    @Test
    @DisplayName("GET /api/books/slug/{slug} accepts viewWindow and returns view metrics")
    void getBookBySlug_includesViewMetricsForRequestedWindow() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));
        when(recentlyViewedService.fetchViewCount(
            fixtureBook.getId(),
            RecentBookViewRepository.ViewWindow.LAST_30_DAYS
        )).thenReturn(9L);

        performAsync(get("/api/books/slug/" + fixtureBook.getSlug())
            .param("viewWindow", "30d"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewMetrics.window", equalTo("30d")))
            .andExpect(jsonPath("$.viewMetrics.totalViews", equalTo(9)));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} prefers canonical Postgres detail when present")
    void getBookByIdentifier_prefersCanonicalPostgresDetail() throws Exception {
        Book canonical = buildBook(fixtureBook.getId(), fixtureBook.getSlug());
        canonical.setDescription("Canonical Description");
        canonical.setLanguage("eng");
        canonical.setPublisher("Canonical Publisher");
        canonical.setPageCount(416);

        when(bookDataOrchestrator.getBookFromDatabaseBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(canonical));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.description", equalTo("Canonical Description")))
            .andExpect(jsonPath("$.publication.language", equalTo("eng")))
            .andExpect(jsonPath("$.publication.publisher", equalTo("Canonical Publisher")))
            .andExpect(jsonPath("$.publication.pageCount", equalTo(416)));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} falls back to canonical UUID when slug missing")
    void getBookByIdentifier_fallsBackToCanonicalId() throws Exception {
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.empty());
        when(bookIdentifierResolver.resolveCanonicalId(fixtureBook.getSlug()))
            .thenReturn(Optional.of(fixtureBook.getId()));

        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetail(UUID.fromString(fixtureBook.getId())))
            .thenReturn(Optional.of(detail));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.id", equalTo(fixtureBook.getId())))
            .andExpect(jsonPath("$.slug", equalTo(fixtureBook.getSlug())));
    }

    @Test
    @DisplayName("GET /api/books/{id} includes edition summaries from repository")
    void getBookByIdentifier_includesEditions() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        EditionSummary summary = new EditionSummary(
            UUID.randomUUID().toString(),
            "edition-slug",
            "Fixture Hardcover",
            java.time.LocalDate.of(2023, 5, 1),
            "Fixture Publisher",
            "9781234567897",
            "https://cdn.test/edition.jpg",
            "en",
            352
        );

        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));
        when(bookSearchService.fetchBookEditions(UUID.fromString(fixtureBook.getId())))
            .thenReturn(List.of(summary));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.editions", hasSize(1)))
            .andExpect(jsonPath("$.editions[0].googleBooksId", equalTo(summary.id())))
            .andExpect(jsonPath("$.editions[0].isbn13", equalTo(summary.isbn13())));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} includes cached AI snapshot when present")
    void getBookByIdentifier_includesCachedAiSnapshot() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));

        BookAiContentSnapshot snapshot = new BookAiContentSnapshot(
            UUID.fromString(fixtureBook.getId()),
            2,
            Instant.parse("2026-02-08T12:00:00Z"),
            "gpt-5-mini",
            "openai",
            new BookAiContent(
                "A compact summary.",
                "Best for readers who want practical guidance.",
                List.of("Theme one", "Theme two"),
                List.of("Key insight one"),
                "Sits within the productivity genre."
            )
        );
        when(bookAiContentService.findCurrent(UUID.fromString(fixtureBook.getId())))
            .thenReturn(Optional.of(snapshot));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.aiContent.summary", equalTo("A compact summary.")))
            .andExpect(jsonPath("$.aiContent.readerFit", equalTo("Best for readers who want practical guidance.")))
            .andExpect(jsonPath("$.aiContent.keyThemes", hasSize(2)))
            .andExpect(jsonPath("$.aiContent.version", equalTo(2)))
            .andExpect(jsonPath("$.aiContent.model", equalTo("gpt-5-mini")))
            .andExpect(jsonPath("$.aiContent.provider", equalTo("openai")));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} falls back to orchestrator when Postgres misses")
    void getBookByIdentifier_usesFallbackWhenRepositoryMisses() throws Exception {
        String fallbackSlug = "fallback-book";
        stubRepositoryMiss(fallbackSlug);

        Book fallback = buildBook(UUID.randomUUID().toString(), fallbackSlug);
        when(bookDataOrchestrator.fetchCanonicalBookReactive(fallbackSlug))
            .thenReturn(Mono.just(fallback));

        performAsync(get("/api/books/" + fallbackSlug))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.id", equalTo(fallback.getId())))
            .andExpect(jsonPath("$.slug", equalTo(fallbackSlug)))
            .andExpect(jsonPath("$.title", equalTo(fallback.getTitle())));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} returns 404 when not found")
    void getBook_notFound() throws Exception {
        when(bookSearchService.fetchBookDetailBySlug("missing")).thenReturn(Optional.empty());
        when(bookIdentifierResolver.resolveCanonicalId("missing")).thenReturn(Optional.empty());

        performAsync(get("/api/books/missing"))
            .andExpect(status().isNotFound());

        verify(recentlyViewedService, never()).addToRecentlyViewed(any(Book.class));
    }

    @Test
    @DisplayName("GET /api/books/authors/search returns 500 ProblemDetail status when search service fails")
    void searchAuthors_returnsInternalServerErrorWhenSearchServiceFails() throws Exception {
        when(bookSearchService.searchAuthors("Fixture", 10))
            .thenThrow(new IllegalStateException("Author index unavailable"));

        performAsync(get("/api/books/authors/search")
            .param("query", "Fixture"))
            .andExpect(status().isInternalServerError())
            .andExpect(result -> {
                assertNotNull(result.getResolvedException());
                assertTrue(result.getResolvedException() instanceof ResponseStatusException);
                ResponseStatusException exception = (ResponseStatusException) result.getResolvedException();
                assertEquals(500, exception.getStatusCode().value());
            });
    }
}
