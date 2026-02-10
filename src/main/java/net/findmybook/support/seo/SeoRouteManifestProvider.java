package net.findmybook.support.seo;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.findmybook.domain.seo.RouteDefinition;
import net.findmybook.domain.seo.RouteManifest;
import org.springframework.stereotype.Component;

/**
 * Provides the canonical public route manifest and route-matching metadata for SPA SEO handling.
 */
@Component
public class SeoRouteManifestProvider {

    // Static ObjectMapper is intentional: used in static initializer for ROUTE_MANIFEST_JSON
    // before Spring context is available. Only serializes simple record types with no custom modules.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_SITEMAP_PATH = "/sitemap/authors/A/1";
    private static final Pattern BOOK_ROUTE_PATTERN = Pattern.compile("^/book/([^/]+)$");
    private static final Pattern SITEMAP_ROUTE_PATTERN = Pattern.compile("^/sitemap/(authors|books)/([^/]+)/(\\d+)$");

    private static final RouteManifest ROUTE_MANIFEST = new RouteManifest(
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
            ),
            new RouteDefinition(
                "search",
                "exact",
                "/search",
                List.of(),
                Map.of(),
                List.of("query", "year", "page", "orderBy", "source", "coverSource", "resolution", "genre", "view"),
                "/search"
            ),
            new RouteDefinition(
                "explore",
                "exact",
                "/explore",
                List.of(),
                Map.of(),
                List.of("query", "year", "page", "orderBy", "source", "coverSource", "resolution", "genre", "view"),
                "/explore"
            ),
            new RouteDefinition(
                "categories",
                "exact",
                "/categories",
                List.of(),
                Map.of(),
                List.of("query", "year", "page", "orderBy", "source", "coverSource", "resolution", "genre", "view"),
                "/categories"
            ),
            new RouteDefinition(
                "book",
                "regex",
                "^/book/([^/]+)$",
                List.of("identifier"),
                Map.of(),
                List.of("query", "page", "orderBy", "view"),
                "/book/{identifier}"
            ),
            new RouteDefinition(
                "sitemap",
                "exact",
                "/sitemap",
                List.of(),
                Map.of("view", "authors", "letter", "A", "page", "1"),
                List.of("view", "letter", "page"),
                DEFAULT_SITEMAP_PATH
            ),
            new RouteDefinition(
                "sitemap",
                "regex",
                "^/sitemap/(authors|books)/([^/]+)/(\\d+)$",
                List.of("view", "letter", "page"),
                Map.of(),
                List.of(),
                "/sitemap/{view}/{letter}/{page}"
            ),
            new RouteDefinition(
                "notFound",
                "exact",
                "/404",
                List.of(),
                Map.of(),
                List.of(),
                "/404"
            ),
            new RouteDefinition(
                "error",
                "exact",
                "/error",
                List.of(),
                Map.of(),
                List.of(),
                "/error"
            )
        ),
        List.of("/api", "/admin", "/actuator", "/ws", "/topic", "/sitemap.xml", "/sitemap-xml", "/r")
    );

    private static final String ROUTE_MANIFEST_JSON = serializeRouteManifest(ROUTE_MANIFEST);

    /**
     * Returns the typed public route contract consumed by both backend and frontend.
     */
    public RouteManifest routeManifest() {
        return ROUTE_MANIFEST;
    }

    /**
     * Returns pre-serialized JSON for script bootstrap embedding.
     */
    public String routeManifestJson() {
        return ROUTE_MANIFEST_JSON;
    }

    /**
     * Returns the canonical default sitemap path used by route metadata and shell bootstrap.
     */
    public String defaultSitemapPath() {
        return DEFAULT_SITEMAP_PATH;
    }

    /**
     * Returns the canonical book-route regex pattern used by metadata route matching.
     */
    public Pattern bookRoutePattern() {
        return BOOK_ROUTE_PATTERN;
    }

    /**
     * Returns the canonical sitemap-route regex pattern used by metadata route matching.
     */
    public Pattern sitemapRoutePattern() {
        return SITEMAP_ROUTE_PATTERN;
    }

    private static String serializeRouteManifest(RouteManifest routeManifest) {
        try {
            return OBJECT_MAPPER.writeValueAsString(routeManifest);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize SPA route manifest", ex);
        }
    }
}
