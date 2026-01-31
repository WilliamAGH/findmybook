package net.findmybook.controller;

import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.EditionSummary;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.CoverImages;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.util.cover.CoverUrlResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookControllerTest {

    @Mock
    private BookSearchService bookSearchService;

    @Mock
    private BookQueryRepository bookQueryRepository;

    @Mock
    private BookIdentifierResolver bookIdentifierResolver;

    @Mock
    private BookDataOrchestrator bookDataOrchestrator;

    @Mock
    private SearchPaginationService searchPaginationService;

    private MockMvc mockMvc;

    private Book fixtureBook;

    @BeforeEach
    void setUp() {
        BookController bookController = new BookController(
            bookSearchService,
            bookQueryRepository,
            bookIdentifierResolver,
            searchPaginationService,
            bookDataOrchestrator
        );
        BookCoverController bookCoverController = new BookCoverController(
            bookQueryRepository,
            bookIdentifierResolver,
            bookDataOrchestrator
        );

        mockMvc = MockMvcBuilders.standaloneSetup(bookController, bookCoverController).build();
        fixtureBook = buildBook("11111111-1111-4111-8111-111111111111", "fixture-book-of-secrets");

        lenient().when(bookDataOrchestrator.fetchCanonicalBookReactive(any()))
            .thenReturn(Mono.empty());
        lenient().when(bookQueryRepository.fetchBookEditions(any(UUID.class)))
            .thenReturn(List.of());
    }

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
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
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
    @DisplayName("GET /api/books/{id} returns mapped DTO")
    void getBookByIdentifier_returnsDto() throws Exception {
        BookDetail detail = buildDetailFromBook(fixtureBook);
        when(bookQueryRepository.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
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
            .andExpect(jsonPath("$.tags[0].key", equalTo("nytBestseller")));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} falls back to canonical UUID when slug missing")
    void getBookByIdentifier_fallsBackToCanonicalId() throws Exception {
        when(bookQueryRepository.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.empty());
        when(bookIdentifierResolver.resolveCanonicalId(fixtureBook.getSlug()))
            .thenReturn(Optional.of(fixtureBook.getId()));

        BookDetail detail = buildDetailFromBook(fixtureBook);
        when(bookQueryRepository.fetchBookDetail(UUID.fromString(fixtureBook.getId())))
            .thenReturn(Optional.of(detail));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id", equalTo(fixtureBook.getId())))
            .andExpect(jsonPath("$.slug", equalTo(fixtureBook.getSlug())));
    }

    @Test
    @DisplayName("GET /api/books/{id} includes edition summaries from repository")
    void getBookByIdentifier_includesEditions() throws Exception {
        BookDetail detail = buildDetailFromBook(fixtureBook);
        EditionSummary summary = new EditionSummary(
            UUID.randomUUID().toString(),
            "edition-slug",
            "Fixture Hardcover",
            LocalDate.of(2023, 5, 1),
            "Fixture Publisher",
            "9781234567897",
            "https://cdn.test/edition.jpg",
            "en",
            352
        );

        when(bookQueryRepository.fetchBookDetailBySlug(fixtureBook.getSlug()))
            .thenReturn(Optional.of(detail));
        when(bookQueryRepository.fetchBookEditions(UUID.fromString(fixtureBook.getId())))
            .thenReturn(List.of(summary));

        performAsync(get("/api/books/" + fixtureBook.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.editions", hasSize(1)))
            .andExpect(jsonPath("$.editions[0].googleBooksId", equalTo(summary.id())))
            .andExpect(jsonPath("$.editions[0].isbn13", equalTo(summary.isbn13())));
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
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id", equalTo(fallback.getId())))
            .andExpect(jsonPath("$.slug", equalTo(fallbackSlug)))
            .andExpect(jsonPath("$.title", equalTo(fallback.getTitle())));
    }

    @Test
    @DisplayName("GET /api/books/{identifier} returns 404 when not found")
    void getBook_notFound() throws Exception {
        when(bookQueryRepository.fetchBookDetailBySlug("missing")).thenReturn(Optional.empty());
        when(bookIdentifierResolver.resolveCanonicalId("missing")).thenReturn(Optional.empty());

        performAsync(get("/api/books/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/books/{id}/similar returns cached DTOs")
    void getBookSimilar_returnsDtos() throws Exception {
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
        when(bookQueryRepository.fetchRecommendationCards(bookUuid, 3)).thenReturn(cards);

        performAsync(get("/api/books/" + fixtureBook.getSlug() + "/similar")
            .param("limit", "3"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id", equalTo(fixtureBook.getId())));
    }

    @Test
    @DisplayName("GET /api/books/{id}/similar returns 404 when canonical lookup fails")
    void getBookSimilar_returnsEmptyWhenMissing() throws Exception {
        when(bookIdentifierResolver.resolveToUuid("unknown"))
            .thenReturn(Optional.empty());

        performAsync(get("/api/books/unknown/similar"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/covers/{id} uses orchestrator fallback when repository misses")
    void getBookCover_fallsBackToOrchestrator() throws Exception {
        stubRepositoryMiss("orchestrator-id");
        Book fallback = buildBook(UUID.randomUUID().toString(), "fallback-book");
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
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.preferredUrl", equalTo(expectedCover.url())))
            .andExpect(jsonPath("$.coverUrl", equalTo(expectedCover.url())))
            .andExpect(jsonPath("$.requestedSourcePreference", equalTo("ANY")));
    }

    @Test
    @DisplayName("GET /api/covers/{id} resolves via repository detail first")
    void getBookCover_usesRepositoryFirst() throws Exception {
        BookDetail detail = buildDetailFromBook(fixtureBook);
        when(bookQueryRepository.fetchBookDetailBySlug(fixtureBook.getSlug()))
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
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.coverUrl", equalTo(expectedCover.url())))
            .andExpect(jsonPath("$.preferredUrl", equalTo(expectedCover.url())));
    }

    @Test
    @DisplayName("GET /api/books/authors/search returns author results")
    void searchAuthors_returnsResults() throws Exception {
        List<BookSearchService.AuthorResult> results = List.of(
            new BookSearchService.AuthorResult("author-1", "Fixture Author", 12, 0.98)
        );
        when(bookSearchService.searchAuthors(eq("Fixture"), anyInt())).thenReturn(results);

        performAsync(get("/api/books/authors/search")
            .param("query", "Fixture")
            .param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.query", equalTo("Fixture")))
            .andExpect(jsonPath("$.results", hasSize(1)))
            .andExpect(jsonPath("$.results[0].id", containsString("author-1")));
    }

    private Book buildBook(String id, String slug) {
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

    private BookDetail buildDetailFromBook(Book book) {
        Map<String, Object> tags = Map.<String, Object>of(
            "nytBestseller",
            Map.<String, Object>of("rank", 1)
        );

        String preferredCover = book.getCoverImages().getPreferredUrl();
        String fallbackCover = book.getCoverImages().getFallbackUrl();
        String thumbnail = fallbackCover;

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
            thumbnail,
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

    private void stubRepositoryMiss(String identifier) {
        lenient().when(bookQueryRepository.fetchBookDetailBySlug(identifier)).thenReturn(Optional.empty());
        lenient().when(bookIdentifierResolver.resolveCanonicalId(identifier)).thenReturn(Optional.empty());
        lenient().when(bookIdentifierResolver.resolveToUuid(identifier)).thenReturn(Optional.empty());
    }

    private ResultActions performAsync(MockHttpServletRequestBuilder builder) throws Exception {
        ResultActions initial = mockMvc.perform(builder);
        MvcResult mvcResult = initial.andReturn();
        if (mvcResult.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(mvcResult));
        }
        return initial;
    }
}
