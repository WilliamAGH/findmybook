/**
 * Test suite for HomeController web endpoints
 * 
 * @author William Callahan
 */
package net.findmybook.controller;

import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.model.Book;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.service.RecentlyViewedService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.SearchQueryUtils;
// Use fully-qualified names for image services to avoid import resolution issues in test slice
import net.findmybook.model.image.CoverImages;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString; // For mocking getSimilarBooks
import static org.mockito.ArgumentMatchers.anyInt; // For mocking getSimilarBooks
import static org.mockito.ArgumentMatchers.eq; // For mocking specific values
import reactor.core.publisher.Mono; // For mocking reactive service
import net.findmybook.service.NewYorkTimesService;
import java.util.Optional;
import java.util.UUID;
@WebFluxTest(value = HomeController.class,
    excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration.class)
@TestPropertySource(properties = "spring.aop.proxy-target-class=true")
class HomeControllerTest {
    /**
     * WebTestClient for controller integration testing
     */
    @Autowired
    private WebTestClient webTestClient;
    @org.springframework.beans.factory.annotation.Autowired
    private BookDataOrchestrator bookDataOrchestrator;

    @org.springframework.beans.factory.annotation.Autowired
    private RecentlyViewedService recentlyViewedService;
    
    @org.springframework.beans.factory.annotation.Autowired
    private net.findmybook.service.image.LocalDiskCoverCacheService localDiskCoverCacheService;
    
    @org.springframework.beans.factory.annotation.Autowired
    private NewYorkTimesService newYorkTimesService;
    
    @org.springframework.beans.factory.annotation.Autowired
    private BookSearchService bookSearchService;

    @org.springframework.beans.factory.annotation.Autowired
    private BookIdentifierResolver bookIdentifierResolver;

    @org.springframework.beans.factory.annotation.Autowired
    private SearchPaginationService searchPaginationService;

    @TestConfiguration
    static class MocksConfig {
        @Bean BookDataOrchestrator bookDataOrchestrator() { return Mockito.mock(BookDataOrchestrator.class); }
        @Bean RecentlyViewedService recentlyViewedService() { return Mockito.mock(RecentlyViewedService.class); }
        @Bean net.findmybook.service.image.LocalDiskCoverCacheService localDiskCoverCacheService() { return Mockito.mock(net.findmybook.service.image.LocalDiskCoverCacheService.class); }
        @Bean net.findmybook.service.EnvironmentService environmentService() { return Mockito.mock(net.findmybook.service.EnvironmentService.class); }
        @Bean net.findmybook.service.AffiliateLinkService affiliateLinkService() { return Mockito.mock(net.findmybook.service.AffiliateLinkService.class); }
        @Bean NewYorkTimesService newYorkTimesService() { return Mockito.mock(NewYorkTimesService.class); }
        @Bean BookSearchService bookSearchService() { return Mockito.mock(BookSearchService.class); }
        @Bean SearchPaginationService searchPaginationService() { return Mockito.mock(SearchPaginationService.class); }
        @Bean BookIdentifierResolver bookIdentifierResolver() { return Mockito.mock(BookIdentifierResolver.class); }
    }
    
