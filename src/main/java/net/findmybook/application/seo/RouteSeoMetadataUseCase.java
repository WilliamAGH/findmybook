package net.findmybook.application.seo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.support.seo.CanonicalUrlResolver;
import net.findmybook.support.seo.RouteStructuredDataRenderer;
import net.findmybook.support.seo.SeoMarkupFormatter;
import net.findmybook.util.ApplicationConstants;
import org.springframework.stereotype.Service;

/**
 * Composes route-level SEO metadata for non-book public routes.
 *
 * <p>This use case owns deterministic route metadata defaults and crawler
 * directives for home/search/explore/categories/sitemap/error routes.
 */
@Service
public class RouteSeoMetadataUseCase {

    private static final String SCHEMA_WEB_PAGE = "WebPage";
    private static final String SCHEMA_SEARCH_RESULTS_PAGE = "SearchResultsPage";
    private static final String SCHEMA_COLLECTION_PAGE = "CollectionPage";

    private final CanonicalUrlResolver canonicalUrlResolver;
    private final SeoRouteManifestUseCase seoRouteManifestUseCase;
    private final RouteStructuredDataRenderer routeStructuredDataRenderer;
    private final SeoMarkupFormatter seoMarkupFormatter;

    public RouteSeoMetadataUseCase(CanonicalUrlResolver canonicalUrlResolver,
                                   SeoRouteManifestUseCase seoRouteManifestUseCase,
                                   RouteStructuredDataRenderer routeStructuredDataRenderer,
                                   SeoMarkupFormatter seoMarkupFormatter) {
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.seoRouteManifestUseCase = seoRouteManifestUseCase;
        this.routeStructuredDataRenderer = routeStructuredDataRenderer;
        this.seoMarkupFormatter = seoMarkupFormatter;
    }

    /** Builds SEO metadata for the homepage route. */
    public SeoMetadata homeMetadata() {
        return buildRouteMetadata("Discover Books",
            "Discover your next favorite read with findmybook recommendations, trending titles, and curated lists.",
            "/",
            "findmybook, discover books, book recommendations, reading lists, best books",
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW, SCHEMA_WEB_PAGE);
    }

    /** Builds SEO metadata for the search route. */
    public SeoMetadata searchMetadata() {
        return buildRouteMetadata("Search Books",
            "Search books by title, author, or ISBN on findmybook to find details, editions, and related recommendations.",
            "/search",
            "findmybook search, search books, isbn lookup, find books by author, find books by title",
            SeoPresentationDefaults.ROBOTS_SEARCH_NOINDEX_FOLLOW, SCHEMA_SEARCH_RESULTS_PAGE);
    }

    /** Builds SEO metadata for the explore route. */
    public SeoMetadata exploreMetadata() {
        return buildRouteMetadata("Explore Books",
            "Explore curated topics and discover your next favorite read on findmybook.",
            "/explore",
            "findmybook, explore books, book discovery, reading recommendations",
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW, SCHEMA_COLLECTION_PAGE);
    }

    /** Builds SEO metadata for the categories route. */
    public SeoMetadata categoriesMetadata() {
        return buildRouteMetadata("Browse Genres",
            "Browse books by category and genre on findmybook.",
            "/categories",
            "findmybook categories, book genres, browse books, genre recommendations",
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW, SCHEMA_COLLECTION_PAGE);
    }

