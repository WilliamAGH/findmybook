/**
 * Test suite for HomeController web endpoints
 * 
 * @author William Callahan
 */
package net.findmybook.controller;

import net.findmybook.dto.BookCard;
import net.findmybook.model.Book;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.SearchPaginationService;
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
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.anyString; // For mocking getSimilarBooks
import static org.mockito.ArgumentMatchers.anyInt; // For mocking getSimilarBooks
import static org.mockito.ArgumentMatchers.eq; // For mocking specific values
import reactor.core.publisher.Mono; // For mocking reactive service
@WebFluxTest(value = {HomeController.class, BookDetailPageController.class},
    excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "spring.aop.proxy-target-class=true",
    "app.frontend.spa.enabled=false"
})
class HomeControllerTest {
    /**
     * WebTestClient for controller integration testing
     */
    @Autowired
    private WebTestClient webTestClient;
    
    @org.springframework.beans.factory.annotation.Autowired
    private net.findmybook.service.image.LocalDiskCoverCacheService localDiskCoverCacheService;

    @org.springframework.beans.factory.annotation.Autowired
    private SearchPaginationService searchPaginationService;

    @org.springframework.beans.factory.annotation.Autowired
    private HomePageSectionsService homePageSectionsService;

    @TestConfiguration
    static class MocksConfig {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager(); }
        @Bean net.findmybook.service.image.LocalDiskCoverCacheService localDiskCoverCacheService() { return Mockito.mock(net.findmybook.service.image.LocalDiskCoverCacheService.class); }
        @Bean HomePageSectionsService homePageSectionsService() { return Mockito.mock(HomePageSectionsService.class); }
        @Bean BookSeoMetadataService bookSeoMetadataService(net.findmybook.service.image.LocalDiskCoverCacheService localDiskCoverCacheService) {
            return new BookSeoMetadataService(localDiskCoverCacheService);
        }
        @Bean net.findmybook.service.EnvironmentService environmentService() { return Mockito.mock(net.findmybook.service.EnvironmentService.class); }
        @Bean net.findmybook.service.AffiliateLinkService affiliateLinkService() { return Mockito.mock(net.findmybook.service.AffiliateLinkService.class); }
        @Bean SearchPaginationService searchPaginationService() { return Mockito.mock(SearchPaginationService.class); }
    }
    
    /**
     * Sets up common test fixtures
     * Configures mock services with default behaviors
     */
    @BeforeEach
    void setUp() {
        when(homePageSectionsService.loadCurrentBestsellers(anyInt()))
            .thenReturn(Mono.just(java.util.Collections.emptyList()));
        when(homePageSectionsService.loadRecentBooks(anyInt()))
            .thenReturn(Mono.just(java.util.Collections.emptyList()));
        when(homePageSectionsService.loadSimilarBooks(anyString(), anyInt(), anyInt()))
            .thenReturn(Mono.just(java.util.Collections.emptyList()));
        when(homePageSectionsService.locateBook(anyString()))
            .thenReturn(Mono.empty());
        doNothing().when(homePageSectionsService).recordRecentlyViewed(org.mockito.ArgumentMatchers.any(Book.class));

        when(localDiskCoverCacheService.getLocalPlaceholderPath()).thenReturn(ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH);

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

        when(homePageSectionsService.locateBook(eq(sanitizedIsbn))).thenReturn(Mono.just(canonicalBook));

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
        when(homePageSectionsService.loadCurrentBestsellers(eq(8)))
            .thenReturn(Mono.just(bestsellerCards));

        // Create recent card with same UUID
        String recentBookUuid = "550e8400-e29b-41d4-a716-446655440001";
        BookCard recentCardWithUuid = createBookCard(
            recentBookUuid,
            "recent-read-slug",
            "Recent Read",
            "http://example.com/cover/recent1.jpg",
            "s3://covers/" + recentBookUuid + ".jpg",
            4.0,
            50
        );
        when(homePageSectionsService.loadRecentBooks(eq(8)))
            .thenReturn(Mono.just(List.of(recentCardWithUuid)));

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
        when(homePageSectionsService.loadCurrentBestsellers(eq(8)))
            .thenReturn(Mono.error(new RuntimeException("simulated bestseller fetch failure")));
        // Act & Assert
        webTestClient.get().uri("/")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().is5xxServerError();
    }
}
