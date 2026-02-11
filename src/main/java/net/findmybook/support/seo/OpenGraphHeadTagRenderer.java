package net.findmybook.support.seo;

import java.util.List;
import net.findmybook.domain.seo.OpenGraphProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders route-level Open Graph extension properties into HTML head tags.
 */
@Component
public class OpenGraphHeadTagRenderer {

    private final SeoMarkupFormatter seoMarkupFormatter;

    public OpenGraphHeadTagRenderer(SeoMarkupFormatter seoMarkupFormatter) {
        this.seoMarkupFormatter = seoMarkupFormatter;
    }

    /**
     * Renders escaped Open Graph properties as one-or-more {@code <meta property="...">} tags.
     *
     * @param properties ordered Open Graph property/value pairs
     * @return newline-prefixed HTML tag fragment, or an empty string when no values are renderable
     */
    public String renderMetaTags(List<OpenGraphProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (OpenGraphProperty property : properties) {
            if (property == null
                || !StringUtils.hasText(property.property())
                || !StringUtils.hasText(property.content())) {
                continue;
            }
            builder.append("\n              <meta property=\"")
                .append(seoMarkupFormatter.escapeHtml(property.property().trim()))
                .append("\" content=\"")
                .append(seoMarkupFormatter.escapeHtml(property.content().trim()))
                .append("\">");
        }
        return builder.toString();
    }
}
