package net.findmybook.controller;

import net.findmybook.config.SitemapProperties;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.SitemapService;
import net.findmybook.service.SitemapService.AuthorListingXmlItem;
import net.findmybook.service.SitemapService.BookSitemapItem;
import net.findmybook.util.PagingUtils;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Serves the browsable sitemap HTML shell ({@code /sitemap/**}) and the
 * XML sitemap feeds ({@code /sitemap.xml}, {@code /sitemap-xml/**}) consumed
 * by search-engine crawlers.
 */
@Controller
public class SitemapController extends SpaShellController {

    private static final DateTimeFormatter LAST_MODIFIED_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final SitemapService sitemapService;
    private final SitemapProperties sitemapProperties;

    public SitemapController(SitemapService sitemapService,
                             SitemapProperties sitemapProperties,
                             BookSeoMetadataService bookSeoMetadataService) {
        super(bookSeoMetadataService);
        this.sitemapService = sitemapService;
        this.sitemapProperties = sitemapProperties;
    }

    @GetMapping("/sitemap")
    public String sitemapLanding(@RequestParam(name = "view", required = false) String view,
                                 @RequestParam(name = "letter", required = false) String letter,
                                 @RequestParam(name = "page", required = false, defaultValue = "1") int page) {
        String normalizedView = normalizeView(view);
        String bucket = sitemapService.normalizeBucket(letter);
        int safePage = PagingUtils.atLeast(page, 1);
        return "redirect:/sitemap/" + normalizedView + "/" + bucket + "/" + safePage;
    }

    @GetMapping("/sitemap/{view}/{letter}/{page}")
    public ResponseEntity<String> sitemapDynamic(@PathVariable String view,
                                                 @PathVariable String letter,
                                                 @PathVariable int page) {
        String normalizedView = normalizeView(view);
        String bucket = sitemapService.normalizeBucket(letter);
        int safePage = PagingUtils.atLeast(page, 1);

        if (!normalizedView.equals(view) || !bucket.equals(letter) || safePage != page) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(java.net.URI.create("/sitemap/" + normalizedView + "/" + bucket + "/" + safePage))
                .build();
        }

        String canonicalPath = "/sitemap/" + normalizedView + "/" + bucket + "/" + safePage;
        BookSeoMetadataService.SeoMetadata metadata = bookSeoMetadataService.sitemapMetadata(canonicalPath);
        return spaResponse(metadata, HttpStatus.OK);
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> sitemapIndex() {
        int totalBookPages = sitemapService.getBooksXmlPageCount();
        int totalAuthorPages = sitemapService.getAuthorXmlPageCount();
        String baseUrl = sitemapProperties.getBaseUrl();

        Map<Integer, Instant> bookLastModified = sitemapService.getBookSitemapPageMetadata().stream()
                .collect(Collectors.toMap(
                        SitemapService.SitemapPageMetadata::page,
                        SitemapService.SitemapPageMetadata::lastModified,
                        (a, b) -> b,
                        LinkedHashMap::new));
        Map<Integer, Instant> authorLastModified = sitemapService.getAuthorSitemapPageMetadata().stream()
                .collect(Collectors.toMap(
                        SitemapService.SitemapPageMetadata::page,
                        SitemapService.SitemapPageMetadata::lastModified,
                        (a, b) -> b,
                        LinkedHashMap::new));

        Instant bookFallback = sitemapService.currentBookFingerprint().lastModified();
        Instant authorFallback = sitemapService.currentAuthorFingerprint().lastModified();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (int i = 1; i <= totalBookPages; i++) {
            Instant lastModified = bookLastModified.getOrDefault(i, bookFallback);
            xml.append("  <sitemap>\n");
            xml.append("    <loc>").append(escapeXml(baseUrl + "/sitemap-xml/books/" + i + ".xml")).append("</loc>\n");
            xml.append("    <lastmod>").append(LAST_MODIFIED_FORMATTER.format(lastModified)).append("</lastmod>\n");
            xml.append("  </sitemap>\n");
        }

        for (int i = 1; i <= totalAuthorPages; i++) {
            Instant lastModified = authorLastModified.getOrDefault(i, authorFallback);
            xml.append("  <sitemap>\n");
            xml.append("    <loc>").append(escapeXml(baseUrl + "/sitemap-xml/authors/" + i + ".xml")).append("</loc>\n");
            xml.append("    <lastmod>").append(LAST_MODIFIED_FORMATTER.format(lastModified)).append("</lastmod>\n");
            xml.append("  </sitemap>\n");
        }
        xml.append("</sitemapindex>");

        return ResponseEntity.ok(xml.toString());
    }

    @GetMapping(value = "/sitemap-xml/books/{page}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> booksSitemap(@PathVariable("page") int page) {
        int totalPages = sitemapService.getBooksXmlPageCount();
        if (totalPages == 0 || page < 1 || page > totalPages) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<BookSitemapItem> items = sitemapService.getBooksForXmlPage(page);
        String xml = buildBookUrlSet(items);
        return ResponseEntity.ok(xml);
    }

    @GetMapping(value = "/sitemap-xml/authors/{page}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> authorsSitemap(@PathVariable("page") int page) {
        int totalPages = sitemapService.getAuthorXmlPageCount();
        if (totalPages == 0 || page < 1 || page > totalPages) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<AuthorListingXmlItem> listings = sitemapService.getAuthorListingsForXmlPage(page);
        String xml = buildAuthorUrlSet(listings);
        return ResponseEntity.ok(xml);
    }

    private String buildBookUrlSet(List<BookSitemapItem> items) {
        String baseUrl = sitemapProperties.getBaseUrl();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        Instant fallback = sitemapService.currentBookFingerprint().lastModified();
        for (BookSitemapItem item : items) {
            if (item.slug() == null || item.slug().isBlank()) {
                continue; // Skip items without valid slugs
            }
            Instant lastModified = item.updatedAt() != null ? item.updatedAt() : fallback;
            xml.append("  <url>\n");
            // Slugs should already be URL-safe, but ensure proper encoding
            String safeSlug = validateAndEncodeSlug(item.slug());
            xml.append("    <loc>").append(escapeXml(baseUrl + "/book/" + safeSlug)).append("</loc>\n");
            xml.append("    <lastmod>").append(LAST_MODIFIED_FORMATTER.format(lastModified)).append("</lastmod>\n");
            xml.append("    <changefreq>weekly</changefreq>\n");
            xml.append("    <priority>0.8</priority>\n");
            xml.append("  </url>\n");
        }
        xml.append("</urlset>");
        return xml.toString();
    }

    private String buildAuthorUrlSet(List<AuthorListingXmlItem> items) {
        String baseUrl = sitemapProperties.getBaseUrl();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        Instant fallback = sitemapService.currentAuthorFingerprint().lastModified();
        for (AuthorListingXmlItem item : items) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(baseUrl + item.toPath())).append("</loc>\n");
            Instant lastModified = item.lastModified() != null ? item.lastModified() : fallback;
            xml.append("    <lastmod>").append(LAST_MODIFIED_FORMATTER.format(lastModified)).append("</lastmod>\n");
            xml.append("    <changefreq>daily</changefreq>\n");
            xml.append("    <priority>0.6</priority>\n");
            xml.append("  </url>\n");
        }
        xml.append("</urlset>");
        return xml.toString();
    }

    private String validateAndEncodeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return "unknown";
        }
        if (slug.matches("[a-z0-9-]+")) {
            return slug;
        }
        return UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8);
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String normalizeView(String view) {
        if (!StringUtils.hasText(view)) {
            return "authors";
        }
        String candidate = view.trim().toLowerCase(Locale.ROOT);
        return "books".equals(candidate) ? "books" : "authors";
    }

}
