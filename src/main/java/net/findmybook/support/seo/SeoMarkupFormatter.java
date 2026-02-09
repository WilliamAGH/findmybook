package net.findmybook.support.seo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Formats and escapes SEO head values before shell rendering.
 */
@Component
public class SeoMarkupFormatter {

    /**
     * Returns {@code candidate} when it has text; otherwise returns {@code fallback}.
     */
    public String fallbackText(String candidate, String fallback) {
        return StringUtils.hasText(candidate) ? candidate : fallback;
    }

    /**
     * Appends the configured page-title suffix unless already present.
     */
    public String pageTitle(String title, String titleSuffix, String fallbackTitle) {
        String baseTitle = fallbackText(title, fallbackTitle);
        if (baseTitle.endsWith(titleSuffix)) {
            return baseTitle;
        }
        return baseTitle + titleSuffix;
    }

    /**
     * Escapes an HTML attribute/content value for safe interpolation into the shell template.
     */
    public String escapeHtml(String text) {
        Objects.requireNonNull(text, "HTML escape input must not be null");
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * Escapes script-breaking characters for inline JSON script tags.
     */
    public String escapeInlineScriptJson(String text) {
        Objects.requireNonNull(text, "Inline script JSON escape input must not be null");
        return text
            .replace("<", "\\u003c")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029");
    }

    /**
     * Trims, deduplicates, and limits a list of text values for SEO output fields.
     *
     * @param values raw input values (may contain blanks, duplicates, or nulls)
     * @param limit maximum number of values to return
     * @return deduplicated, trimmed, immutable list capped at {@code limit} entries
     */
    public List<String> normalizeTextValues(List<String> values, int limit) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String entry : values) {
            if (!StringUtils.hasText(entry)) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || normalized.contains(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
            if (normalized.size() >= limit) {
                break;
            }
        }
        return List.copyOf(normalized);
    }
}

