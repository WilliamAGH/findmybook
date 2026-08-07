package net.findmybook.support.seo;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import net.findmybook.util.ApplicationConstants;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

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

    /**
     * Builds an encoded redirect location while expanding raw path and query values exactly once.
     *
     * @param builder route template whose variable placeholders identify values to encode
     * @param uriVariables raw path and query values keyed by their template names
     * @return encoded relative redirect URI
     */
    public URI encodedLocation(UriComponentsBuilder builder, Map<String, String> uriVariables) {
        return URI.create(builder.encode(StandardCharsets.UTF_8)
            .buildAndExpand(uriVariables)
            .toUriString());
    }
}