    /**
     * Sets up common test fixtures
     * Configures mock services with default behaviors
     */
    @BeforeEach
    void setUp() {
        // Configure NewYorkTimesService to return empty BookCard list by default (NEW OPTIMIZED METHOD)
        when(newYorkTimesService.getCurrentBestSellersCards(anyString(), anyInt()))
            .thenReturn(Mono.just(java.util.Collections.emptyList()));
        // Configure BookSearchService projection methods to return empty results by default
        when(bookSearchService.fetchBookCards(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(java.util.Collections.emptyList());
        when(bookSearchService.fetchBookDetailBySlug(anyString())).thenReturn(Optional.empty());
        when(bookSearchService.fetchBookDetail(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(Optional.empty());

        when(bookIdentifierResolver.resolveToUuid(anyString())).thenReturn(Optional.empty());
        when(localDiskCoverCacheService.getLocalPlaceholderPath()).thenReturn(ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH);
    
        // Configure RecentlyViewedService to return empty BookCard IDs by default
        when(recentlyViewedService.getRecentlyViewedBookIds(anyInt()))
            .thenReturn(java.util.Collections.emptyList());

        when(bookDataOrchestrator.fetchCanonicalBookReactive(anyString())).thenReturn(Mono.empty());

        when(searchPaginationService.search(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(new SearchPaginationService.SearchPage(
                "",
                0,
                ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT,
                0,
                0,
                List.of(),
                List.of(),
                false,
                0,
                0,
                "newest",
                CoverImageSource.ANY,
                ImageResolutionPreference.ANY
            )));
    }

    @Test
    void searchPageDelegatesToPaginationService() {
        Book resultBook = createTestBook("11111111-1111-4111-8111-222222222222", "Delegated Result", "Author Sample");

        SearchPaginationService.SearchPage page = new SearchPaginationService.SearchPage(
            "java",
            0,
            ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT,
            ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT,
            1,
            List.of(resultBook),
            List.of(resultBook),
            false,
            ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT,
            0,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        when(searchPaginationService.search(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(page));

        webTestClient.get().uri("/search?query=java")
            .exchange()
            .expectStatus().isOk();

        ArgumentCaptor<SearchPaginationService.SearchRequest> captor = ArgumentCaptor.forClass(SearchPaginationService.SearchRequest.class);
        Mockito.verify(searchPaginationService).search(captor.capture());

        SearchPaginationService.SearchRequest captured = captor.getValue();
        assertEquals(SearchQueryUtils.normalize("java"), captured.query());
        assertEquals(0, captured.startIndex());
        assertEquals(ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT, captured.maxResults());
        assertEquals(CoverImageSource.ANY, captured.coverSource());
        assertEquals(ImageResolutionPreference.ANY, captured.resolutionPreference());
    }

    @Test
    void shouldRedirectIsbnToCanonicalSlugWhenFoundInPostgres() {
        String isbn = "978-0590353427";
        String sanitizedIsbn = "9780590353427";

        Book canonicalBook = createTestBook("123e4567-e89b-12d3-a456-426614174000", "Test Title", "Author A");
        canonicalBook.setSlug("test-title");

        UUID canonicalUuid = UUID.fromString(canonicalBook.getId());
        String preferredCover = canonicalBook.getCoverImages().getPreferredUrl();
        String fallbackCover = canonicalBook.getCoverImages().getFallbackUrl();
        String thumbnail = fallbackCover;
        BookDetail detail = new BookDetail(
            canonicalBook.getId(),
            canonicalBook.getSlug(),
            canonicalBook.getTitle(),
            canonicalBook.getDescription(),
            "Test Publisher",
            LocalDate.of(2024, 1, 1),
            "en",
            canonicalBook.getPageCount(),
            canonicalBook.getAuthors(),
            List.<String>of(),
            preferredCover,
            canonicalBook.getS3ImagePath(),
            fallbackCover,
            thumbnail,
            600,
            900,
            Boolean.TRUE,
            "POSTGRES",
            4.2,
            128,
            canonicalBook.getIsbn10(),
            sanitizedIsbn,
            "https://preview",
            "https://info",
            Map.<String, Object>of(),
            List.<net.findmybook.dto.EditionSummary>of()
        );

        when(bookIdentifierResolver.resolveToUuid(eq(sanitizedIsbn))).thenReturn(Optional.of(canonicalUuid));
        when(bookSearchService.fetchBookDetail(eq(canonicalUuid))).thenReturn(Optional.of(detail));

        webTestClient.get().uri("/book/isbn/" + isbn)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
            .expectHeader().valueEquals("Location", "/book/" + canonicalBook.getSlug());
    }

    @Test
    void shouldRedirectToNotFoundWhenIsbnMissingFromPostgresAndApis() {
        String rawIsbn = "978-0307465351";
        String sanitizedIsbn = "9780307465351";

        webTestClient.get().uri("/book/isbn/" + rawIsbn)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
            .expectHeader().valueEquals("Location", "/?info=bookNotFound&isbn=" + sanitizedIsbn);

    }

    @Test
    void exploreRedirectsToSearchWithEncodedQuery() {
        webTestClient.get().uri("/explore")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
            .expectHeader().valueMatches("Location", "/search\\?query=.*&source=explore");
    }
    /**
     * Helper method to create test Book instances
     * 
     * @param id Book identifier
     * @param title Book title
     * @param author Book authorer
     * @return Populated Book instance for testing
     */
    private Book createTestBook(String id, String title, String author) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        book.setAuthors(List.of(author));
        book.setDescription("Test description for " + title);
        String coverUrl = "http://example.com/cover/" + (id != null ? id : "new") + ".jpg";
        book.setExternalImageUrl(coverUrl);
        
        CoverImages coverImages = new CoverImages(
            coverUrl,
            coverUrl,
            CoverImageSource.UNDEFINED 
        );
        book.setCoverImages(coverImages);
        return book;
    }

    private BookCard createBookCard(String id,
                                    String slug,
                                    String title,
                                    String coverUrl,
                                    String s3Key,
                                    double averageRating,
                                    int ratingsCount) {
        return new BookCard(
            id,
            slug,
            title,
            List.of("Author"),
            coverUrl,
            s3Key,
            coverUrl,
            averageRating,
            ratingsCount,
            Map.<String, Object>of()
        );
    }
    /**
     * Tests home page with successful recommendations
     */
    @Test
    void shouldReturnHomeViewWithBestsellersAndRecentBooks() {
        // Arrange - use BookCard DTOs (THE NEW WAY)
        BookCard bestsellerCard = createBookCard(
            "bestseller1",
            "nyt-bestseller-slug",
            "NYT Bestseller",
            "http://example.com/cover/bestseller1.jpg",
            "s3://covers/bestseller1.jpg",
            4.5,
            100
        );
        List<BookCard> bestsellerCards = List.of(bestsellerCard);
        
        // Mock for bestsellers from NYT service (NEW OPTIMIZED METHOD)
        when(newYorkTimesService.getCurrentBestSellersCards(eq("hardcover-fiction"), eq(8)))
            .thenReturn(Mono.just(bestsellerCards));
        // Mock recently viewed book IDs (NEW OPTIMIZED WAY) - use proper UUIDs
        String recentBookUuid = "550e8400-e29b-41d4-a716-446655440001";
        when(recentlyViewedService.getRecentlyViewedBookIds(anyInt()))
            .thenReturn(List.of(recentBookUuid));
        
        // Create recent card with same UUID
        BookCard recentCardWithUuid = createBookCard(
            recentBookUuid,
            "recent-read-slug",
            "Recent Read",
            "http://example.com/cover/recent1.jpg",
            "s3://covers/" + recentBookUuid + ".jpg",
            4.0,
            50
        );
        
        // Mock BookSearchService projection method to return recent cards
        when(bookSearchService.fetchBookCards(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(List.of(recentCardWithUuid));

        // Act & Assert
        webTestClient.get().uri("/")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                try {
                    // Verify sections are populated (no empty alerts), not specific titles
                    assertTrue(body.contains("NYT Bestsellers"), "Response body did not contain 'NYT Bestsellers' header.\nBody:\n" + body);
                    assertFalse(body.contains("No current bestsellers to display."), "Bestsellers section unexpectedly empty.\nBody:\n" + body);
                    assertFalse(body.contains("No recent books to display."), "Recent section unexpectedly empty.\nBody:\n" + body);
                    assertTrue(body.contains("class=\"group h-full"), "Expected at least one rendered book card.\nBody:\n" + body);
                } catch (AssertionError e) {
                    System.out.println("\n\n==== DEBUG: Response Body ====");
                    System.out.println(body);
                    System.out.println("==== END RESPONSE BODY ====");
                    throw e;
                }
            });

    }
    /**
     * Tests home page with empty recommendations
     */
    @Test
    void shouldShowEmptyHomePageWhenServicesReturnEmptyLists() {
        // Default setUp mocks already return empty lists

        // Act & Assert
        webTestClient.get().uri("/")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("No current bestsellers to display."));
                assertTrue(body.contains("No recent books to display."));
            });
    }
    /**
     * Tests home page when recommendation service fails
     */
    @Test
    void shouldShowEmptyHomePageWhenServiceThrowsException() {
        // Arrange - NEW OPTIMIZED METHOD
        when(newYorkTimesService.getCurrentBestSellersCards(eq("hardcover-fiction"), eq(8)))
            .thenReturn(Mono.error(new RuntimeException("simulated bestseller fetch failure")));
        when(recentlyViewedService.getRecentlyViewedBookIds(anyInt()))
            .thenReturn(java.util.Collections.emptyList());
        // Act & Assert
        webTestClient.get().uri("/")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("No current bestsellers to display."));
                assertTrue(body.contains("No recent books to display."));
            });
    }
}