    /**
     * Builds SEO metadata for sitemap routes and route-level sitemap views.
     *
     * @param canonicalPath canonical sitemap route path
     * @return sitemap route SEO metadata
     */
    public SeoMetadata sitemapMetadata(String canonicalPath) {
        String normalizedPath = normalizeRequestPath(canonicalPath);
        Matcher sitemapRouteMatcher = seoRouteManifestUseCase.sitemapRoutePattern().matcher(normalizedPath);
        if (sitemapRouteMatcher.matches()) {
            String viewToken = "books".equalsIgnoreCase(sitemapRouteMatcher.group(1)) ? "books" : "authors";
            String bucketToken = sitemapRouteMatcher.group(2).toUpperCase(Locale.ROOT);
            int pageNumber = parseSitemapPageNumber(sitemapRouteMatcher.group(3));
            String viewLabel = "books".equals(viewToken) ? "Books" : "Authors";
            String bucketLabel = "0-9".equals(bucketToken) ? "0-9" : bucketToken;
            String bucketKeyword = "0-9".equals(bucketToken) ? "numeric" : "letter " + bucketToken;

            return buildRouteMetadata(
                viewLabel + " Sitemap: " + bucketLabel + " Page " + pageNumber,
                "Browse " + viewToken + " indexed under " + bucketLabel + " on page " + pageNumber + " of the findmybook sitemap.",
                normalizedPath,
                "findmybook sitemap, " + viewToken + " sitemap, " + bucketKeyword + ", sitemap page " + pageNumber,
                SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW, SCHEMA_WEB_PAGE);
        }

        return buildRouteMetadata("Sitemap",
            "Browse indexed author and book pages on findmybook.",
            normalizedPath,
            "findmybook sitemap, author sitemap, book sitemap",
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW, SCHEMA_WEB_PAGE);
    }

    /**
     * Builds SEO metadata for not-found responses.
     *
     * @param requestPath original unresolved request path
     * @return not-found SEO metadata
     */
    public SeoMetadata notFoundMetadata(String requestPath) {
        return buildRouteMetadata("Page Not Found",
            "The page you requested could not be found on findmybook.",
            SeoPresentationDefaults.DEFAULT_NOT_FOUND_PATH,
            "findmybook 404, page not found, broken link",
            SeoPresentationDefaults.ROBOTS_NOINDEX_NOFOLLOW, SCHEMA_WEB_PAGE);
    }

    /**
     * Builds SEO metadata for runtime error responses.
     *
     * @param statusCode HTTP error status code
     * @param requestPath failing request path
     * @return error route SEO metadata
     */
    public SeoMetadata errorMetadata(int statusCode, String requestPath) {
        return buildRouteMetadata("Error " + statusCode,
            "An unexpected error occurred while loading this findmybook page.",
            SeoPresentationDefaults.DEFAULT_ERROR_PATH,
            "findmybook error, server error, application error",
            SeoPresentationDefaults.ROBOTS_NOINDEX_NOFOLLOW, SCHEMA_WEB_PAGE);
    }

    /**
     * Assembles a complete route-level SEO metadata record from the provided
     * route-specific parameters and shared presentation defaults.
     *
     * @param title          page title (before brand suffix)
     * @param description    meta description text
     * @param canonicalPath  canonical route path (will be resolved to a public URL)
     * @param keywords       meta keywords
     * @param robots         robots directive
     * @param schemaOrgType  schema.org page type for the JSON-LD graph (e.g. "WebPage", "SearchResultsPage")
     * @return fully composed SEO metadata for the route
     */
    private SeoMetadata buildRouteMetadata(String title,
                                           String description,
                                           String canonicalPath,
                                           String keywords,
                                           String robots,
                                           String schemaOrgType) {
        String canonicalUrl = canonicalUrlResolver.normalizePublicUrl(canonicalPath);
        String ogImage = ApplicationConstants.Urls.OG_LOGO;
        String fullTitle = seoMarkupFormatter.pageTitle(
            title, SeoPresentationDefaults.PAGE_TITLE_SUFFIX, SeoPresentationDefaults.BRAND_NAME);
        String structuredDataJson = routeStructuredDataRenderer.renderRouteGraph(
            canonicalUrl, fullTitle, description, ogImage,
            SeoPresentationDefaults.BRAND_NAME, ApplicationConstants.Urls.BASE_URL, schemaOrgType);
        return new SeoMetadata(title, description, canonicalUrl, keywords, ogImage, robots,
            SeoPresentationDefaults.OPEN_GRAPH_TYPE_WEBSITE, List.of(), structuredDataJson);
    }

    private String normalizeRequestPath(String requestPath) {
        if (requestPath == null || requestPath.trim().isEmpty()) {
            return "/";
        }
        String trimmed = requestPath.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private int parseSitemapPageNumber(String rawPageNumber) {
        if (rawPageNumber == null || rawPageNumber.trim().isEmpty()) {
            return 1;
        }
        try {
            int parsed = Integer.parseInt(rawPageNumber.trim());
            return Math.max(parsed, 1);
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}

