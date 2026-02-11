package net.findmybook.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Pattern;
import net.findmybook.application.page.PublicPagePayloadUseCase;
import net.findmybook.config.SitemapProperties;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.domain.seo.OpenGraphProperty;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.adapters.persistence.PageViewEventRepository;
import net.findmybook.service.RecentBookViewRepository;
import net.findmybook.service.SitemapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

@WebMvcTest(PageApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PublicPagePayloadUseCase.class)
class PageApiControllerMetadataTest {

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
    void should_ReturnMetadataPayload_When_HomePathRequested() throws Exception {
        when(bookSeoMetadataService.homeMetadata()).thenReturn(new SeoMetadata(
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
            new SeoMetadata(
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
            new SeoMetadata(
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
            new SeoMetadata(
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
            new SeoMetadata(
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
            new SeoMetadata(
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
}
