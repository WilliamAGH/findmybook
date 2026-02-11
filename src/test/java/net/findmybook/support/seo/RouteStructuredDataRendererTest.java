package net.findmybook.support.seo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RouteStructuredDataRendererTest {

    private final RouteStructuredDataRenderer renderer = new RouteStructuredDataRenderer();

    @Test
    void should_IncludeSearchAction_When_Rendered() {
        String json = renderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                "https://findmybook.net/",
                "Home | findmybook",
                "Description",
                "https://findmybook.net/og.png",
                "findmybook",
                "https://findmybook.net",
                "WebPage"
            )
        );

        assertTrue(json.contains("\"@type\":\"SearchAction\""));
        assertTrue(json.contains("\"@type\":\"WebSite\""));
        assertTrue(json.contains("\"@context\":\"https://schema.org\""));
    }

    @Test
    void should_RenderDefaultWebPage_When_TypeIsNotProvided() {
        String json = renderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                "https://findmybook.net/",
                "Home | findmybook",
                "Description",
                "https://findmybook.net/og.png",
                "findmybook",
                "https://findmybook.net",
                null // Test null defaulting in record constructor
            )
        );

        assertTrue(json.contains("\"@type\":\"WebPage\""));
        // Expect escaped slashes in URL
        assertTrue(json.contains("\"@id\":\"https:\\/\\/findmybook.net\\/#webpage\""));
        assertFalse(json.contains("SearchResultsPage"));
    }

    @Test
    void should_RenderSpecificPageType_When_TypeIsProvided() {
        String json = renderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                "https://findmybook.net/search",
                "Search | findmybook",
                "Search results",
                "https://findmybook.net/og.png",
                "findmybook",
                "https://findmybook.net",
                "SearchResultsPage"
            )
        );

        assertTrue(json.contains("\"@type\":\"SearchResultsPage\""));
        // Expect escaped slashes in URL
        assertTrue(json.contains("\"@id\":\"https:\\/\\/findmybook.net\\/search#webpage\""));
        assertFalse(json.contains("\"@type\":\"WebPage\"")); // Should replace WebPage type for the page node
    }
    
    @Test
    void should_EscapeJsonCharacters() {
        String json = renderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                "https://findmybook.net/foo",
                "Title \"with quotes\"",
                "Description with \n newline",
                "https://findmybook.net/og.png",
                "Brand",
                "https://findmybook.net",
                "WebPage"
            )
        );

        assertTrue(json.contains("Title \\\"with quotes\\\""));
        assertTrue(json.contains("Description with \\n newline"));
    }

    @Test
    void should_RejectNullInput_When_RequestIsNull() {
        assertThrows(NullPointerException.class, () ->
            renderer.renderRouteGraph(null)
        );
    }

    @Test
    void should_EscapeHtmlAngleBrackets_When_InputContainsHtml() {
        String json = renderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                "https://findmybook.net/",
                "Title <script>alert('xss')</script>",
                "Safe description",
                "https://findmybook.net/og.png",
                "findmybook",
                "https://findmybook.net",
                "WebPage"
            )
        );

        assertFalse(json.contains("<script>"));
        assertTrue(json.contains("\\u003cscript\\u003e"));
    }

    @Test
    void should_EscapeControlCharacters_When_InputContainsTabBackspaceFormFeed() {
        String json = renderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                "https://findmybook.net/",
                "Title\twith\ttabs",
                "Desc with\bbackspace and\fform feed",
                "https://findmybook.net/og.png",
                "findmybook",
                "https://findmybook.net",
                "WebPage"
            )
        );

        assertTrue(json.contains("Title\\twith\\ttabs"));
        assertTrue(json.contains("\\b"));
        assertTrue(json.contains("\\f"));
        assertFalse(json.contains("\t"));
        assertFalse(json.contains("\b"));
        assertFalse(json.contains("\f"));
    }

    @Test
    void should_EscapeNullByteAsUnicode_When_InputContainsAsciiControl() {
        String json = renderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                "https://findmybook.net/",
                "Title with \u0001 control",
                "Description",
                "https://findmybook.net/og.png",
                "findmybook",
                "https://findmybook.net",
                "WebPage"
            )
        );

        assertTrue(json.contains("\\u0001"));
        assertFalse(json.contains("\u0001"));
    }
}
