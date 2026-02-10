package net.findmybook.support.seo;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Renders generic route JSON-LD for non-book pages.
 */
@Component
public class RouteStructuredDataRenderer {

    private static final String DEFAULT_WEB_PAGE_TYPE = "WebPage";

    /**
     * Builds a WebSite + WebPage JSON-LD graph for a canonical route.
     *
     * @param webPageType the schema.org type for the page node (e.g. "WebPage", "SearchResultsPage", "CollectionPage")
     */
    public String renderRouteGraph(String canonicalUrl,
                                   String fullTitle,
                                   String description,
                                   String ogImage,
                                   String brandName,
                                   String baseUrl,
                                   String webPageType) {
        return """
            {"@context":"https://schema.org","@graph":[{"@type":"WebSite","@id":"%s/#website","url":"%s/","name":"%s","potentialAction":{"@type":"SearchAction","target":"%s/search?query={search_term_string}","query-input":"required name=search_term_string"}},{"@type":"%s","@id":"%s#webpage","url":"%s","name":"%s","description":"%s","isPartOf":{"@id":"%s/#website"},"primaryImageOfPage":{"@type":"ImageObject","url":"%s"},"inLanguage":"en-US"}]}
            """.formatted(
            escapeJson(baseUrl),
            escapeJson(baseUrl),
            escapeJson(brandName),
            escapeJson(baseUrl),
            escapeJson(webPageType),
            escapeJson(canonicalUrl),
            escapeJson(canonicalUrl),
            escapeJson(fullTitle),
            escapeJson(description),
            escapeJson(baseUrl),
            escapeJson(ogImage)
        );
    }

    /**
     * Builds a WebSite + WebPage JSON-LD graph using the default "WebPage" type.
     */
    public String renderRouteGraph(String canonicalUrl,
                                   String fullTitle,
                                   String description,
                                   String ogImage,
                                   String brandName,
                                   String baseUrl) {
        return renderRouteGraph(canonicalUrl, fullTitle, description, ogImage, brandName, baseUrl, DEFAULT_WEB_PAGE_TYPE);
    }

    private String escapeJson(String text) {
        Objects.requireNonNull(text, "JSON escape input must not be null");
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("/", "\\/");
    }
}

