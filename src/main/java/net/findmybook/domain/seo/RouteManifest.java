package net.findmybook.domain.seo;

import java.util.List;

/**
 * Domain contract for the public SPA route manifest.
 *
 * @param version manifest schema version
 * @param publicRoutes public route definitions available to SPA clients
 * @param passthroughPrefixes backend-owned path prefixes excluded from SPA interception
 */
public record RouteManifest(
    int version,
    List<RouteDefinition> publicRoutes,
    List<String> passthroughPrefixes
) {

    /**
     * Normalizes collection fields to immutable values.
     */
    public RouteManifest {
        publicRoutes = publicRoutes == null ? List.of() : List.copyOf(publicRoutes);
        passthroughPrefixes = passthroughPrefixes == null ? List.of() : List.copyOf(passthroughPrefixes);
    }
}

