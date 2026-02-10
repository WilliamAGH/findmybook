package net.findmybook.support.seo;

/**
 * Encapsulates all parameters needed to render a route JSON-LD graph.
 *
 * <p>Replaces the 7-parameter {@code renderRouteGraph} method signature on
 * {@link RouteStructuredDataRenderer} with a single typed boundary object.
 *
 * @param canonicalUrl canonical public URL for the route
 * @param fullTitle full rendered page title (brand-suffixed)
 * @param description route-level meta description
 * @param ogImage absolute Open Graph preview image URL
 * @param brandName website brand name for the WebSite entity
 * @param baseUrl canonical site base URL for graph identifiers
 * @param webPageType the schema.org type for the page node (e.g. "WebPage", "SearchResultsPage")
 */
public record RouteGraphRenderRequest(
    String canonicalUrl,
    String fullTitle,
    String description,
    String ogImage,
    String brandName,
    String baseUrl,
    String webPageType
) {
    /**
     * Compact constructor to validate required fields.
     */
    public RouteGraphRenderRequest {
        if (webPageType == null || webPageType.isBlank()) {
            webPageType = "WebPage";
        }
    }
}
