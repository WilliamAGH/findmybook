package net.findmybook.support.seo;

import net.findmybook.domain.seo.SeoMetadata;

/**
 * Bundles the presentation defaults and environment values needed to render a
 * server-side SPA shell document, keeping the renderer's parameter list small.
 *
 * @param metadata            route-specific SEO metadata (may be {@code null})
 * @param fallbackMetadata    fallback metadata when route-specific is absent
 * @param pageTitleSuffix     suffix appended to rendered page titles
 * @param brandName           canonical brand token for meta tags
 * @param defaultDescription  fallback description when route-specific is absent
 * @param defaultKeywords     fallback keywords when route-specific is absent
 * @param defaultRobots       fallback robots directive
 * @param defaultOpenGraphType fallback OpenGraph type (e.g. {@code website})
 * @param openGraphImageAlt   alt text for the OG image
 * @param themeColor           browser theme-color value
 * @param routeManifestJson   serialized route manifest for client hydration
 * @param baseUrl             canonical base URL for absolute link resolution
 */
public record SpaShellRenderContext(
    SeoMetadata metadata,
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
    String baseUrl
) {
}
