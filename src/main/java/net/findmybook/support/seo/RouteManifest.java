package net.findmybook.support.seo;

import java.util.List;

/**
 * Immutable manifest consumed by SPA clients for route matching and navigation boundaries.
 *
 * @param version manifest contract version
 * @param publicRoutes allowlisted route definitions served by the backend
 * @param passthroughPrefixes backend path prefixes excluded from SPA route interception
 */
public record RouteManifest(
    int version,
    List<RouteDefinition> publicRoutes,
    List<String> passthroughPrefixes
) {
}
