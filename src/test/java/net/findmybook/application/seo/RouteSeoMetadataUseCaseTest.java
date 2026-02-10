package net.findmybook.application.seo;

import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.support.seo.CanonicalUrlResolver;
import net.findmybook.support.seo.RouteGraphRenderRequest;
import net.findmybook.support.seo.RouteStructuredDataRenderer;
import net.findmybook.support.seo.SeoMarkupFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteSeoMetadataUseCaseTest {

    @Mock
    private CanonicalUrlResolver canonicalUrlResolver;
    @Mock
    private SeoRouteManifestUseCase seoRouteManifestUseCase;
    @Mock
    private RouteStructuredDataRenderer routeStructuredDataRenderer;
    @Mock
    private SeoMarkupFormatter seoMarkupFormatter;

    private RouteSeoMetadataUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RouteSeoMetadataUseCase(
            canonicalUrlResolver,
            seoRouteManifestUseCase,
            routeStructuredDataRenderer,
            seoMarkupFormatter
        );
    }

    @Test
    void homeMetadata_ShouldUseWebPageType() {
        when(canonicalUrlResolver.normalizePublicUrl(anyString())).thenReturn("https://findmybook.net/");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Home Title");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("Expected JSON");

        SeoMetadata metadata = useCase.homeMetadata();

        assertEquals("Expected JSON", metadata.structuredDataJson());
        verify(routeStructuredDataRenderer).renderRouteGraph(any(RouteGraphRenderRequest.class));
    }

    @Test
    void searchMetadata_ShouldUseSearchResultsPageType() {
        when(canonicalUrlResolver.normalizePublicUrl(anyString())).thenReturn("https://findmybook.net/search");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Search Title");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("Expected JSON");

        SeoMetadata metadata = useCase.searchMetadata();

        assertEquals("Expected JSON", metadata.structuredDataJson());
        verify(routeStructuredDataRenderer).renderRouteGraph(any(RouteGraphRenderRequest.class));
    }

    @Test
    void exploreMetadata_ShouldUseCollectionPageType() {
        when(canonicalUrlResolver.normalizePublicUrl(anyString())).thenReturn("https://findmybook.net/explore");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Explore Title");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("Expected JSON");

        SeoMetadata metadata = useCase.exploreMetadata();

        assertEquals("Expected JSON", metadata.structuredDataJson());
        verify(routeStructuredDataRenderer).renderRouteGraph(any(RouteGraphRenderRequest.class));
    }

    @Test
    void categoriesMetadata_ShouldUseCollectionPageType() {
        when(canonicalUrlResolver.normalizePublicUrl(anyString())).thenReturn("https://findmybook.net/categories");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Categories Title");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("Expected JSON");

        SeoMetadata metadata = useCase.categoriesMetadata();

        assertEquals("Expected JSON", metadata.structuredDataJson());
        verify(routeStructuredDataRenderer).renderRouteGraph(any(RouteGraphRenderRequest.class));
    }

    @Test
    void should_BuildNotFoundMetadata_When_PathIsUnresolved() {
        when(canonicalUrlResolver.normalizePublicUrl(eq("/404"))).thenReturn("https://findmybook.net/404");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Page Not Found | findmybook");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("not-found-json");

        SeoMetadata metadata = useCase.notFoundMetadata("/unknown");

        assertEquals("Page Not Found", metadata.title());
        assertEquals(SeoPresentationDefaults.ROBOTS_NOINDEX_NOFOLLOW, metadata.robots());
        verify(canonicalUrlResolver).normalizePublicUrl("/404");
    }

    @Test
    void should_BuildErrorMetadata_When_StatusCodeIsProvided() {
        when(canonicalUrlResolver.normalizePublicUrl(eq("/error"))).thenReturn("https://findmybook.net/error");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Error 500 | findmybook");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("error-json");

        SeoMetadata metadata = useCase.errorMetadata(500, "/broken");

        assertEquals("Error 500", metadata.title());
        assertEquals(SeoPresentationDefaults.ROBOTS_NOINDEX_NOFOLLOW, metadata.robots());
        verify(canonicalUrlResolver).normalizePublicUrl("/error");
    }

    @Test
    void should_BuildGenericSitemapMetadata_When_PathDoesNotMatchPattern() {
        when(seoRouteManifestUseCase.sitemapRoutePattern())
            .thenReturn(Pattern.compile("^/sitemap/(authors|books)/([^/]+)/(\\d+)$"));
        when(canonicalUrlResolver.normalizePublicUrl(eq("/sitemap"))).thenReturn("https://findmybook.net/sitemap");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Sitemap | findmybook");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("sitemap-json");

        SeoMetadata metadata = useCase.sitemapMetadata("/sitemap");

        assertEquals("Sitemap", metadata.title());
        assertTrue(metadata.keywords().contains("sitemap"));
    }

    @Test
    void should_BuildPatternedSitemapMetadata_When_PathMatchesViewBucketPage() {
        when(seoRouteManifestUseCase.sitemapRoutePattern())
            .thenReturn(Pattern.compile("^/sitemap/(authors|books)/([^/]+)/(\\d+)$"));
        when(canonicalUrlResolver.normalizePublicUrl(eq("/sitemap/books/A/2")))
            .thenReturn("https://findmybook.net/sitemap/books/A/2");
        when(seoMarkupFormatter.pageTitle(anyString(), anyString(), anyString())).thenReturn("Books Sitemap: A Page 2 | findmybook");
        when(routeStructuredDataRenderer.renderRouteGraph(any(RouteGraphRenderRequest.class)))
            .thenReturn("patterned-sitemap-json");

        SeoMetadata metadata = useCase.sitemapMetadata("/sitemap/books/A/2");

        assertEquals("Books Sitemap: A Page 2", metadata.title());
        assertTrue(metadata.description().contains("books"));
        assertTrue(metadata.keywords().contains("books sitemap"));
    }
}
