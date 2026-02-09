package net.findmybook.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import net.findmybook.model.Book;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Date;

@WebFluxTest(value = {HomeController.class, BookDetailPageController.class},
    excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration.class)
@TestPropertySource(properties = "app.feature.year-filtering.enabled=true")
class HomeControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private HomePageSectionsService homePageSectionsService;

    @Autowired
    private BookSeoMetadataService bookSeoMetadataService;

    @TestConfiguration
    static class MocksConfig {
        @Bean
        HomePageSectionsService homePageSectionsService() {
            return Mockito.mock(HomePageSectionsService.class);
        }

        @Bean
        LocalDiskCoverCacheService localDiskCoverCacheService() {
            return Mockito.mock(LocalDiskCoverCacheService.class);
        }

        @Bean
        BookSeoMetadataService bookSeoMetadataService(LocalDiskCoverCacheService localDiskCoverCacheService) {
            return new BookSeoMetadataService(localDiskCoverCacheService);
        }

        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }

    @BeforeEach
    void setUp() {
        when(homePageSectionsService.locateBook(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void should_ReturnSpaShell_When_HomeRouteRequested() {
        webTestClient.get().uri("/")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("<div id=\"app\"></div>"));
                assertTrue(body.contains("<title>Discover Books | findmybook</title>"));
            });
    }

    @Test
    void should_ReturnSpaShell_When_SearchRouteRequested() {
        webTestClient.get().uri("/search?query=dune")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("<title>Search Books | findmybook</title>"));
                assertTrue(body.contains("<meta name=\"robots\" content=\"noindex, follow, max-image-preview:large\">"));
            });
    }

    @Test
    void should_RedirectWithYearParameter_When_QueryContainsYearAndYearFilteringEnabled() {
        webTestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/search")
                .queryParam("query", "dune 2020")
                .queryParam("orderBy", "title")
                .build())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
            .expectHeader().value("Location", location -> {
                assertTrue(location.contains("year=2020"));
                assertTrue(location.contains("query=dune"));
            });
    }

    @Test
    void should_ReturnSpaShell_When_ExploreRouteRequested() {
        webTestClient.get().uri("/explore")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("<title>Explore Books | findmybook</title>")));
    }

    @Test
    void should_ReturnSpaShell_When_CategoriesRouteRequested() {
        webTestClient.get().uri("/categories")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> assertTrue(body.contains("<title>Browse Genres | findmybook</title>")));
    }

    @Test
    void should_RedirectToCanonicalSlug_When_BookIdentifierIsNotCanonical() {
        Book canonical = new Book();
        canonical.setId("book-id");
        canonical.setSlug("canonical-book");
        when(homePageSectionsService.locateBook("book-id")).thenReturn(Mono.just(canonical));

        webTestClient.get().uri("/book/book-id?query=foo&page=2&orderBy=newest&view=list")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
            .expectHeader().valueEquals("Location", "/book/canonical-book?query=foo&page=2&orderBy=newest&view=list");
    }

    @Test
    void should_Return404SpaShell_When_BookIsMissing() {
        when(homePageSectionsService.locateBook("missing-book")).thenReturn(Mono.empty());

        webTestClient.get().uri("/book/missing-book")
            .exchange()
            .expectStatus().isNotFound()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class)
            .value(body -> {
                assertTrue(body.contains("<title>Page Not Found | findmybook</title>"));
                assertTrue(body.contains("<meta name=\"robots\" content=\"noindex, nofollow, noarchive\">"));
            });
    }

    @Test
    void should_EscapeScriptSensitiveCharacters_When_BookPathContainsHtmlLikeSegment() {
        String html = bookSeoMetadataService.renderSpaShell(
            bookSeoMetadataService.notFoundMetadata("/book/<svg>")
        );

        assertTrue(!html.contains("window.__FMB_INITIAL_CONTEXT__"));
        assertTrue(!html.contains("<svg>"));
    }

    @Test
    void should_RenderBookStructuredDataAndBookOpenGraph_When_BookMetadataProvided() {
        Book book = new Book();
        book.setId("book-id");
        book.setSlug("the-catcher-in-the-rye");
        book.setTitle("The Catcher in the Rye");
        book.setDescription("A classic novel about teenage rebellion and alienation in 1950s New York City.");
        book.setAuthors(java.util.List.of("J.D. Salinger"));
        book.setPublisher("Little, Brown and Company");
        book.setCategories(java.util.List.of("Fiction", "Literary Fiction"));
        book.setLanguage("en");
        book.setIsbn13("9780316769488");
        book.setPageCount(214);
        book.setPublishedDate(Date.from(Instant.parse("1951-07-16T00:00:00Z")));

        BookSeoMetadataService.SeoMetadata metadata = bookSeoMetadataService.bookMetadata(book, 170);
        String html = bookSeoMetadataService.renderSpaShell(metadata);

        assertTrue(html.contains("<meta property=\"og:type\" content=\"book\">"));
        assertTrue(html.contains("<meta property=\"book:isbn\" content=\"9780316769488\">"));
        assertTrue(html.contains("<meta property=\"book:release_date\" content=\"1951-07-16\">"));
        assertTrue(html.contains("<meta property=\"book:tag\" content=\"Literary Fiction\">"));
        assertTrue(html.contains("\"@type\":\"Book\""));
        assertTrue(html.contains("\"isbn\":\"9780316769488\""));
        assertTrue(html.contains("\"numberOfPages\":214"));
        assertTrue(html.contains("\"name\":\"J.D. Salinger\""));
        assertTrue(!html.contains("\"isbn13\":"));
    }

    @Test
    void should_GenerateRouteSpecificSitemapMetadata_When_CanonicalPathIncludesViewBucketAndPage() {
        BookSeoMetadataService.SeoMetadata metadata = bookSeoMetadataService.sitemapMetadata("/sitemap/books/B/3");

        assertTrue(metadata.title().equals("Books Sitemap: B Page 3"));
        assertTrue(metadata.description().contains("books indexed under B on page 3"));
        assertTrue(metadata.canonicalUrl().equals("https://findmybook.net/sitemap/books/B/3"));
    }

    @Test
    void should_CanonicalizeNotFoundMetadataTo404Route_When_RequestPathIsUnknown() {
        BookSeoMetadataService.SeoMetadata metadata = bookSeoMetadataService.notFoundMetadata("/book/missing-book");

        assertTrue(metadata.canonicalUrl().equals("https://findmybook.net/404"));
        assertTrue(metadata.robots().equals("noindex, nofollow, noarchive"));
    }

    @Test
    void should_RedirectToCanonicalBook_When_IsbnLookupFindsBook() {
        Book canonical = new Book();
        canonical.setId("123e4567-e89b-12d3-a456-426614174000");
        canonical.setSlug("canonical-slug");
        when(homePageSectionsService.locateBook("9780590353427")).thenReturn(Mono.just(canonical));

        webTestClient.get().uri("/book/isbn/978-0590353427")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
            .expectHeader().valueEquals("Location", "/book/canonical-slug");
    }
}
