package net.findmybook.support.seo;

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
    public String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * Escapes script-breaking characters for inline JSON script tags.
     */
    public String escapeInlineScriptJson(String value) {
        return value
            .replace("<", "\\u003c")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029");
    }
}

