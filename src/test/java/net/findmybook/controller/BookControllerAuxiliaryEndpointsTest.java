package net.findmybook.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.util.cover.CoverUrlResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Mono;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
        when(bookSearchService.hasActiveRecommendationCards(bookUuid))
            .thenReturn(true);

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
    @DisplayName("GET /api/books/{id}/similar regenerates recommendations when active cache is missing")
    void should_RegenerateRecommendations_When_ActiveCacheMissing() throws Exception {
        UUID bookUuid = UUID.fromString(fixtureBook.getId());
        when(bookIdentifierResolver.resolveToUuid(fixtureBook.getSlug()))
            .thenReturn(Optional.of(bookUuid));
        when(bookSearchService.hasActiveRecommendationCards(bookUuid))
            .thenReturn(false);

        Book generatedBook = buildBook("22222222-2222-4222-8222-222222222222", "generated-book");
        when(recommendationService.regenerateSimilarBooks(fixtureBook.getSlug(), 3))
            .thenReturn(Mono.just(List.of(generatedBook)));

        BookCard refreshedCard = new BookCard(
            generatedBook.getId(),
            generatedBook.getSlug(),
            generatedBook.getTitle(),
            generatedBook.getAuthors(),
            generatedBook.getCoverImages().getPreferredUrl(),
            generatedBook.getS3ImagePath(),
            generatedBook.getCoverImages().getFallbackUrl(),
            4.4,
            101,
            Map.of()
        );
        List<RecommendationCard> refreshed = List.of(
            new RecommendationCard(refreshedCard, 0.8, "AUTHOR", "RECOMMENDATION_PIPELINE")
        );
        when(bookSearchService.fetchRecommendationCards(bookUuid, 3))
            .thenReturn(List.of())
            .thenReturn(refreshed);

        performAsync(get("/api/books/" + fixtureBook.getSlug() + "/similar")
            .param("limit", "3"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id", equalTo(generatedBook.getId())));

        verify(recommendationService).regenerateSimilarBooks(fixtureBook.getSlug(), 3);
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
    @DisplayName("POST /api/covers/{id}/ingest persists browser-uploaded cover to S3 and metadata store")
    void should_PersistBrowserUploadedCover_When_IngestRequested() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));
        when(coverUrlSafetyValidator.isAllowedImageUrl(detail.coverUrl()))
            .thenReturn(true);

        byte[] processedBytes = "processed-cover".getBytes(StandardCharsets.UTF_8);
        when(imageProcessingService.processImageForS3(any(), eq(fixtureBook.getId())))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                net.findmybook.model.image.ProcessedImage.success(
                    processedBytes,
                    ".jpg",
                    "image/jpeg",
                    600,
                    900,
                    false
                )
            ));

        ImageDetails uploadedDetails = new ImageDetails(
            "https://cdn.test/images/book-covers/fixture-book.jpg",
            "GOOGLE_BOOKS",
            "fixture-book",
            CoverImageSource.GOOGLE_BOOKS,
            null,
            600,
            900
        );
        uploadedDetails.setStorageKey("images/book-covers/fixture-book-lg-google-books.jpg");
        uploadedDetails.setGrayscale(false);
        when(s3BookCoverService.uploadProcessedCoverToS3Async(any()))
            .thenReturn(Mono.just(uploadedDetails));

        UUID expectedBookId = UUID.fromString(fixtureBook.getId());
        when(coverPersistenceService.updateAfterS3Upload(eq(expectedBookId), any()))
            .thenReturn(new CoverPersistenceService.PersistenceResult(
                true,
                uploadedDetails.getUrlOrPath(),
                600,
                900,
                true
            ));

        MockMultipartFile image = new MockMultipartFile(
            "image",
            "cover.png",
            "image/png",
            "raw-image".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/covers/" + fixtureBook.getSlug() + "/ingest")
                .file(image)
                .param("sourceUrl", detail.coverUrl())
                .param("source", "GOOGLE_BOOKS"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.bookId", equalTo(fixtureBook.getId())))
            .andExpect(jsonPath("$.storedCoverUrl", equalTo(uploadedDetails.getUrlOrPath())))
            .andExpect(jsonPath("$.storageKey", equalTo(uploadedDetails.getStorageKey())))
            .andExpect(jsonPath("$.source", equalTo("GOOGLE_BOOKS")));

        verify(coverPersistenceService).updateAfterS3Upload(
            eq(expectedBookId),
            argThat(upload -> upload.source() == CoverImageSource.GOOGLE_BOOKS
                && upload.s3Key().equals(uploadedDetails.getStorageKey()))
        );
    }

    @Test
    @DisplayName("POST /api/covers/{id}/ingest rejects sourceUrl that is not the active cover candidate")
    void should_RejectBrowserUpload_When_SourceUrlDoesNotMatchBookCover() throws Exception {
        var detail = buildDetailFromBook(fixtureBook);
        when(bookSearchService.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));
        when(coverUrlSafetyValidator.isAllowedImageUrl("https://books.google.com/books/content?id=unexpected"))
            .thenReturn(true);

        MockMultipartFile image = new MockMultipartFile(
            "image",
            "cover.png",
            "image/png",
            "raw-image".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/covers/" + fixtureBook.getSlug() + "/ingest")
                .file(image)
                .param("sourceUrl", "https://books.google.com/books/content?id=unexpected")
                .param("source", "GOOGLE_BOOKS"))
            .andExpect(status().isBadRequest());
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
