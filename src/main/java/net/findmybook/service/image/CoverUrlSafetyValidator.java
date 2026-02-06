package net.findmybook.service.image;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates external cover URLs against a strict HTTPS host allowlist and
 * rejects internal/private network destinations to reduce SSRF risk.
 */
@Component
@Slf4j
public class CoverUrlSafetyValidator {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "books.googleusercontent.com",
        "covers.openlibrary.org",
        "images-na.ssl-images-amazon.com",
        "images-eu.ssl-images-amazon.com",
        "m.media-amazon.com",
        "images.amazon.com",
        "d1w7fb2mkkr3kw.cloudfront.net",
        "ia600100.us.archive.org",
        "ia800100.us.archive.org",
        "ia601400.us.archive.org",
        "ia800200.us.archive.org",
        "syndetics.com",
        "cdn.penguin.com",
        "images.penguinrandomhouse.com",
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

                String ip = address.getHostAddress();
                if (ip.startsWith("169.254.")
                    || ip.startsWith("127.")
                    || ip.startsWith("10.")
                    || ip.startsWith("192.168.")
                    || ip.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
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
