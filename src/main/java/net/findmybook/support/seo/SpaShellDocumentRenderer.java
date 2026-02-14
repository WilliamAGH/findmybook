package net.findmybook.support.seo;

import net.findmybook.domain.seo.SeoMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders the server-side SPA shell HTML document from route-level SEO metadata.
 */
@Component
public class SpaShellDocumentRenderer {

    private static final String OPEN_GRAPH_IMAGE_TYPE = "image/png";
    private static final int OPEN_GRAPH_IMAGE_WIDTH = 1200;
    private static final int OPEN_GRAPH_IMAGE_HEIGHT = 630;
    private static final String OPEN_GRAPH_IMAGE_WIDTH_VALUE = Integer.toString(OPEN_GRAPH_IMAGE_WIDTH);
    private static final String OPEN_GRAPH_IMAGE_HEIGHT_VALUE = Integer.toString(OPEN_GRAPH_IMAGE_HEIGHT);
    private static final String TWITTER_CARD_TYPE = "summary_large_image";
    private static final boolean DEFAULT_SIMPLE_ANALYTICS_ENABLED = true;
    private static final String DEFAULT_SIMPLE_ANALYTICS_SCRIPT_URL =
        "https://scripts.simpleanalyticscdn.com/latest.js";
    private static final boolean DEFAULT_CLICKY_ENABLED = true;
    private static final String DEFAULT_CLICKY_SITE_ID = "101484793";
    private static final String CLICKY_SCRIPT_URL = "//static.getclicky.com/js";

    private final SeoMarkupFormatter seoMarkupFormatter;
    private final OpenGraphHeadTagRenderer openGraphHeadTagRenderer;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final SeoMetadataDevValidator seoMetadataDevValidator;
    private final boolean simpleAnalyticsEnabled;
    private final String simpleAnalyticsScriptUrl;
    private final boolean clickyEnabled;
    private final String clickySiteId;

    /**
     * Backward-compatible constructor used by manual wiring in tests and utility contexts.
     *
     * @param seoMarkupFormatter formatter used for escaping and fallback text normalization
     * @param openGraphHeadTagRenderer renderer for OpenGraph extension tags
     * @param canonicalUrlResolver resolver for absolute canonical and image URLs
     */
    public SpaShellDocumentRenderer(SeoMarkupFormatter seoMarkupFormatter,
                                    OpenGraphHeadTagRenderer openGraphHeadTagRenderer,
                                    CanonicalUrlResolver canonicalUrlResolver) {
        this(
            seoMarkupFormatter,
            openGraphHeadTagRenderer,
            canonicalUrlResolver,
            SeoMetadataDevValidator.disabled(),
            DEFAULT_SIMPLE_ANALYTICS_ENABLED,
            DEFAULT_SIMPLE_ANALYTICS_SCRIPT_URL,
            DEFAULT_CLICKY_ENABLED,
            DEFAULT_CLICKY_SITE_ID
        );
    }

    @Autowired
    public SpaShellDocumentRenderer(SeoMarkupFormatter seoMarkupFormatter,
                                    OpenGraphHeadTagRenderer openGraphHeadTagRenderer,
                                    CanonicalUrlResolver canonicalUrlResolver,
                                    SeoMetadataDevValidator seoMetadataDevValidator,
                                    @Value("${app.simple-analytics.enabled:true}") boolean simpleAnalyticsEnabled,
                                    @Value("${app.simple-analytics.script-url:https://scripts.simpleanalyticscdn.com/latest.js}") String simpleAnalyticsScriptUrl,
                                    @Value("${app.clicky.enabled:true}") boolean clickyEnabled,
                                    @Value("${app.clicky.site-id:101484793}") String clickySiteId) {
        this.seoMarkupFormatter = seoMarkupFormatter;
        this.openGraphHeadTagRenderer = openGraphHeadTagRenderer;
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.seoMetadataDevValidator = seoMetadataDevValidator;
        this.simpleAnalyticsEnabled = simpleAnalyticsEnabled;
        this.simpleAnalyticsScriptUrl = simpleAnalyticsScriptUrl;
        this.clickyEnabled = clickyEnabled;
        this.clickySiteId = clickySiteId;
    }

