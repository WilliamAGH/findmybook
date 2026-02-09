package net.findmybook.support.seo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RouteStructuredDataRendererTest {

    private final RouteStructuredDataRenderer renderer = new RouteStructuredDataRenderer();

    @Test
    void should_RenderDefaultWebPage_When_TypeIsNotProvided() {
        String json = renderer.renderRouteGraph(
            "https://findmybook.net/",
            "Home | findmybook",
            "Description",
            "https://findmybook.net/og.png",
            "findmybook",
            "https://findmybook.net"
        );

        assertTrue(json.contains("\"@type\":\"WebPage\""));
        // Expect escaped slashes in URL
        assertTrue(json.contains("\"@id\":\"https:\\/\\/findmybook.net\\/#webpage\""));
        assertFalse(json.contains("SearchResultsPage"));
    }

    @Test
    void should_RenderSpecificPageType_When_TypeIsProvided() {
        String json = renderer.renderRouteGraph(
            "https://findmybook.net/search",
            "Search | findmybook",
            "Search results",
            "https://findmybook.net/og.png",
            "findmybook",
            "https://findmybook.net",
            "SearchResultsPage"
        );

        assertTrue(json.contains("\"@type\":\"SearchResultsPage\""));
        // Expect escaped slashes in URL
        assertTrue(json.contains("\"@id\":\"https:\\/\\/findmybook.net\\/search#webpage\""));
        assertFalse(json.contains("\"@type\":\"WebPage\"")); // Should replace WebPage type for the page node
    }
    
    @Test
    void should_EscapeJsonCharacters() {
        String json = renderer.renderRouteGraph(
            "https://findmybook.net/foo",
            "Title \"with quotes\"",
            "Description with \n newline",
            "https://findmybook.net/og.png",
            "Brand",
            "https://findmybook.net"
        );

        assertTrue(json.contains("Title \\\"with quotes\\\""));
        assertTrue(json.contains("Description with \\n newline"));
    }

    @Test
    void should_RejectNullInput_When_AnyParameterIsNull() {
        assertThrows(NullPointerException.class, () ->
            renderer.renderRouteGraph(
                null,
                "Title",
                "Description",
                "https://findmybook.net/og.png",
                "findmybook",
                "https://findmybook.net"
            )
        );
    }

    @Test
    void should_EscapeHtmlAngleBrackets_When_InputContainsHtml() {
        String json = renderer.renderRouteGraph(
            "https://findmybook.net/",
            "Title <script>alert('xss')</script>",
            "Safe description",
            "https://findmybook.net/og.png",
            "findmybook",
            "https://findmybook.net"
        );

        assertFalse(json.contains("<script>"));
        assertTrue(json.contains("\\u003cscript\\u003e"));
    }

    @Test
    void should_IncludeSearchAction_When_Rendered() {
        String json = renderer.renderRouteGraph(
            "https://findmybook.net/",
            "Home | findmybook",
            "Description",
            "https://findmybook.net/og.png",
            "findmybook",
            "https://findmybook.net"
        );

        assertTrue(json.contains("\"@type\":\"SearchAction\""));
        assertTrue(json.contains("\"@type\":\"WebSite\""));
        assertTrue(json.contains("\"@context\":\"https://schema.org\""));
    }
}
