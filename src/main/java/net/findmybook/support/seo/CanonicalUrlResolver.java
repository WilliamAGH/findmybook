package net.findmybook.support.seo;

import java.util.Locale;
import net.findmybook.util.ApplicationConstants;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Canonicalizes route-relative and absolute URLs to stable public URL values.
 */
@Component
public class CanonicalUrlResolver {

    private static final String HTTP_SCHEME_PREFIX = "http";

    /**
     * Returns an absolute canonical URL for a route-relative or already absolute candidate.
     *
     * @param candidate route-relative path (for example {@code /book/foo}) or absolute URL
     * @return canonical absolute URL anchored to {@link ApplicationConstants.Urls#BASE_URL}
     */
    public String normalizePublicUrl(String candidate) {
        String raw = StringUtils.hasText(candidate) ? candidate.trim() : ApplicationConstants.Urls.BASE_URL + "/";
        if (raw.toLowerCase(Locale.ROOT).startsWith(HTTP_SCHEME_PREFIX)) {
            return raw;
        }
        if (!raw.startsWith("/")) {
            raw = "/" + raw;
        }
        return ApplicationConstants.Urls.BASE_URL + raw;
    }
}
