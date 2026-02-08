package net.findmybook.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import net.findmybook.model.Book;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.SeoUtils;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds and applies SEO metadata for server-rendered SPA shell responses.
 */
@Service
public class BookSeoMetadataService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PLACEHOLDER_COVER_MARKER = "placeholder-book-cover";
    private static final String HTTP_SCHEME_PREFIX = "http";
    private static final String PAGE_TITLE_SUFFIX = " - Book Finder";
    private static final String DEFAULT_DESCRIPTION = "Discover books and recommendations on FindMyBook.";
    private static final String DEFAULT_KEYWORDS = "book recommendations, find books, book search";
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
            )
        ),
        List.of("/api", "/admin", "/actuator", "/ws", "/topic", "/sitemap.xml", "/sitemap-xml", "/r")
    );
    private static final String ROUTE_MANIFEST_JSON = serializeRouteManifest(ROUTE_MANIFEST);

    private final LocalDiskCoverCacheService localDiskCoverCacheService;

    public BookSeoMetadataService(LocalDiskCoverCacheService localDiskCoverCacheService) {
        this.localDiskCoverCacheService = localDiskCoverCacheService;
    }

    public SeoMetadata homeMetadata() {
        return new SeoMetadata(
            "Home",
            "Discover your next favorite book with our recommendation engine. Explore recently viewed books and new arrivals.",
            ApplicationConstants.Urls.BASE_URL + "/",
            "book recommendations, find books, book suggestions, reading, literature, home",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE
        );
    }

    public SeoMetadata searchMetadata() {
        return new SeoMetadata(
            "Search Books",
            "Search our extensive catalog of books by title, author, or ISBN. Find detailed information and recommendations.",
            ApplicationConstants.Urls.BASE_URL + "/search",
            "book search, find books by title, find books by author, isbn lookup, book catalog",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE
        );
    }

    public SeoMetadata exploreMetadata() {
        return new SeoMetadata(
            "Explore Books",
            "Explore curated topics and discover your next favorite read.",
            ApplicationConstants.Urls.BASE_URL + "/explore",
            "book discovery, explore books, reading recommendations",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE
        );
    }

    public SeoMetadata categoriesMetadata() {
        return new SeoMetadata(
            "Browse Categories",
            "Browse books by category and genre.",
            ApplicationConstants.Urls.BASE_URL + "/categories",
            "book categories, book genres, browse books",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE
        );
    }

    public SeoMetadata bookFallbackMetadata(String identifier) {
        return new SeoMetadata(
            "Book Details",
            "Detailed information about the selected book.",
            ApplicationConstants.Urls.BASE_URL + "/book/" + identifier,
            "book, literature, reading, book details",
            ApplicationConstants.Urls.OG_LOGO
        );
    }

    public SeoMetadata bookMetadata(Book book, int maxDescriptionLength) {
        if (book == null) {
            throw new IllegalArgumentException("Book must not be null when generating SEO metadata");
        }
        String title = StringUtils.hasText(book.getTitle()) ? book.getTitle() : "Book Details";
        String description = SeoUtils.truncateDescription(book.getDescription(), maxDescriptionLength);
        String canonicalIdentifier = StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
        String canonicalUrl = ApplicationConstants.Urls.BASE_URL + "/book/" + canonicalIdentifier;
        String keywords = SeoUtils.generateKeywords(book);
        String ogImage = resolveOgImage(book);

        return new SeoMetadata(title, description, canonicalUrl, keywords, ogImage);
    }

    public SeoMetadata sitemapMetadata(String canonicalPath) {
        String normalizedPath = normalizeRequestPath(canonicalPath);
        return new SeoMetadata(
            "Sitemap",
            "Browse all indexed author and book pages.",
            normalizeCanonicalUrl(normalizedPath),
            "book sitemap, author sitemap, find my book",
            ApplicationConstants.Urls.OG_LOGO
        );
    }

    public SeoMetadata notFoundMetadata(String requestPath) {
        String normalizedPath = normalizeRequestPath(requestPath);
        return new SeoMetadata(
            "Page Not Found",
            "The page you requested could not be found.",
            normalizeCanonicalUrl(normalizedPath),
            "404, page not found",
            ApplicationConstants.Urls.OG_LOGO
        );
    }

    public SeoMetadata errorMetadata(int statusCode, String requestPath) {
        String normalizedPath = normalizeRequestPath(requestPath);
        return new SeoMetadata(
            "Error " + statusCode,
            "An unexpected error occurred while loading this page.",
            normalizeCanonicalUrl(normalizedPath),
            "error, server error",
            ApplicationConstants.Urls.OG_LOGO
        );
    }

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

    public String renderSpaShell(SeoMetadata seoMetadata) {
        SeoMetadata effectiveMetadata = seoMetadata != null ? seoMetadata : homeMetadata();
        String normalizedCanonical = normalizeCanonicalUrl(effectiveMetadata.canonicalUrl());
        String absoluteOgImage = normalizeCanonicalUrl(effectiveMetadata.ogImage());
        String fullTitle = formatPageTitle(effectiveMetadata.title());
        String escapedTitle = escapeHtml(fullTitle);
        String escapedDescription = escapeHtml(defaultIfBlank(effectiveMetadata.description(), DEFAULT_DESCRIPTION));
        String escapedKeywords = escapeHtml(defaultIfBlank(effectiveMetadata.keywords(), DEFAULT_KEYWORDS));
        String escapedCanonicalUrl = escapeHtml(normalizedCanonical);
        String escapedOgImage = escapeHtml(absoluteOgImage);
        String escapedRouteManifestJson = escapeInlineScriptJson(routeManifestJson());

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <meta name="color-scheme" content="light dark">
              <title>%s</title>
              <meta name="description" content="%s">
              <meta name="keywords" content="%s">
              <meta name="robots" content="index, follow">
              <link rel="canonical" href="%s">
              <meta property="og:type" content="website">
              <meta property="og:url" content="%s">
              <meta property="og:title" content="%s">
              <meta property="og:description" content="%s">
              <meta property="og:image" content="%s">
              <meta property="twitter:card" content="summary_large_image">
              <meta property="twitter:url" content="%s">
              <meta property="twitter:title" content="%s">
              <meta property="twitter:description" content="%s">
              <meta property="twitter:image" content="%s">
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
              <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Poppins:wght@500;600;700&display=swap" rel="stylesheet">
              <link rel="icon" href="/favicon.svg" type="image/svg+xml">
              <link rel="icon" href="/favicon-32x32.png" type="image/png" sizes="32x32">
              <link rel="icon" href="/favicon-192x192.png" type="image/png" sizes="192x192">
              <link rel="apple-touch-icon" href="/apple-touch-icon.png">
              <link rel="manifest" href="/site.webmanifest">
              <link rel="stylesheet" href="/frontend/app.css">
              <script>
                (function () {
                  try {
                    var preferredTheme = localStorage.getItem("preferred_theme");
                    if (preferredTheme === "light" || preferredTheme === "dark") {
                      document.documentElement.setAttribute("data-theme", preferredTheme);
                    }
                  } catch (e) {
                    // Ignore localStorage access failures.
                  }
                })();
              </script>
            </head>
            <body>
              <div id="app"></div>
              <script>
                window.__FMB_ROUTE_MANIFEST__ = %s;
              </script>
              <script type="module" src="/frontend/app.js"></script>
            </body>
            </html>
            """.formatted(
            escapedTitle,
            escapedDescription,
            escapedKeywords,
            escapedCanonicalUrl,
            escapedCanonicalUrl,
            escapedTitle,
            escapedDescription,
            escapedOgImage,
            escapedCanonicalUrl,
            escapedTitle,
            escapedDescription,
            escapedOgImage,
            escapedRouteManifestJson
        );
    }

    private String resolveOgImage(Book book) {
        String coverUrl = book.getS3ImagePath();
        String placeholder = localDiskCoverCacheService.getLocalPlaceholderPath();
        if (StringUtils.hasText(coverUrl)
            && (placeholder == null || !placeholder.equals(coverUrl))
            && !coverUrl.contains(PLACEHOLDER_COVER_MARKER)) {
            return coverUrl;
        }
        return ApplicationConstants.Urls.OG_LOGO;
    }

    private String normalizeCanonicalUrl(String candidate) {
        String raw = StringUtils.hasText(candidate) ? candidate.trim() : ApplicationConstants.Urls.BASE_URL + "/";
        if (raw.toLowerCase(Locale.ROOT).startsWith(HTTP_SCHEME_PREFIX)) {
            return raw;
        }
        if (!raw.startsWith("/")) {
            raw = "/" + raw;
        }
        return ApplicationConstants.Urls.BASE_URL + raw;
    }

    private String normalizeRequestPath(String requestPath) {
        if (!StringUtils.hasText(requestPath)) {
            return "/";
        }
        return requestPath.startsWith("/") ? requestPath : "/" + requestPath;
    }

    private String defaultIfBlank(String candidate, String fallback) {
        return StringUtils.hasText(candidate) ? candidate : fallback;
    }

    private String formatPageTitle(String title) {
        String baseTitle = defaultIfBlank(title, "Book Finder");
        if (baseTitle.endsWith(PAGE_TITLE_SUFFIX)) {
            return baseTitle;
        }
        return baseTitle + PAGE_TITLE_SUFFIX;
    }

    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
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

    private String escapeInlineScriptJson(String value) {
        return value
            .replace("<", "\\u003c")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029");
    }

    private static String serializeRouteManifest(RouteManifest routeManifest) {
        try {
            return OBJECT_MAPPER.writeValueAsString(routeManifest);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize SPA route manifest", ex);
        }
    }

    public record SeoMetadata(
        String title,
        String description,
        String canonicalUrl,
        String keywords,
        String ogImage
    ) {}

    /**
     * Immutable manifest consumed by SPA clients for route matching and navigation boundaries.
     */
    public record RouteManifest(
        int version,
        List<RouteDefinition> publicRoutes,
        List<String> passthroughPrefixes
    ) {}

    /**
     * Single route-definition contract entry for public SPA routes.
     */
    public record RouteDefinition(
        String name,
        String matchType,
        String pattern,
        List<String> paramNames,
        Map<String, String> defaults,
        List<String> allowedQueryParams,
        String canonicalPathTemplate
    ) {}
}
