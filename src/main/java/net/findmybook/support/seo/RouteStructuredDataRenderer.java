package net.findmybook.support.seo;

import org.springframework.stereotype.Component;

/**
 * Renders generic route JSON-LD for non-book pages.
 */
@Component
public class RouteStructuredDataRenderer {

    /**
     * Builds a WebSite + WebPage JSON-LD graph for a canonical route.
     */
    public String renderRouteGraph(String canonicalUrl,
                                   String fullTitle,
                                   String description,
                                   String ogImage,
                                   String brandName,
                                   String baseUrl) {
        return """
            {"@context":"https://schema.org","@graph":[{"@type":"WebSite","@id":"%s/#website","url":"%s/","name":"%s","potentialAction":{"@type":"SearchAction","target":"%s/search?query={search_term_string}","query-input":"required name=search_term_string"}},{"@type":"WebPage","@id":"%s#webpage","url":"%s","name":"%s","description":"%s","isPartOf":{"@id":"%s/#website"},"primaryImageOfPage":{"@type":"ImageObject","url":"%s"},"inLanguage":"en-US"}]}
            """.formatted(
            escapeJson(baseUrl),
            escapeJson(baseUrl),
            escapeJson(brandName),
            escapeJson(baseUrl),
            escapeJson(canonicalUrl),
            escapeJson(canonicalUrl),
            escapeJson(fullTitle),
            escapeJson(description),
            escapeJson(baseUrl),
            escapeJson(ogImage)
        );
    }

    private String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("/", "\\/");
    }
}

