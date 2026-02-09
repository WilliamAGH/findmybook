package net.findmybook.domain.seo;

import java.util.List;
import java.util.Map;

/**
 * Describes one public route contract entry exposed to SPA routing clients.
 *
 * @param name stable route name consumed by client-side route matching
 * @param matchType matching strategy ({@code exact} or {@code regex})
 * @param pattern literal route path or regex pattern
 * @param paramNames ordered path parameter names for matched groups
 * @param defaults default parameter values used for canonicalization
 * @param allowedQueryParams allowlisted query-string parameter names
 * @param canonicalPathTemplate canonical route template
 */
public record RouteDefinition(
    String name,
    String matchType,
    String pattern,
    List<String> paramNames,
    Map<String, String> defaults,
    List<String> allowedQueryParams,
    String canonicalPathTemplate
) {

    /**
     * Enforces immutable collection fields for downstream adapter safety.
     */
    public RouteDefinition {
        paramNames = paramNames == null ? List.of() : List.copyOf(paramNames);
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
        allowedQueryParams = allowedQueryParams == null ? List.of() : List.copyOf(allowedQueryParams);
    }
}

