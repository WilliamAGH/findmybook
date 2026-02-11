package net.findmybook.application.seo;

import java.util.regex.Pattern;
import net.findmybook.domain.seo.RouteDefinition;
import net.findmybook.domain.seo.RouteManifest;
import net.findmybook.support.seo.SeoRouteManifestProvider;
import org.springframework.stereotype.Service;

/**
 * Provides the canonical public route manifest contract to application and web layers.
 *
 * <p>This use case translates adapter-level manifest payloads into domain route
 * contracts while preserving route-pattern utilities needed by controllers.
 */
@Service
public class SeoRouteManifestUseCase {

    private final SeoRouteManifestProvider seoRouteManifestProvider;

    public SeoRouteManifestUseCase(SeoRouteManifestProvider seoRouteManifestProvider) {
        this.seoRouteManifestProvider = seoRouteManifestProvider;
    }

    /**
     * Returns the canonical domain route manifest used by SPA APIs and shell rendering.
     *
     * @return immutable domain route manifest
     */
    public RouteManifest routeManifest() {
        net.findmybook.support.seo.RouteManifest manifest = seoRouteManifestProvider.routeManifest();
        return new RouteManifest(
            manifest.version(),
            manifest.publicRoutes().stream().map(this::toDomainRouteDefinition).toList(),
            manifest.passthroughPrefixes()
        );
    }

    /**
     * Returns pre-serialized JSON for shell bootstrap route manifest embedding.
     *
     * @return serialized route manifest JSON
     */
    public String routeManifestJson() {
        return seoRouteManifestProvider.routeManifestJson();
    }

    /**
     * Returns the default canonical sitemap route path used by metadata fallbacks.
     *
     * @return default sitemap route path
     */
    public String defaultSitemapPath() {
        return seoRouteManifestProvider.defaultSitemapPath();
    }

    /**
     * Returns the canonical regex for public book route matching.
     *
     * @return compiled book route pattern
     */
    public Pattern bookRoutePattern() {
        return seoRouteManifestProvider.bookRoutePattern();
    }

    /**
     * Returns the canonical regex for public sitemap route matching.
     *
     * @return compiled sitemap route pattern
     */
    public Pattern sitemapRoutePattern() {
        return seoRouteManifestProvider.sitemapRoutePattern();
    }

    private RouteDefinition toDomainRouteDefinition(net.findmybook.support.seo.RouteDefinition routeDefinition) {
        return new RouteDefinition(
            routeDefinition.name(),
            routeDefinition.matchType(),
            routeDefinition.pattern(),
            routeDefinition.paramNames(),
            routeDefinition.defaults(),
            routeDefinition.allowedQueryParams(),
            routeDefinition.canonicalPathTemplate()
        );
    }
}

