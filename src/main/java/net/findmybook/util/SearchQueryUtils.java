package net.findmybook.util;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Utility methods for working with search queries.
 * Centralizes common normalization behavior so controllers, services,
 * caches, and mock helpers stay aligned.
 */
public final class SearchQueryUtils {

    private static final String DEFAULT_QUERY = "*";
    private static final Pattern CACHE_KEY_SANITIZER = Pattern.compile("[^a-zA-Z0-9-_]");

    private SearchQueryUtils() {
        // Utility class
    }

    /**
     * Normalizes queries intended for user-facing search APIs.
     * <p>
     * Behavior mirrors the legacy controller/service helpers: trim whitespace
     * and fall back to {@code "*"} when the incoming query is null or blank.
     */
    public static String normalize(String query) {
        if (query == null) {
            return DEFAULT_QUERY;
        }
        String trimmed = query.trim();
        return trimmed.isEmpty() ? DEFAULT_QUERY : trimmed;
    }

    /**
     * Indicates whether the provided query string represents the wildcard fallback.
     */
    public static boolean isWildcard(String query) {
        return DEFAULT_QUERY.equals(query);
    }

    /**
     * Produces a canonical, case-insensitive representation suitable for
     * map keys and cache lookups. Returns {@code null} only when the input is
     * null so existing callers retain their guard conditions.
     */
    public static String canonicalize(String query) {
        if (query == null) {
            return null;
        }
        return query.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Generates a filesystem and cache-safe key for search responses without
     * any language qualifier, preserving previous sanitization semantics.
     */
    public static String cacheKey(String query) {
        String canonical = Objects.requireNonNullElse(canonicalize(query), "");
        String sanitized = CACHE_KEY_SANITIZER.matcher(canonical).replaceAll("_");
        return sanitized + ".json";
    }

    /**
     * Generates a filesystem and cache-safe key for search responses that are
     * scoped by an optional language code. Mirrors previous behavior by
     * defaulting to {@code "any"} when a language is not provided.
     */
    public static String cacheKey(String query, String langCode) {
        String canonical = Objects.requireNonNullElse(canonicalize(query), "");
        String sanitized = CACHE_KEY_SANITIZER.matcher(canonical).replaceAll("_");
        String normalizedLang = langCode == null ? "" : langCode.trim();
        String langPart;
        if (normalizedLang.isEmpty()) {
            langPart = "any";
        } else {
            String sanitizedLang = CACHE_KEY_SANITIZER.matcher(normalizedLang.toLowerCase(Locale.ROOT)).replaceAll("");
            langPart = sanitizedLang.isEmpty() ? "any" : sanitizedLang;
        }
        return sanitized + "-" + langPart + ".json";
    }
}
