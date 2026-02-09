package net.findmybook.support.seo;

import net.findmybook.domain.seo.SeoMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders the server-side SPA shell HTML document from route-level SEO metadata.
 */
@Component
public class SpaShellDocumentRenderer {

    private static final String OPEN_GRAPH_IMAGE_TYPE = "image/png";
    private static final String OPEN_GRAPH_IMAGE_WIDTH = "1200";
    private static final String OPEN_GRAPH_IMAGE_HEIGHT = "630";

    private final SeoMarkupFormatter seoMarkupFormatter;
    private final OpenGraphHeadTagRenderer openGraphHeadTagRenderer;
    private final RouteStructuredDataRenderer routeStructuredDataRenderer;
    private final CanonicalUrlResolver canonicalUrlResolver;

    public SpaShellDocumentRenderer(SeoMarkupFormatter seoMarkupFormatter,
                                    OpenGraphHeadTagRenderer openGraphHeadTagRenderer,
                                    RouteStructuredDataRenderer routeStructuredDataRenderer,
                                    CanonicalUrlResolver canonicalUrlResolver) {
        this.seoMarkupFormatter = seoMarkupFormatter;
        this.openGraphHeadTagRenderer = openGraphHeadTagRenderer;
        this.routeStructuredDataRenderer = routeStructuredDataRenderer;
        this.canonicalUrlResolver = canonicalUrlResolver;
    }

    /**
     * Renders a complete HTML shell with meta tags and JSON-LD script for the provided route metadata.
     */
    public String render(SeoMetadata metadata,
                         SeoMetadata fallbackMetadata,
                         String pageTitleSuffix,
                         String brandName,
                         String defaultDescription,
                         String defaultKeywords,
                         String defaultRobots,
                         String defaultOpenGraphType,
                         String openGraphImageAlt,
                         String themeColor,
                         String routeManifestJson,
                         String baseUrl) {
        SeoMetadata effectiveMetadata = metadata != null ? metadata : fallbackMetadata;
        String normalizedCanonical = canonicalUrlResolver.normalizePublicUrl(effectiveMetadata.canonicalUrl());
        String absoluteOgImage = canonicalUrlResolver.normalizePublicUrl(effectiveMetadata.ogImage());
        String fullTitle = seoMarkupFormatter.pageTitle(effectiveMetadata.title(), pageTitleSuffix, brandName);
        String normalizedDescription = seoMarkupFormatter.fallbackText(effectiveMetadata.description(), defaultDescription);

        String escapedTitle = seoMarkupFormatter.escapeHtml(fullTitle);
        String escapedDescription = seoMarkupFormatter.escapeHtml(normalizedDescription);
        String escapedKeywords = seoMarkupFormatter.escapeHtml(
            seoMarkupFormatter.fallbackText(effectiveMetadata.keywords(), defaultKeywords)
        );
        String escapedRobots = seoMarkupFormatter.escapeHtml(
            seoMarkupFormatter.fallbackText(effectiveMetadata.robots(), defaultRobots)
        );
        String escapedCanonicalUrl = seoMarkupFormatter.escapeHtml(normalizedCanonical);
        String escapedOgImage = seoMarkupFormatter.escapeHtml(absoluteOgImage);
        String escapedOgImageAlt = seoMarkupFormatter.escapeHtml(openGraphImageAlt);
        String escapedOpenGraphType = seoMarkupFormatter.escapeHtml(
            seoMarkupFormatter.fallbackText(effectiveMetadata.openGraphType(), defaultOpenGraphType)
        );
        String openGraphExtensionTags = openGraphHeadTagRenderer.renderMetaTags(effectiveMetadata.openGraphProperties());
        String escapedBrandName = seoMarkupFormatter.escapeHtml(brandName);
        String escapedRouteManifestJson = seoMarkupFormatter.escapeInlineScriptJson(routeManifestJson);

        String structuredDataJson = StringUtils.hasText(effectiveMetadata.structuredDataJson())
            ? effectiveMetadata.structuredDataJson()
            : routeStructuredDataRenderer.renderRouteGraph(
                normalizedCanonical,
                fullTitle,
                normalizedDescription,
                absoluteOgImage,
                brandName,
                baseUrl
            );
        String escapedStructuredData = seoMarkupFormatter.escapeInlineScriptJson(structuredDataJson);

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <meta name="color-scheme" content="light dark">
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <meta name="format-detection" content="telephone=no">
              <meta name="theme-color" content="%s">
              <meta name="application-name" content="%s">
              <meta name="apple-mobile-web-app-title" content="%s">
              <title>%s</title>
              <meta name="description" content="%s">
              <meta name="keywords" content="%s">
              <meta name="robots" content="%s">
              <meta name="googlebot" content="%s">
              <link rel="canonical" href="%s">
              <link rel="alternate" hreflang="en-US" href="%s">
              <link rel="alternate" hreflang="x-default" href="%s">
              <meta property="og:type" content="%s">
              <meta property="og:site_name" content="%s">
              <meta property="og:locale" content="en_US">
              <meta property="og:url" content="%s">
              <meta property="og:title" content="%s">
              <meta property="og:description" content="%s">
              <meta property="og:image" content="%s">
              <meta property="og:image:secure_url" content="%s">
              <meta property="og:image:type" content="%s">
              <meta property="og:image:width" content="%s">
              <meta property="og:image:height" content="%s">
              <meta property="og:image:alt" content="%s">
              %s
              <meta name="twitter:card" content="summary_large_image">
              <meta name="twitter:domain" content="findmybook.net">
              <meta name="twitter:url" content="%s">
              <meta name="twitter:title" content="%s">
              <meta name="twitter:description" content="%s">
              <meta name="twitter:image" content="%s">
              <meta name="twitter:image:alt" content="%s">
              <script type="application/ld+json">%s</script>
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
              <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Poppins:wght@500;600;700&display=swap" rel="stylesheet">
              <link rel="icon" href="/favicon.svg" type="image/svg+xml">
              <link rel="icon" href="/favicon-32x32.png" type="image/png" sizes="32x32">
              <link rel="icon" href="/favicon-192x192.png" type="image/png" sizes="192x192">
              <link rel="apple-touch-icon" href="/apple-touch-icon.png">
              <link rel="manifest" href="/site.webmanifest">
              <link rel="sitemap" type="application/xml" title="Sitemap" href="/sitemap.xml">
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
            themeColor,
            escapedBrandName,
            escapedBrandName,
            escapedTitle,
            escapedDescription,
            escapedKeywords,
            escapedRobots,
            escapedRobots,
            escapedCanonicalUrl,
            escapedCanonicalUrl,
            escapedCanonicalUrl,
            escapedOpenGraphType,
            escapedBrandName,
            escapedCanonicalUrl,
            escapedTitle,
            escapedDescription,
            escapedOgImage,
            escapedOgImage,
            OPEN_GRAPH_IMAGE_TYPE,
            OPEN_GRAPH_IMAGE_WIDTH,
            OPEN_GRAPH_IMAGE_HEIGHT,
            escapedOgImageAlt,
            openGraphExtensionTags,
            escapedCanonicalUrl,
            escapedTitle,
            escapedDescription,
            escapedOgImage,
            escapedOgImageAlt,
            escapedStructuredData,
            escapedRouteManifestJson
        );
    }
}
