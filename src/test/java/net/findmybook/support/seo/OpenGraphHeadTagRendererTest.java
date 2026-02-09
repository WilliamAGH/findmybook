package net.findmybook.support.seo;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenGraphHeadTagRendererTest {

    @Test
    void should_RenderEscapedOpenGraphMetaTags_When_PropertiesAreValid() {
        OpenGraphHeadTagRenderer renderer = new OpenGraphHeadTagRenderer();

        String html = renderer.renderMetaTags(List.of(
            new net.findmybook.domain.seo.OpenGraphProperty("book:isbn", "9780316769488"),
            new net.findmybook.domain.seo.OpenGraphProperty("book:tag", "Sci-Fi & Fantasy")
        ));

        assertTrue(html.contains("<meta property=\"book:isbn\" content=\"9780316769488\">"));
        assertTrue(html.contains("<meta property=\"book:tag\" content=\"Sci-Fi &amp; Fantasy\">"));
    }

    @Test
    void should_ReturnEmptyString_When_NoRenderablePropertiesProvided() {
        OpenGraphHeadTagRenderer renderer = new OpenGraphHeadTagRenderer();

        assertEquals("", renderer.renderMetaTags(null));
        assertEquals("", renderer.renderMetaTags(List.of()));
        assertEquals("", renderer.renderMetaTags(List.of(
            new net.findmybook.domain.seo.OpenGraphProperty(" ", "value"),
            new net.findmybook.domain.seo.OpenGraphProperty("book:tag", " ")
        )));
    }
}
