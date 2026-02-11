package net.findmybook.support.seo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import net.findmybook.service.image.CoverUrlSafetyValidator;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.cover.CoverUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Loads and normalizes cover images for OpenGraph composition.
 *
 * <p>This loader encapsulates classpath/remote fetch concerns, SSRF allow-list
 * checks, payload limits, and image normalization so rendering code can remain
 * focused on card layout concerns.
 */
@Component
public class BookOpenGraphCoverImageLoader {

    private static final Logger log = LoggerFactory.getLogger(BookOpenGraphCoverImageLoader.class);

    private static final long MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;
    private static final long MAX_INPUT_PIXELS = 40_000_000L;
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final Duration DEFAULT_REMOTE_FETCH_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final CoverUrlSafetyValidator coverUrlSafetyValidator;
    private final Duration remoteFetchTimeout;

    public BookOpenGraphCoverImageLoader() {
        this(
            WebClient.builder(),
            new CanonicalUrlResolver(),
            new CoverUrlSafetyValidator(),
            DEFAULT_REMOTE_FETCH_TIMEOUT
        );
    }

    @Autowired
    public BookOpenGraphCoverImageLoader(WebClient.Builder webClientBuilder,
                                         CanonicalUrlResolver canonicalUrlResolver,
                                         CoverUrlSafetyValidator coverUrlSafetyValidator) {
        this(webClientBuilder, canonicalUrlResolver, coverUrlSafetyValidator, DEFAULT_REMOTE_FETCH_TIMEOUT);
    }

    BookOpenGraphCoverImageLoader(WebClient.Builder webClientBuilder,
                                  CanonicalUrlResolver canonicalUrlResolver,
                                  CoverUrlSafetyValidator coverUrlSafetyValidator,
                                  Duration remoteFetchTimeout) {
        this.webClient = webClientBuilder.build();
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.coverUrlSafetyValidator = coverUrlSafetyValidator;
        this.remoteFetchTimeout = remoteFetchTimeout != null ? remoteFetchTimeout : DEFAULT_REMOTE_FETCH_TIMEOUT;
    }

    /**
     * Loads an OpenGraph cover candidate into a normalized RGB image.
     *
     * @param candidateCoverUrl resolved cover URL candidate
     * @return decoded and normalized image, or {@code null} when unavailable/unsafe
     */
    public BufferedImage load(String candidateCoverUrl) {
        if (!StringUtils.hasText(candidateCoverUrl)) {
            return null;
        }

        BufferedImage localImage = readClasspathImage(candidateCoverUrl);
        if (localImage != null) {
            return localImage;
        }

        String absoluteUrl = canonicalUrlResolver.normalizePublicUrl(candidateCoverUrl);
        URI coverUri = parseUri(absoluteUrl);
        if (coverUri == null) {
            return null;
        }
        if (!isAllowedRemoteCover(coverUri)) {
            log.warn("Blocked OpenGraph cover fetch for host {}", coverUri.getHost());
            return null;
        }
        return fetchRemoteImage(coverUri);
    }

    private BufferedImage readClasspathImage(String candidate) {
        if (!candidate.startsWith("/")) {
            return null;
        }
        ClassPathResource resource = new ClassPathResource("static" + candidate);
        if (!resource.exists()) {
            return null;
        }
        try {
            return normalizeDecodedImage(ImageIO.read(resource.getInputStream()));
        } catch (IOException ioException) {
            log.warn("Failed to read classpath image {} for OpenGraph rendering", candidate, ioException);
            return null;
        }
    }

    private URI parseUri(String candidate) {
        try {
            URI parsed = new URI(candidate);
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            if (!HTTP_SCHEME.equals(scheme) && !HTTPS_SCHEME.equals(scheme)) {
                return null;
            }
            return parsed;
        } catch (URISyntaxException invalidUri) {
            log.warn("Invalid OpenGraph cover URI {}", candidate, invalidUri);
            return null;
        }
    }

    private boolean isAllowedRemoteCover(URI uri) {
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return false;
        }
        if (CoverUrlResolver.isCdnUrl(uri.toString())) {
            return true;
        }
        String publicHost = URI.create(ApplicationConstants.Urls.BASE_URL).getHost();
        if (host.equalsIgnoreCase(publicHost)) {
            return true;
        }
        return coverUrlSafetyValidator.isAllowedImageUrl(uri.toString());
    }

    private BufferedImage fetchRemoteImage(URI imageUri) {
        Mono<byte[]> imageBytesMono = webClient.get()
            .uri(imageUri)
            .exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful()) {
                    return Mono.empty();
                }
                MediaType contentType = response.headers().contentType().orElse(null);
                if (contentType == null || !"image".equalsIgnoreCase(contentType.getType())) {
                    return Mono.empty();
                }
                long contentLength = response.headers().contentLength().orElse(-1L);
                if (contentLength > MAX_DOWNLOAD_BYTES) {
                    return Mono.empty();
                }
                return response.bodyToMono(byte[].class);
            })
            .timeout(remoteFetchTimeout)
            .onErrorResume(WebClientRequestException.class, error -> {
                log.warn("OpenGraph cover request failed for {}", imageUri, error);
                return Mono.empty();
            })
            .onErrorResume(WebClientResponseException.class, error -> {
                log.warn("OpenGraph cover response failed for {}", imageUri, error);
                return Mono.empty();
            })
            .onErrorResume(TimeoutException.class, error -> {
                log.warn("OpenGraph cover request timed out for {}", imageUri, error);
                return Mono.empty();
            });

        byte[] imageBytes = imageBytesMono.blockOptional().orElse(null);
        if (imageBytes == null || imageBytes.length == 0 || imageBytes.length > MAX_DOWNLOAD_BYTES) {
            return null;
        }

        try {
            return normalizeDecodedImage(ImageIO.read(new ByteArrayInputStream(imageBytes)));
        } catch (IOException ioException) {
            log.warn("Failed to decode OpenGraph cover payload from {}", imageUri, ioException);
            return null;
        }
    }

    private BufferedImage normalizeDecodedImage(BufferedImage image) {
        if (image == null) {
            return null;
        }
        long pixels = (long) image.getWidth() * (long) image.getHeight();
        if (pixels <= 0 || pixels > MAX_INPUT_PIXELS) {
            return null;
        }
        BufferedImage normalized = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }
}