    /**
     * Renders a complete HTML shell with meta tags and JSON-LD script for the provided render context.
     */
    public String render(SpaShellRenderContext ctx) {
        SeoMetadata effectiveMetadata = ctx.metadata() != null ? ctx.metadata() : ctx.fallbackMetadata();
        String normalizedCanonical = canonicalUrlResolver.normalizePublicUrl(effectiveMetadata.canonicalUrl());
        String absoluteOgImage = canonicalUrlResolver.normalizePublicUrl(effectiveMetadata.ogImage());
        String fullTitle = seoMarkupFormatter.pageTitle(effectiveMetadata.title(), ctx.pageTitleSuffix(), ctx.brandName());
        String normalizedDescription = seoMarkupFormatter.fallbackText(effectiveMetadata.description(), ctx.defaultDescription());

        String escapedTitle = seoMarkupFormatter.escapeHtml(fullTitle);
        String escapedDescription = seoMarkupFormatter.escapeHtml(normalizedDescription);
        String escapedKeywords = seoMarkupFormatter.escapeHtml(
            seoMarkupFormatter.fallbackText(effectiveMetadata.keywords(), ctx.defaultKeywords())
        );
        String escapedRobots = seoMarkupFormatter.escapeHtml(
            seoMarkupFormatter.fallbackText(effectiveMetadata.robots(), ctx.defaultRobots())
        );
        String escapedCanonicalUrl = seoMarkupFormatter.escapeHtml(normalizedCanonical);
        String escapedOgImage = seoMarkupFormatter.escapeHtml(absoluteOgImage);
        String escapedOgImageAlt = seoMarkupFormatter.escapeHtml(ctx.openGraphImageAlt());
        String resolvedOpenGraphType = seoMarkupFormatter.fallbackText(effectiveMetadata.openGraphType(), ctx.defaultOpenGraphType());
        String escapedOpenGraphType = seoMarkupFormatter.escapeHtml(resolvedOpenGraphType);
        String openGraphExtensionTags = openGraphHeadTagRenderer.renderMetaTags(effectiveMetadata.openGraphProperties());
        String escapedThemeColor = seoMarkupFormatter.escapeHtml(ctx.themeColor());
        String escapedBrandName = seoMarkupFormatter.escapeHtml(ctx.brandName());
        String escapedRouteManifestJson = seoMarkupFormatter.escapeInlineScriptJson(ctx.routeManifestJson());

        seoMetadataDevValidator.validateSpaHead(
            fullTitle,
            normalizedDescription,
            normalizedCanonical,
            absoluteOgImage,
            resolvedOpenGraphType,
            ctx.brandName(),
            OPEN_GRAPH_IMAGE_TYPE,
            OPEN_GRAPH_IMAGE_WIDTH,
            OPEN_GRAPH_IMAGE_HEIGHT,
            TWITTER_CARD_TYPE
        );

        String structuredDataJson = effectiveMetadata.structuredDataJson();
        String escapedStructuredData = seoMarkupFormatter.escapeInlineScriptJson(structuredDataJson);
        String simpleAnalyticsTag = simpleAnalyticsEnabled ? "<script async src=\"%s\"></script>".formatted(simpleAnalyticsScriptUrl) : "";
        String clickyTag = clickyEnabled ? buildClickyScriptTag() : "";

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
              <meta name="twitter:card" content="%s">
              <meta name="twitter:domain" content="findmybook.net">
              <meta name="twitter:url" content="%s">
              <meta name="twitter:title" content="%s">
              <meta name="twitter:description" content="%s">
              <meta name="twitter:image" content="%s">
              <meta name="twitter:image:alt" content="%s">
              <script type="application/ld+json">%s</script>
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
              <link href="https://fonts.googleapis.com/css2?family=Crimson+Pro:wght@400&family=Inter:wght@400;500;600;700&family=Poppins:wght@500;600;700&display=swap" rel="stylesheet">
              <link rel="icon" href="/favicon.svg" type="image/svg+xml">
              <link rel="icon" href="/favicon-32x32.png" type="image/png" sizes="32x32">
              <link rel="icon" href="/favicon-192x192.png" type="image/png" sizes="192x192">
              <link rel="apple-touch-icon" href="/apple-touch-icon.png">
              <link rel="manifest" href="/site.webmanifest">
              <link rel="sitemap" type="application/xml" title="Sitemap" href="/sitemap.xml">
              <link rel="stylesheet" href="/frontend/app.css">
              %s
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
            escapedThemeColor,
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
            OPEN_GRAPH_IMAGE_WIDTH_VALUE,
            OPEN_GRAPH_IMAGE_HEIGHT_VALUE,
            escapedOgImageAlt,
            openGraphExtensionTags,
            TWITTER_CARD_TYPE,
            escapedCanonicalUrl,
            escapedTitle,
            escapedDescription,
            escapedOgImage,
            escapedOgImageAlt,
            escapedStructuredData,
            simpleAnalyticsTag + clickyTag,
            escapedRouteManifestJson
        );
    }

    /**
     * Builds the Clicky analytics script tag using direct CDN loading.
     */
    private String buildClickyScriptTag() {
        return "<script async data-id=\"%s\" src=\"%s\"></script>".formatted(
            seoMarkupFormatter.escapeHtml(clickySiteId),
            CLICKY_SCRIPT_URL
        );
    }
}
