package net.findmybook.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.application.book.BookDetailResponseUseCase;
import net.findmybook.application.book.RecommendationCardResponseUseCase;
import net.findmybook.application.book.SimilarBooksResponseUseCase;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.application.cover.BookCoverResolutionService;
import net.findmybook.application.cover.BrowserCoverIngestUseCase;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.EditionSummary;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.CoverImages;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.RecommendationService;
import net.findmybook.service.RecentlyViewedService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.service.image.CoverUrlSafetyValidator;
import net.findmybook.service.image.ImageProcessingService;
import net.findmybook.service.image.S3BookCoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/**
 * Shared MVC test support for book controller endpoint tests.
 *
 * <p>This base keeps controller wiring and fixture helpers in one place so
 * endpoint-focused tests can stay small and single-purpose.
 */
@ExtendWith(MockitoExtension.class)
abstract class AbstractBookControllerMvcTest {

    @Mock
    protected BookSearchService bookSearchService;

    @Mock
    protected BookIdentifierResolver bookIdentifierResolver;

    @Mock
    protected BookDataOrchestrator bookDataOrchestrator;

    @Mock
    protected SearchPaginationService searchPaginationService;

    @Mock
    protected RecommendationService recommendationService;

    @Mock
    protected BookAiContentService bookAiContentService;

    @Mock
    protected RecentlyViewedService recentlyViewedService;

    @Mock
    protected S3BookCoverService s3BookCoverService;

    @Mock
    protected ImageProcessingService imageProcessingService;

    @Mock
    protected CoverPersistenceService coverPersistenceService;

    @Mock
    protected CoverUrlSafetyValidator coverUrlSafetyValidator;

    protected MockMvc mockMvc;
    protected Book fixtureBook;

    @BeforeEach
    void setUpBookControllerSlice() {
        BookDetailResponseUseCase bookDetailResponseUseCase =
            new BookDetailResponseUseCase(bookAiContentService, recentlyViewedService);
        RecommendationCardResponseUseCase recommendationCardResponseUseCase =
            new RecommendationCardResponseUseCase();
        SimilarBooksResponseUseCase similarBooksResponseUseCase =
            new SimilarBooksResponseUseCase(bookSearchService, recommendationCardResponseUseCase, recommendationService);
        BookController.BookControllerServices services = new BookController.BookControllerServices(
            bookSearchService,
            bookIdentifierResolver,
            searchPaginationService,
            bookDetailResponseUseCase,
            similarBooksResponseUseCase,
            bookDataOrchestrator
        );
        BookController bookController = new BookController(services);
        BookCoverResolutionService bookCoverResolutionService = new BookCoverResolutionService(
            bookSearchService,
            bookIdentifierResolver,
            Optional.of(bookDataOrchestrator)
        );
        BrowserCoverIngestUseCase browserCoverIngestUseCase = new BrowserCoverIngestUseCase(
            bookCoverResolutionService,
            s3BookCoverService,
            imageProcessingService,
            coverPersistenceService,
            coverUrlSafetyValidator
        );
        BookCoverController bookCoverController = new BookCoverController(
            bookCoverResolutionService,
            browserCoverIngestUseCase
        );

        mockMvc = MockMvcBuilders.standaloneSetup(bookController, bookCoverController).build();
        fixtureBook = buildBook("11111111-1111-4111-8111-111111111111", "fixture-book-of-secrets");

        lenient().when(bookDataOrchestrator.fetchCanonicalBookReactive(any()))
            .thenReturn(Mono.empty());
        lenient().when(bookDataOrchestrator.getBookFromDatabaseBySlug(any()))
            .thenReturn(Optional.empty());
        lenient().when(bookDataOrchestrator.getBookFromDatabase(any()))
            .thenReturn(Optional.empty());
        lenient().when(bookSearchService.fetchBookEditions(any(UUID.class)))
            .thenReturn(List.of());
        lenient().when(bookSearchService.hasActiveRecommendationCards(any(UUID.class)))
            .thenReturn(true);
        lenient().when(bookAiContentService.findCurrent(any(UUID.class)))
            .thenReturn(Optional.empty());
        lenient().when(recommendationService.regenerateSimilarBooks(any(), anyInt()))
            .thenReturn(Mono.just(List.of()));
    }

    protected Book buildBook(String id, String slug) {
        Book book = new Book();
        book.setId(id);
        book.setTitle("Fixture Title");
        book.setSlug(slug);
        book.setDescription("Fixture Description");
        book.setAuthors(List.of("Fixture Author"));
        book.setCategories(List.of("NYT Fiction"));
        book.setLanguage("en");
        book.setPageCount(320);
        book.setPublisher("Fixture Publisher");
        book.setS3ImagePath(null);
        book.setExternalImageUrl("https://example.test/cover/" + id + ".jpg");
        book.setCoverImageWidth(600);
        book.setCoverImageHeight(900);
        book.setIsCoverHighResolution(Boolean.TRUE);
        book.setCoverImages(new CoverImages(
            "https://cdn.test/preferred/" + id + ".jpg",
            "https://cdn.test/fallback/" + id + ".jpg",
            CoverImageSource.GOOGLE_BOOKS));
        book.setQualifiers(new java.util.HashMap<>(Map.<String, Object>of(
            "nytBestseller",
            Map.<String, Object>of("rank", 1)
        )));
        book.setCachedRecommendationIds(List.of("rec-1", "rec-2"));
        book.setPublishedDate(Date.from(Instant.parse("2020-01-01T00:00:00Z")));
        book.setDataSource("POSTGRES");
        return book;
    }

    protected BookDetail buildDetailFromBook(Book book) {
        Map<String, Object> tags = Map.<String, Object>of(
            "nytBestseller",
            Map.<String, Object>of("rank", 1)
        );

        String preferredCover = book.getCoverImages().getPreferredUrl();
        String fallbackCover = book.getCoverImages().getFallbackUrl();

        return new BookDetail(
            book.getId(),
            book.getSlug(),
            book.getTitle(),
            book.getDescription(),
            book.getPublisher(),
            LocalDate.of(2024, 1, 1),
            book.getLanguage(),
            book.getPageCount(),
            book.getAuthors(),
            book.getCategories(),
            preferredCover,
            book.getS3ImagePath(),
            fallbackCover,
            fallbackCover,
            book.getCoverImageWidth(),
            book.getCoverImageHeight(),
            book.getIsCoverHighResolution(),
            book.getDataSource(),
            4.6,
            87,
            "1234567890",
            "1234567890123",
            "https://preview",
            "https://info",
            tags,
            List.<EditionSummary>of()
        );
    }

    protected void stubRepositoryMiss(String identifier) {
        lenient().when(bookSearchService.fetchBookDetailBySlug(identifier)).thenReturn(Optional.empty());
        lenient().when(bookIdentifierResolver.resolveCanonicalId(identifier)).thenReturn(Optional.empty());
        lenient().when(bookIdentifierResolver.resolveToUuid(identifier)).thenReturn(Optional.empty());
    }

    protected ResultActions performAsync(MockHttpServletRequestBuilder builder) throws Exception {
        ResultActions initial = mockMvc.perform(builder);
        MvcResult mvcResult = initial.andReturn();
        if (mvcResult.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(mvcResult));
        }
        return initial;
    }
}
