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

    /**
     * Escapes a string for safe embedding in a JSON value per RFC 8259.
     *
     * <p>Handles all mandatory JSON escape sequences: backslash, double-quote,
     * named control characters ({@code \b}, {@code \f}, {@code \n}, {@code \r},
     * {@code \t}), remaining ASCII control characters (U+0000-U+001F), and
     * HTML-sensitive characters ({@code <}, {@code >}, {@code /}) to prevent
     * script injection in {@code <script>} contexts.</p>
     */
    private String escapeJson(String text) {
        Objects.requireNonNull(text, "JSON escape input must not be null");
        var sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<'  -> sb.append("\\u003c");
                case '>'  -> sb.append("\\u003e");
                case '/'  -> sb.append("\\/");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}

