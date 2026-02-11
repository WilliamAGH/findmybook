package net.findmybook.support.seo;

import java.util.List;
import net.findmybook.domain.seo.OpenGraphProperty;
import net.findmybook.domain.seo.SeoMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpaShellDocumentRendererTest {

    @Test
    void should_RenderBookOpenGraphAndStructuredData_When_MetadataContainsExtensions() {
        SpaShellDocumentRenderer renderer = new SpaShellDocumentRenderer(
            new SeoMarkupFormatter(),
            new OpenGraphHeadTagRenderer(new SeoMarkupFormatter()),
            new CanonicalUrlResolver()
        );

        SeoMetadata metadata = new SeoMetadata(
            "The Catcher in the Rye",
            "A classic novel about teenage rebellion.",
            "https://findmybook.net/book/the-catcher-in-the-rye",
            "book details",
            "https://findmybook.net/images/catcher.jpg",
            "index, follow, max-image-preview:large",
            "book",
            List.of(new OpenGraphProperty("book:isbn", "9780316769488")),
            "{\"@context\":\"https://schema.org\",\"@type\":\"Book\"}"
        );

        var ctx = new SpaShellRenderContext(
            metadata,
            metadata,
            " | findmybook",
            "findmybook",
            "Default description",
            "default keywords",
            "index, follow, max-image-preview:large",
            "website",
            "findmybook social preview image",
            "#fdfcfa",
            "{\"version\":1}",
            "https://findmybook.net"
        );

        String html = renderer.render(ctx);

        assertTrue(html.contains("<meta property=\"og:type\" content=\"book\">"));
        assertTrue(html.contains("<meta property=\"book:isbn\" content=\"9780316769488\">"));
        assertTrue(html.contains("<meta property=\"og:image:secure_url\" content=\"https://findmybook.net/images/catcher.jpg\">"));
        assertTrue(html.contains("<meta property=\"og:image:width\" content=\"1200\">"));
        assertTrue(html.contains("<link rel=\"sitemap\" type=\"application/xml\" title=\"Sitemap\" href=\"/sitemap.xml\">"));
        assertTrue(html.contains("<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"Book\"}</script>"));
    }

    @Test
    void should_NotAppendDefaultSuffix_When_MetadataTitleAlreadyUsesFindmybookDotNetSuffix() {
        SpaShellDocumentRenderer renderer = new SpaShellDocumentRenderer(
            new SeoMarkupFormatter(),
            new OpenGraphHeadTagRenderer(new SeoMarkupFormatter()),
            new CanonicalUrlResolver()
        );
        SeoMetadata metadata = new SeoMetadata(
            "The Pragmatic Programmer - Book Details | findmybook.net",
            "A practical guide to software craftsmanship.",
            "https://findmybook.net/book/the-pragmatic-programmer",
            "book details",
            "https://findmybook.net/images/cover.png",
            "index, follow, max-image-preview:large",
            "book",
            List.of(),
            "{\"@context\":\"https://schema.org\",\"@type\":\"Book\"}"
        );
        var ctx = new SpaShellRenderContext(
            metadata,
            metadata,
            " | findmybook",
            "findmybook",
            "Default description",
            "default keywords",
            "index, follow, max-image-preview:large",
            "website",
            "findmybook social preview image",
            "#fdfcfa",
            "{\"version\":1}",
            "https://findmybook.net"
        );

        String html = renderer.render(ctx);
        assertTrue(html.contains("<title>The Pragmatic Programmer - Book Details | findmybook.net</title>"));
        assertTrue(!html.contains("| findmybook.net | findmybook"));
    }
}
