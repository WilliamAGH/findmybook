package net.findmybook.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.findmybook.application.page.PublicPagePayloadUseCase;
import net.findmybook.config.SitemapProperties;
import net.findmybook.dto.BookCard;
import net.findmybook.model.Book;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.PageViewEventRepository;
import net.findmybook.service.RecentBookViewRepository;
import net.findmybook.service.SitemapService;
import net.findmybook.domain.seo.OpenGraphProperty;
import net.findmybook.util.ApplicationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

@WebMvcTest(PageApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PublicPagePayloadUseCase.class)
class PageApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HomePageSectionsService homePageSectionsService;

    @MockitoBean
    private PageViewEventRepository pageViewEventRepository;

    @MockitoBean
    private SitemapService sitemapService;

    @MockitoBean
    private SitemapProperties sitemapProperties;

    @MockitoBean
    private AffiliateLinkService affiliateLinkService;

    @MockitoBean
    private BookSeoMetadataService bookSeoMetadataService;

    @MockitoBean
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        when(bookSeoMetadataService.bookRoutePattern()).thenReturn(Pattern.compile("^/book/([^/]+)$"));
        when(bookSeoMetadataService.sitemapRoutePattern()).thenReturn(Pattern.compile("^/sitemap/(authors|books)/([^/]+)/(\\d+)$"));
        when(bookSeoMetadataService.defaultSitemapPath()).thenReturn("/sitemap/authors/A/1");
        when(homePageSectionsService.loadPopularBooks(any(RecentBookViewRepository.ViewWindow.class), anyInt()))
            .thenReturn(Mono.just(List.of()));
    }

    @Test
    void shouldReturnHomePayloadWhenEndpointRequested() throws Exception {
        BookCard bestseller = new BookCard(
            "book-1",
            "book-1-slug",
            "Book One",
            List.of("Author One"),
            "https://cdn.example.com/cover.jpg",
            null,
            "https://cdn.example.com/fallback.jpg",
            4.4,
            88,
            Map.of()
        );

        when(homePageSectionsService.loadCurrentBestsellers(anyInt())).thenReturn(Mono.just(List.of(bestseller)));
        when(homePageSectionsService.loadRecentBooks(anyInt())).thenReturn(Mono.just(List.of()));

        var asyncResult = mockMvc.perform(get("/api/pages/home"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentBestsellers[0].id").value("book-1"))
            .andExpect(jsonPath("$.recentBooks").isArray());

        verify(pageViewEventRepository, times(1)).recordView(eq("homepage"), any(Instant.class), eq("api"));
    }

    @Test
    void shouldReturnPopularBooksWhenWindowRequested() throws Exception {
        BookCard bestseller = new BookCard(
            "book-best",
            "book-best-slug",
            "Book Best",
            List.of("Author Best"),
            "https://cdn.example.com/cover-best.jpg",
            null,
            "https://cdn.example.com/fallback-best.jpg",
            4.4,
            88,
            Map.of()
        );
        BookCard popular = new BookCard(
            "book-popular",
            "book-popular-slug",
            "Book Popular",
            List.of("Author Popular"),
            "https://cdn.example.com/cover-popular.jpg",
            null,
            "https://cdn.example.com/fallback-popular.jpg",
            4.8,
            240,
            Map.of()
        );

        when(homePageSectionsService.loadCurrentBestsellers(anyInt())).thenReturn(Mono.just(List.of(bestseller)));
        when(homePageSectionsService.loadRecentBooks(anyInt())).thenReturn(Mono.just(List.of()));
        when(homePageSectionsService.loadPopularBooks(eq(RecentBookViewRepository.ViewWindow.LAST_90_DAYS), eq(15)))
            .thenReturn(Mono.just(List.of(popular)));

        var asyncResult = mockMvc.perform(get("/api/pages/home")
                .param("popularWindow", "90d")
                .param("popularLimit", "5"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.popularWindow").value("90d"))
            .andExpect(jsonPath("$.popularBooks[0].id").value("book-popular"));
    }

    @Test
    void shouldRejectInvalidPopularWindow() throws Exception {
        mockMvc.perform(get("/api/pages/home")
                .param("popularWindow", "7d")
                .accept(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Invalid popularWindow parameter: supported values are 30d, 90d, all"));
    }

    @Test
    void shouldApplyDefaultPopularityArgumentsWhenNotProvided() throws Exception {
        when(homePageSectionsService.loadCurrentBestsellers(anyInt())).thenReturn(Mono.just(List.of()));
        when(homePageSectionsService.loadRecentBooks(anyInt())).thenReturn(Mono.just(List.of()));

        var asyncResult = mockMvc.perform(get("/api/pages/home"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.popularWindow").value("30d"));

        verify(homePageSectionsService).loadPopularBooks(
            eq(RecentBookViewRepository.ViewWindow.LAST_30_DAYS),
            eq(24)
        );
    }

    @Test
    void shouldClampPopularityLimitWhenAboveMaximum() throws Exception {
        when(homePageSectionsService.loadCurrentBestsellers(anyInt())).thenReturn(Mono.just(List.of()));
        when(homePageSectionsService.loadRecentBooks(anyInt())).thenReturn(Mono.just(List.of()));

        var asyncResult = mockMvc.perform(get("/api/pages/home")
                .param("popularLimit", "500"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk());

        verify(homePageSectionsService).loadPopularBooks(
            eq(RecentBookViewRepository.ViewWindow.LAST_30_DAYS),
            eq(72)
        );
    }

    @Test
    void shouldFilterHomePayloadCardsWithoutRenderableCovers() throws Exception {
        BookCard validCover = new BookCard(
            "book-valid",
            "book-valid-slug",
            "Valid Cover",
            List.of("Author One"),
            "https://cdn.example.com/cover.jpg",
            null,
            "https://cdn.example.com/fallback.jpg",
            4.4,
            88,
            Map.of()
        );
        BookCard placeholder = new BookCard(
            "book-placeholder",
            "book-placeholder-slug",
            "Placeholder",
            List.of("Author Two"),
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
            null,
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
            4.1,
            22,
            Map.of()
        );
        BookCard nullToken = new BookCard(
            "book-null-token",
            "book-null-token-slug",
            "Null Token",
            List.of("Author Three"),
            "null",
            "null",
            null,
            3.7,
            10,
            Map.of()
        );
        BookCard grayscale = new BookCard(
            "book-grayscale",
            "book-grayscale-slug",
            "Grayscale",
            List.of("Author Four"),
            "https://cdn.example.com/grayscale.jpg",
            null,
            "https://cdn.example.com/grayscale-fallback.jpg",
            3.9,
            11,
            Map.of(),
            Boolean.TRUE
        );

        when(homePageSectionsService.loadCurrentBestsellers(anyInt()))
            .thenReturn(Mono.just(List.of(nullToken, validCover, placeholder, grayscale)));
        when(homePageSectionsService.loadRecentBooks(anyInt()))
            .thenReturn(Mono.just(List.of(placeholder, grayscale, validCover)));
        when(homePageSectionsService.loadPopularBooks(any(RecentBookViewRepository.ViewWindow.class), anyInt()))
            .thenReturn(Mono.just(List.of(grayscale, placeholder, validCover)));

        var asyncResult = mockMvc.perform(get("/api/pages/home"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentBestsellers.length()").value(1))
            .andExpect(jsonPath("$.currentBestsellers[0].id").value("book-valid"))
            .andExpect(jsonPath("$.recentBooks.length()").value(1))
            .andExpect(jsonPath("$.recentBooks[0].id").value("book-valid"))
            .andExpect(jsonPath("$.popularBooks.length()").value(1))
            .andExpect(jsonPath("$.popularBooks[0].id").value("book-valid"));
    }

    @Test
    void shouldReturnProblemDetailWhenHomePayloadFails() throws Exception {
        when(homePageSectionsService.loadCurrentBestsellers(anyInt()))
            .thenReturn(Mono.error(new IllegalStateException("downstream unavailable")));
        when(homePageSectionsService.loadRecentBooks(anyInt())).thenReturn(Mono.just(List.of()));

        var asyncResult = mockMvc.perform(get("/api/pages/home").accept(MediaType.APPLICATION_PROBLEM_JSON))
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.detail").value("Homepage payload load failed"));
    }

    @Test
    void shouldReturnSitemapPayloadWhenBooksViewRequested() throws Exception {
        when(sitemapProperties.getBaseUrl()).thenReturn("https://findmybook.net");
        when(sitemapService.normalizeBucket(eq("A"))).thenReturn("A");
        when(sitemapService.getBooksByLetter(eq("A"), eq(1))).thenReturn(
            new SitemapService.PagedResult<>(
                List.of(new SitemapService.BookSitemapItem("book-uuid", "book-slug", "Book Title", Instant.parse("2026-01-01T00:00:00Z"))),
                1,
                3,
                300
            )
        );

        mockMvc.perform(get("/api/pages/sitemap").param("view", "books").param("letter", "A").param("page", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewType").value("books"))
            .andExpect(jsonPath("$.books[0].slug").value("book-slug"))
            .andExpect(jsonPath("$.authors").isArray());
    }

    @Test
    void shouldReturnAffiliateLinksWhenBookExists() throws Exception {
        Book book = new Book();
        book.setId("book-uuid");
        when(homePageSectionsService.locateBook(eq("book-uuid"))).thenReturn(Mono.just(book));
        when(affiliateLinkService.generateLinks(eq(book))).thenReturn(Map.of("Amazon", "https://example.com/amz"));

        var asyncResult = mockMvc.perform(get("/api/pages/book/book-uuid/affiliate-links"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Amazon").value("https://example.com/amz"));
    }

    @Test
    void shouldReturnProblemDetailWhenAffiliateLinksBookMissing() throws Exception {
        when(homePageSectionsService.locateBook(eq("missing-book"))).thenReturn(Mono.empty());

        var asyncResult = mockMvc.perform(get("/api/pages/book/missing-book/affiliate-links")
                .accept(MediaType.APPLICATION_PROBLEM_JSON))
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("No book found for identifier: missing-book"));
    }

    @Test
    void should_ReturnCategoryFacets_When_CategoriesEndpointRequested() throws Exception {
        when(homePageSectionsService.loadCategoryFacets(eq(10), eq(3))).thenReturn(
            List.of(
                new BookSearchService.CategoryFacet("Fantasy", 240),
                new BookSearchService.CategoryFacet("Mystery", 180)
            )
        );

        mockMvc.perform(get("/api/pages/categories/facets").param("limit", "10").param("minBooks", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.genres[0].name").value("Fantasy"))
            .andExpect(jsonPath("$.genres[0].bookCount").value(240))
            .andExpect(jsonPath("$.genres[1].name").value("Mystery"))
            .andExpect(jsonPath("$.genres[1].bookCount").value(180))
            .andExpect(jsonPath("$.limit").value(10))
            .andExpect(jsonPath("$.minBooks").value(3))
            .andExpect(jsonPath("$.generatedAt").isNotEmpty());
    }

    @Test
    void should_ReturnMetadataPayload_When_HomePathRequested() throws Exception {
        when(bookSeoMetadataService.homeMetadata()).thenReturn(new BookSeoMetadataService.SeoMetadata(
            "Home",
            "Home description",
            "https://findmybook.net/",
            "home keywords",
            "https://findmybook.net/images/og-logo.png",
            "index, follow, max-image-preview:large",
            "website",
            List.of(),
            "{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"WebSite\"}]}"
        ));

        var asyncResult = mockMvc.perform(get("/api/pages/meta").param("path", "/"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Home"))
            .andExpect(jsonPath("$.canonicalUrl").value("https://findmybook.net/"))
            .andExpect(jsonPath("$.robots").value("index, follow, max-image-preview:large"))
            .andExpect(jsonPath("$.openGraphType").value("website"))
            .andExpect(jsonPath("$.openGraphProperties.length()").value(0))
            .andExpect(jsonPath("$.structuredDataJson").value("{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"WebSite\"}]}"))
            .andExpect(jsonPath("$.statusCode").value(200));
    }

    @Test
    void should_ReturnProblemDetail_When_MetadataPathMissing() throws Exception {
        var asyncResult = mockMvc.perform(get("/api/pages/meta").accept(MediaType.APPLICATION_PROBLEM_JSON))
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Route path is required"));
    }

    @Test
    void should_ReturnErrorMetadata_When_ErrorPathRequested() throws Exception {
        when(bookSeoMetadataService.errorMetadata(eq(500), eq("/error"))).thenReturn(
            new BookSeoMetadataService.SeoMetadata(
                "Error 500",
                "Unexpected error",
                "https://findmybook.net/error",
                "error",
                "https://findmybook.net/images/og-logo.png",
                "noindex, nofollow, noarchive",
                "website",
                List.of(),
                "{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"WebPage\"}]}"
            )
        );

        var asyncResult = mockMvc.perform(get("/api/pages/meta").param("path", "/error"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Error 500"))
            .andExpect(jsonPath("$.canonicalUrl").value("https://findmybook.net/error"))
            .andExpect(jsonPath("$.structuredDataJson").value("{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"WebPage\"}]}"))
            .andExpect(jsonPath("$.statusCode").value(500));
    }

    @Test
    void should_ReturnMetadataWithOpenGraphExtensions_When_MetadataIncludesExtensions() throws Exception {
        when(bookSeoMetadataService.homeMetadata()).thenReturn(
            new BookSeoMetadataService.SeoMetadata(
                "Book Title",
                "Book description",
                "https://findmybook.net/",
                "book keywords",
                "https://findmybook.net/images/book-cover.png",
                "index, follow, max-image-preview:large",
                "book",
                List.of(
                    new OpenGraphProperty("book:isbn", "9780316769488"),
                    new OpenGraphProperty("book:release_date", "1951-07-16")
                ),
                "{\"@context\":\"https://schema.org\"}"
            )
        );

        var asyncResult = mockMvc.perform(get("/api/pages/meta").param("path", "/"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openGraphType").value("book"))
            .andExpect(jsonPath("$.openGraphProperties[0].property").value("book:isbn"))
            .andExpect(jsonPath("$.openGraphProperties[0].content").value("9780316769488"))
            .andExpect(jsonPath("$.structuredDataJson").value("{\"@context\":\"https://schema.org\"}"))
            .andExpect(jsonPath("$.statusCode").value(200));
    }

    @Test
    void should_ReturnNotFoundMetadata_When_BookPathCannotBeResolved() throws Exception {
        when(homePageSectionsService.locateBook(eq("missing-book"))).thenReturn(Mono.empty());
        when(bookSeoMetadataService.notFoundMetadata(eq("/book/missing-book"))).thenReturn(
            new BookSeoMetadataService.SeoMetadata(
                "Page Not Found",
                "Missing page.",
                "https://findmybook.net/book/missing-book",
                "404",
                "https://findmybook.net/images/og-logo.png",
                "noindex, nofollow, noarchive"
            )
        );

        var asyncResult = mockMvc.perform(get("/api/pages/meta").param("path", "/book/missing-book"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Page Not Found"))
            .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void should_ReturnClampedSitemapMetadata_When_MetadataPathPageExceedsIntegerRange() throws Exception {
        when(bookSeoMetadataService.sitemapRoutePattern()).thenReturn(
            Pattern.compile("^/sitemap/(authors|books)/([^/]+)/(\\d+)$")
        );
        when(sitemapService.normalizeBucket(eq("A"))).thenReturn("A");
        String expectedPath = "/sitemap/books/A/" + Integer.MAX_VALUE;
        when(bookSeoMetadataService.sitemapMetadata(eq(expectedPath))).thenReturn(
            new BookSeoMetadataService.SeoMetadata(
                "Sitemap",
                "Sitemap description",
                "https://findmybook.net" + expectedPath,
                "sitemap",
                "https://findmybook.net/images/og-logo.png",
                "index, follow, max-image-preview:large"
            )
        );

        var asyncResult = mockMvc.perform(get("/api/pages/meta").param("path", "/sitemap/books/A/2147483648"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canonicalUrl").value("https://findmybook.net" + expectedPath))
            .andExpect(jsonPath("$.statusCode").value(200));
    }

    @Test
    void should_ReturnFirstSitemapPageMetadata_When_MetadataPathPageCannotBeParsedAsLong() throws Exception {
        when(bookSeoMetadataService.sitemapRoutePattern()).thenReturn(
            Pattern.compile("^/sitemap/(authors|books)/([^/]+)/(\\d+)$")
        );
        when(sitemapService.normalizeBucket(eq("A"))).thenReturn("A");
        String expectedPath = "/sitemap/books/A/1";
        when(bookSeoMetadataService.sitemapMetadata(eq(expectedPath))).thenReturn(
            new BookSeoMetadataService.SeoMetadata(
                "Sitemap",
                "Sitemap description",
                "https://findmybook.net" + expectedPath,
                "sitemap",
                "https://findmybook.net/images/og-logo.png",
                "index, follow, max-image-preview:large"
            )
        );

        var asyncResult = mockMvc.perform(get("/api/pages/meta")
                .param("path", "/sitemap/books/A/999999999999999999999999999999999999"))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canonicalUrl").value("https://findmybook.net" + expectedPath))
            .andExpect(jsonPath("$.statusCode").value(200));
    }

    @Test
    void should_ReturnRouteManifest_When_RoutesEndpointRequested() throws Exception {
        BookSeoMetadataService.RouteManifest routeManifest = new BookSeoMetadataService.RouteManifest(
            1,
            List.of(
                new BookSeoMetadataService.RouteDefinition(
                    "home",
                    "exact",
                    "/",
                    List.of(),
                    Map.of(),
                    List.of(),
                    "/"
                )
            ),
            List.of("/api")
        );
        when(bookSeoMetadataService.routeManifest()).thenReturn(routeManifest);

        mockMvc.perform(get("/api/pages/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.publicRoutes[0].name").value("home"))
            .andExpect(jsonPath("$.passthroughPrefixes[0]").value("/api"));
    }
}
