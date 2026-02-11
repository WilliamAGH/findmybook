package net.findmybook.service.image;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates external cover URLs against a strict HTTPS host allowlist and
 * rejects internal/private network destinations to reduce SSRF risk.
 */
@Component
public class CoverUrlSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(CoverUrlSafetyValidator.class);
    private static final Set<String> RESERVED_IP_PREFIXES = Set.of(
        "169.254.", "127.", "10.", "192.168."
    );
    private static final Pattern RFC_1918_172_RANGE = Pattern.compile("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");

    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "books.google.com",
        "books.googleusercontent.com",
        "covers.openlibrary.org",
        "images-na.ssl-images-amazon.com",
        "images-eu.ssl-images-amazon.com",
        "m.media-amazon.com",
        "images.amazon.com",
        "d1w7fb2mkkr3kw.cloudfront.net",
        "us.archive.org",
        "syndetics.com",
        "cdn.penguin.com",
        "images.penguinrandomhouse.com",
        "static.nytimes.com",
        "static01.nyt.com",
        "longitood.com"
    );

    public boolean isAllowedImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            log.warn("Blank image URL provided, blocking.");
            return false;
        }
        try {
            URI uri = URI.create(imageUrl);
            return isAllowedImageHost(uri);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid image URL format, blocking: {}", imageUrl);
            return false;
        }
    }

    private boolean isAllowedImageHost(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        if (!isInAllowlist(host)) {
            return false;
        }

        return resolvesToPublicAddress(host);
    }

    private boolean isInAllowlist(String host) {
        for (String allowedHost : ALLOWED_HOSTS) {
            if (host.equals(allowedHost) || host.endsWith("." + allowedHost)) {
                return true;
            }
        }
        return false;
    }

    private boolean resolvesToPublicAddress(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()) {
                    log.warn("Blocked private/link-local IP address for host: {} -> {}", host, address.getHostAddress());
                    return false;
                }

                // Defense-in-depth: the JDK checks above cover standard site-local and link-local
                // ranges, but explicit string-prefix and regex checks below guard against edge cases
                // where InetAddress classification may disagree with raw IP formatting (e.g. IPv4-mapped
                // IPv6 addresses or non-standard JVM implementations).
                String ip = address.getHostAddress();
                boolean isReservedPrefix = RESERVED_IP_PREFIXES.stream().anyMatch(ip::startsWith);
                boolean isRfc1918Range = RFC_1918_172_RANGE.matcher(ip).matches();
                if (isReservedPrefix || isRfc1918Range) {
                    log.warn("Blocked reserved IP range for host: {} -> {}", host, ip);
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | SecurityException exception) {
            log.warn("Could not resolve host for validation, blocking: {}", host);
            return false;
        }
    }
}
