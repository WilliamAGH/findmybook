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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.findmybook.application.page.PublicPagePayloadUseCase;
import net.findmybook.config.SitemapProperties;
import net.findmybook.model.Book;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.domain.seo.RouteDefinition;
import net.findmybook.domain.seo.RouteManifest;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.BookSearchService;
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
class PageApiControllerAuxiliaryEndpointsTest {

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
    void should_ReturnRouteManifest_When_RoutesEndpointRequested() throws Exception {
        RouteManifest routeManifest = new RouteManifest(
            1,
            List.of(
                new RouteDefinition(
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
