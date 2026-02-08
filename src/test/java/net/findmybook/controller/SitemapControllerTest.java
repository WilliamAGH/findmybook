package net.findmybook.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.findmybook.RequestLoggingFilter;
import net.findmybook.config.SitemapProperties;
import net.findmybook.repository.SitemapRepository.DatasetFingerprint;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.SitemapService;
import net.findmybook.service.SitemapService.AuthorListingXmlItem;
import net.findmybook.service.SitemapService.BookSitemapItem;
import net.findmybook.service.SitemapService.SitemapOverview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = SitemapController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestLoggingFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class SitemapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SitemapService sitemapService;

    @MockitoBean
    private SitemapProperties sitemapProperties;

    @MockitoBean
    private BookSeoMetadataService bookSeoMetadataService;

    @MockitoBean
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        Instant fallbackInstant = Instant.parse("2024-01-01T00:00:00Z");
        DatasetFingerprint fallbackFingerprint = new DatasetFingerprint(0, fallbackInstant);

        when(sitemapProperties.getBaseUrl()).thenReturn("https://findmybook.net");
        when(sitemapProperties.getHtmlPageSize()).thenReturn(100);
        when(sitemapProperties.getXmlPageSize()).thenReturn(5000);

        Map<String, Integer> defaultCounts = SitemapService.LETTER_BUCKETS.stream()
            .collect(Collectors.toMap(letter -> letter, letter -> 0, (left, right) -> right, LinkedHashMap::new));
        when(sitemapService.getOverview()).thenReturn(new SitemapOverview(defaultCounts, defaultCounts));
        when(sitemapService.normalizeBucket(any())).thenAnswer(invocation -> {
            String arg = invocation.getArgument(0);
            return arg == null ? "A" : arg.toUpperCase(Locale.ROOT);
        });
        when(sitemapService.currentBookFingerprint()).thenReturn(fallbackFingerprint);
        when(sitemapService.currentAuthorFingerprint()).thenReturn(fallbackFingerprint);
        when(sitemapService.getBookSitemapPageMetadata()).thenReturn(List.of());
        when(sitemapService.getAuthorSitemapPageMetadata()).thenReturn(List.of());

        BookSeoMetadataService.SeoMetadata sitemapMetadata = new BookSeoMetadataService.SeoMetadata(
            "Sitemap",
            "Browse all indexed author and book pages.",
            "https://findmybook.net/sitemap/books/A/1",
            "book sitemap, author sitemap, find my book",
            "https://findmybook.net/images/og-logo.png"
        );
        when(bookSeoMetadataService.sitemapMetadata(any())).thenReturn(sitemapMetadata);
        when(bookSeoMetadataService.renderSpaShell(any()))
            .thenReturn("<!doctype html><html><head><title>Sitemap - Book Finder</title></head><body><div id=\"app\"></div></body></html>");
    }

    @Test
    @DisplayName("GET /sitemap/books/A/1 returns SPA shell HTML")
    void sitemapDynamicBooksReturnsSpaShell() throws Exception {
        mockMvc.perform(get("/sitemap/books/A/1"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("<div id=\"app\"></div>")));
    }

    @Test
    @DisplayName("GET /sitemap/authors/A/2 returns SPA shell HTML")
    void sitemapDynamicAuthorsReturnsSpaShell() throws Exception {
        mockMvc.perform(get("/sitemap/authors/A/2"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("Sitemap - Book Finder")));
    }

    @Test
    @DisplayName("GET /sitemap with parameters redirects to canonical dynamic route")
    void sitemapLandingRedirectsToDynamicRoute() throws Exception {
        mockMvc.perform(get("/sitemap")
                .param("view", "books")
                .param("letter", "b")
                .param("page", "3"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/sitemap/books/B/3"));
    }

    @Test
    @DisplayName("GET /sitemap-xml/books/1.xml returns book urlset")
    void booksSitemapReturnsXml() throws Exception {
        when(sitemapService.getBooksXmlPageCount()).thenReturn(1);
        when(sitemapService.getBooksForXmlPage(1)).thenReturn(List.of(
            new BookSitemapItem("book-id", "book-slug", "Demo Book", Instant.parse("2024-01-01T00:00:00Z"))
        ));

        mockMvc.perform(get("/sitemap-xml/books/1.xml"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(content().string(containsString("/book/book-slug")));
    }

    @Test
    @DisplayName("GET /sitemap-xml/authors/1.xml returns author listing urlset")
    void authorsSitemapReturnsXml() throws Exception {
        when(sitemapService.getAuthorXmlPageCount()).thenReturn(1);
        when(sitemapService.getAuthorListingsForXmlPage(1)).thenReturn(List.of(
            new AuthorListingXmlItem("A", 1, Instant.parse("2024-01-01T00:00:00Z"))
        ));

        mockMvc.perform(get("/sitemap-xml/authors/1.xml"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(content().string(containsString("/sitemap/authors/A/1")));
    }

    @Test
    @DisplayName("GET /sitemap-xml/books/5.xml returns 404 when page exceeds total")
    void booksSitemapOutOfRangeReturns404() throws Exception {
        when(sitemapService.getBooksXmlPageCount()).thenReturn(2);

        mockMvc.perform(get("/sitemap-xml/books/5.xml"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /sitemap-xml/authors/4.xml returns 404 when page exceeds total")
    void authorsSitemapOutOfRangeReturns404() throws Exception {
        when(sitemapService.getAuthorXmlPageCount()).thenReturn(2);

        mockMvc.perform(get("/sitemap-xml/authors/4.xml"))
            .andExpect(status().isNotFound());
    }
}
