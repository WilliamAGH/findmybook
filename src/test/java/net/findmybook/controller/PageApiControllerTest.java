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
import net.findmybook.adapters.persistence.PageViewEventRepository;
import net.findmybook.config.SitemapProperties;
import net.findmybook.dto.BookCard;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.RecentBookViewRepository;
import net.findmybook.service.SitemapService;
import net.findmybook.util.ApplicationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
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
    void should_ReturnPopularBooks_When_WindowRequested() throws Exception {
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
    void should_RejectInvalidPopularWindow_When_WindowUnsupported() throws Exception {
        mockMvc.perform(get("/api/pages/home")
                .param("popularWindow", "7d")
                .accept(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Invalid popularWindow parameter: supported values are 30d, 90d, all"));
    }

    @Test
    void should_ApplyDefaultPopularityArguments_When_NotProvided() throws Exception {
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
    void should_ClampPopularityLimit_When_AboveMaximum() throws Exception {
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

}

@ExtendWith(MockitoExtension.class)
class PageViewEventRepositoryTest {

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void should_NotThrow_When_RecordViewInsertFails() {
        PageViewEventRepository repository = new PageViewEventRepository(jdbcTemplate);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
            .when(jdbcTemplate)
            .update(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.PreparedStatementSetter.class)
            );

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> repository.recordView("homepage", Instant.parse("2026-02-11T00:00:00Z"), "api")
        );
    }
}
