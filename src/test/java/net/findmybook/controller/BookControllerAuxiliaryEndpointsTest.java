package net.findmybook.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.cover.CoverUrlResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused tests for related-book, cover, and author-search API endpoints.
 */
class BookControllerAuxiliaryEndpointsTest extends AbstractBookControllerMvcTest {

    @Test
    @DisplayName("GET /api/books/{id}/similar returns cached DTOs")
    void should_ReturnCachedDtos_When_SimilarBooksRequested() throws Exception {
        UUID bookUuid = UUID.fromString(fixtureBook.getId());
        when(bookIdentifierResolver.resolveToUuid(fixtureBook.getSlug()))
            .thenReturn(Optional.of(bookUuid));

        BookCard card = new BookCard(
            fixtureBook.getId(),
            fixtureBook.getSlug(),
            fixtureBook.getTitle(),
            fixtureBook.getAuthors(),
            fixtureBook.getCoverImages().getPreferredUrl(),
            fixtureBook.getS3ImagePath(),
            fixtureBook.getCoverImages().getFallbackUrl(),
            4.7,
            321,
            Map.<String, Object>of("reason", Map.<String, Object>of("type", "AUTHOR"))
        );
        List<RecommendationCard> cards = List.of(new RecommendationCard(card, 0.9, "AUTHOR", "SAME_AUTHOR"));
        when(bookSearchService.fetchRecommendationCards(bookUuid, 3)).thenReturn(cards);

        performAsync(get("/api/books/" + fixtureBook.getSlug() + "/similar")
            .param("limit", "3"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id", equalTo(fixtureBook.getId())));
    }

    @Test
    @DisplayName("GET /api/books/{id}/similar returns 404 when canonical lookup fails")
    void should_Return404_When_CanonicalLookupFails() throws Exception {
        when(bookIdentifierResolver.resolveToUuid("unknown"))
            .thenReturn(Optional.empty());

        performAsync(get("/api/books/unknown/similar"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/covers/{id} uses orchestrator fallback when repository misses")
    void should_FallBackToOrchestrator_When_RepositoryMisses() throws Exception {
        stubRepositoryMiss("orchestrator-id");
        var fallback = buildBook(UUID.randomUUID().toString(), "fallback-book");
        when(bookDataOrchestrator.fetchCanonicalBookReactive("orchestrator-id"))
            .thenReturn(Mono.just(fallback));

        CoverUrlResolver.ResolvedCover expectedCover = CoverUrlResolver.resolve(
            fallback.getS3ImagePath(),
            fallback.getExternalImageUrl(),
            fallback.getCoverImageWidth(),
            fallback.getCoverImageHeight(),
            fallback.getIsCoverHighResolution()
        );

        mockMvc.perform(get("/api/covers/orchestrator-id"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.preferredUrl", equalTo(expectedCover.url())))
            .andExpect(jsonPath("$.coverUrl", equalTo(expectedCover.url())))
            .andExpect(jsonPath("$.requestedSourcePreference", equalTo("ANY")));
    }

    @Test
    @DisplayName("GET /api/covers/{id} returns 500 when orchestrator lookup fails")
    void should_ReturnServerError_When_OrchestratorFails() throws Exception {
        stubRepositoryMiss("orchestrator-error");
        when(bookDataOrchestrator.fetchCanonicalBookReactive("orchestrator-error"))
            .thenReturn(Mono.error(new RuntimeException("downstream-failure")));

        mockMvc.perform(get("/api/covers/orchestrator-error"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("GET /api/covers/{id} resolves via repository detail first")
    void should_UseRepositoryFirst_When_CoverRequested() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));

        CoverUrlResolver.ResolvedCover expectedCover = CoverUrlResolver.resolve(
            detail.coverUrl(),
            detail.thumbnailUrl(),
            detail.coverWidth(),
            detail.coverHeight(),
            detail.coverHighResolution()
        );

        mockMvc.perform(get("/api/covers/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.coverUrl", equalTo(expectedCover.url())))
            .andExpect(jsonPath("$.preferredUrl", equalTo(expectedCover.url())));
    }

    @Test
    @DisplayName("GET /api/books/authors/search returns author results")
    void should_ReturnAuthorResults_When_QueryMatches() throws Exception {
        List<BookSearchService.AuthorResult> results = List.of(
            new BookSearchService.AuthorResult("author-1", "Fixture Author", 12, 0.98)
        );
        when(bookSearchService.searchAuthors(eq("Fixture"), anyInt())).thenReturn(results);

        performAsync(get("/api/books/authors/search")
            .param("query", "Fixture")
            .param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.query", equalTo("Fixture")))
            .andExpect(jsonPath("$.results", hasSize(1)))
            .andExpect(jsonPath("$.results[0].id", containsString("author-1")));
    }
}
