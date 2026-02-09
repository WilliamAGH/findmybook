package net.findmybook.application.seo;

import java.util.Locale;
import java.util.regex.Matcher;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.support.seo.CanonicalUrlResolver;
import org.springframework.stereotype.Service;

/**
 * Composes route-level SEO metadata for non-book public routes.
 *
 * <p>This use case owns deterministic route metadata defaults and crawler
 * directives for home/search/explore/categories/sitemap/error routes.
 */
@Service
public class RouteSeoMetadataUseCase {

    private final CanonicalUrlResolver canonicalUrlResolver;
    private final SeoRouteManifestUseCase seoRouteManifestUseCase;

    public RouteSeoMetadataUseCase(CanonicalUrlResolver canonicalUrlResolver,
                                   SeoRouteManifestUseCase seoRouteManifestUseCase) {
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.seoRouteManifestUseCase = seoRouteManifestUseCase;
    }

    /**
     * Builds SEO metadata for the homepage route.
     *
     * @return homepage SEO metadata
     */
    public SeoMetadata homeMetadata() {
        return new SeoMetadata(
            "Discover Books",
            "Discover your next favorite read with findmybook recommendations, trending titles, and curated lists.",
            canonicalUrlResolver.normalizePublicUrl("/"),
            "findmybook, discover books, book recommendations, reading lists, best books",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE,
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW
        );
    }

    /**
     * Builds SEO metadata for the search route.
     *
     * @return search route SEO metadata
     */
    public SeoMetadata searchMetadata() {
        return new SeoMetadata(
            "Search Books",
            "Search books by title, author, or ISBN on findmybook to find details, editions, and related recommendations.",
            canonicalUrlResolver.normalizePublicUrl("/search"),
            "findmybook search, search books, isbn lookup, find books by author, find books by title",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE,
            SeoPresentationDefaults.ROBOTS_SEARCH_NOINDEX_FOLLOW
        );
    }

    /**
     * Builds SEO metadata for the explore route.
     *
     * @return explore route SEO metadata
     */
    public SeoMetadata exploreMetadata() {
        return new SeoMetadata(
            "Explore Books",
            "Explore curated topics and discover your next favorite read on findmybook.",
            canonicalUrlResolver.normalizePublicUrl("/explore"),
            "findmybook, explore books, book discovery, reading recommendations",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE,
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW
        );
    }

    /**
     * Builds SEO metadata for the categories route.
     *
     * @return categories route SEO metadata
     */
    public SeoMetadata categoriesMetadata() {
        return new SeoMetadata(
            "Browse Genres",
            "Browse books by category and genre on findmybook.",
            canonicalUrlResolver.normalizePublicUrl("/categories"),
            "findmybook categories, book genres, browse books, genre recommendations",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE,
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW
        );
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

            return new SeoMetadata(
                viewLabel + " Sitemap: " + bucketLabel + " Page " + pageNumber,
                "Browse " + viewToken + " indexed under " + bucketLabel + " on page " + pageNumber + " of the findmybook sitemap.",
                canonicalUrlResolver.normalizePublicUrl(normalizedPath),
                "findmybook sitemap, " + viewToken + " sitemap, " + bucketKeyword + ", sitemap page " + pageNumber,
                ApplicationConstants.Urls.OG_LOGO,
                SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW
            );
        }

        return new SeoMetadata(
            "Sitemap",
            "Browse indexed author and book pages on findmybook.",
            canonicalUrlResolver.normalizePublicUrl(normalizedPath),
            "findmybook sitemap, author sitemap, book sitemap",
            ApplicationConstants.Urls.OG_LOGO,
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW
        );
    }

    /**
     * Builds SEO metadata for not-found responses.
     *
     * @param requestPath original unresolved request path
     * @return not-found SEO metadata
     */
    public SeoMetadata notFoundMetadata(String requestPath) {
        return new SeoMetadata(
            "Page Not Found",
            "The page you requested could not be found on findmybook.",
            canonicalUrlResolver.normalizePublicUrl(SeoPresentationDefaults.DEFAULT_NOT_FOUND_PATH),
            "findmybook 404, page not found, broken link",
            ApplicationConstants.Urls.OG_LOGO,
            SeoPresentationDefaults.ROBOTS_NOINDEX_NOFOLLOW
        );
    }

    /**
     * Builds SEO metadata for runtime error responses.
     *
     * @param statusCode HTTP error status code
     * @param requestPath failing request path
     * @return error route SEO metadata
     */
    public SeoMetadata errorMetadata(int statusCode, String requestPath) {
        return new SeoMetadata(
            "Error " + statusCode,
            "An unexpected error occurred while loading this findmybook page.",
            canonicalUrlResolver.normalizePublicUrl(SeoPresentationDefaults.DEFAULT_ERROR_PATH),
            "findmybook error, server error, application error",
            ApplicationConstants.Urls.OG_LOGO,
            SeoPresentationDefaults.ROBOTS_NOINDEX_NOFOLLOW
        );
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

