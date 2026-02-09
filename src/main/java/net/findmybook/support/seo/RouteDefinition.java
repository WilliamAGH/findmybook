package net.findmybook.support.seo;

import java.util.List;
import java.util.Map;

/**
 * Single route-definition contract entry for public SPA routes.
 *
 * @param name stable route identifier consumed by frontend routing logic
 * @param matchType matching mode (for example: exact or regex)
 * @param pattern route path or regex pattern
 * @param paramNames ordered extracted path parameters
 * @param defaults default query/path parameter values
 * @param allowedQueryParams allowlisted query parameter names
 * @param canonicalPathTemplate canonical template used for route normalization
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
}
