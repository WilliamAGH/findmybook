package net.findmybook.support.seo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeoRouteManifestProviderTest {

    @Test
    void should_ExposeCanonicalRouteManifest_When_PublicRoutesRequested() {
        SeoRouteManifestProvider provider = new SeoRouteManifestProvider();

        RouteManifest manifest = provider.routeManifest();

        assertEquals(1, manifest.version());
        assertTrue(manifest.publicRoutes().stream().anyMatch(route -> "book".equals(route.name())));
        assertTrue(manifest.publicRoutes().stream().anyMatch(route -> "error".equals(route.name())));
        assertTrue(manifest.passthroughPrefixes().contains("/api"));
        assertEquals("/sitemap/authors/A/1", provider.defaultSitemapPath());
        assertTrue(provider.bookRoutePattern().matcher("/book/the-hobbit").matches());
        assertTrue(provider.sitemapRoutePattern().matcher("/sitemap/books/A/2").matches());
    }

    @Test
    void should_ReturnSerializedManifestJson_When_ManifestScriptPayloadRequested() {
        SeoRouteManifestProvider provider = new SeoRouteManifestProvider();

        String routeManifestJson = provider.routeManifestJson();

        assertTrue(routeManifestJson.contains("\"version\":1"));
        assertTrue(routeManifestJson.contains("\"publicRoutes\""));
        assertTrue(routeManifestJson.contains("\"passthroughPrefixes\""));
    }
}
