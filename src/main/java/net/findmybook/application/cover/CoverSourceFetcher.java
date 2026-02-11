package net.findmybook.application.cover;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import net.findmybook.exception.CoverProcessingException;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.service.GoogleApiFetcher;
import net.findmybook.service.image.S3BookCoverService;
import net.findmybook.support.cover.CoverImageUrlSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the specific interactions with external cover APIs (Open Library, Google Books, Longitood).
 * Encapsulates source-specific logic, error handling/classification, and retry policies.
 */
@Component
public class CoverSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(CoverSourceFetcher.class);

    // ── Constants ───────────────────────────────────────────────────────
    static final String SRC_OPEN_LIBRARY = "OPEN_LIBRARY";
    static final String SRC_GOOGLE_BOOKS = "GOOGLE_BOOKS";
    static final String SRC_LONGITOOD = "LONGITOOD";

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final int MAX_RETRIES_PER_API = 3;

    private final S3BookCoverService s3BookCoverService;
    private final GoogleApiFetcher googleApiFetcher;
    private final WebClient webClient;

    public CoverSourceFetcher(S3BookCoverService s3BookCoverService,
                              GoogleApiFetcher googleApiFetcher,
                              WebClient.Builder webClientBuilder) {
        this.s3BookCoverService = s3BookCoverService;
        this.googleApiFetcher = googleApiFetcher;
        this.webClient = webClientBuilder.build();
    }

    // ── Open Library ────────────────────────────────────────────────────

    SourceAttemptResult tryOpenLibrary(String isbn, String bookId) {
        String url = "https://covers.openlibrary.org/b/isbn/" + isbn + "-L.jpg?default=false";
        return tryUploadWithRetry(url, bookId, SRC_OPEN_LIBRARY);
    }

    // ── Google Books ────────────────────────────────────────────────────

    SourceAttemptResult tryGoogleBooks(String isbn, String bookId) {
        try {
            JsonNode response = googleApiFetcher
                .searchVolumesAuthenticated("isbn:" + isbn, 0, "relevance", null, 1)
                .onErrorResume(e -> googleApiFetcher.searchVolumesUnauthenticated(
                    "isbn:" + isbn, 0, "relevance", null, 1))
                .block(Duration.ofSeconds(15));

            if (response == null || !response.has("items") || !response.get("items").isArray()
                    || response.get("items").isEmpty()) {
                return SourceAttemptResult.notFound("google-books: no volumes matched isbn query");
            }

            JsonNode volumeInfo = response.get("items").get(0).path("volumeInfo");
            JsonNode imageLinks = volumeInfo.path("imageLinks");
            if (imageLinks.isMissingNode() || imageLinks.isEmpty()) {
                return SourceAttemptResult.notFound("google-books: matched volume has no imageLinks");
            }

            Map<String, String> links = new HashMap<>();
            imageLinks.properties().forEach(entry ->
                links.put(entry.getKey(), entry.getValue().asString()));

            String bestUrl = CoverImageUrlSelector.selectPreferredImageUrl(links);
            if (!StringUtils.hasText(bestUrl)) {
                return SourceAttemptResult.notFound("google-books: imageLinks did not provide a usable URL");
            }

            bestUrl = upgradeGoogleBooksImageUrl(bestUrl);
            return tryUploadWithRetry(bestUrl, bookId, SRC_GOOGLE_BOOKS);

        } catch (WebClientException | JacksonException | IllegalStateException ex) {
            log.debug("Google Books search failed for ISBN {}", isbn, ex);
            // WebClientException: HTTP errors or connection issues
            // JacksonException: JSON parsing issues
            // IllegalStateException: timeout from block()
            return SourceAttemptResult.failure("google-books search failed: " + summarizeThrowable(ex));
        }
    }

    /**
     * Upgrades a Google Books thumbnail URL for maximum resolution:
     * zoom=0 returns the largest available image, edge=curl is cosmetic noise.
     */
    static String upgradeGoogleBooksImageUrl(String url) {
        if (url == null) return null;
        String upgraded = url.replaceAll("zoom=\\d+", "zoom=0")
                             .replace("&edge=curl", "")
                             .replace("?edge=curl&", "?");
        if (upgraded.startsWith("http://")) {
            upgraded = "https://" + upgraded.substring(7);
        }
        return upgraded;
    }

    // ── Longitood ───────────────────────────────────────────────────────

    SourceAttemptResult tryLongitood(String isbn, String bookId) {
        try {
            Map<String, String> response = webClient.get()
                .uri("https://bookcover.longitood.com/bookcover/" + isbn)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                .block(Duration.ofSeconds(10));

            if (response == null || !StringUtils.hasText(response.get("url"))) {
                return SourceAttemptResult.notFound("longitood: response did not include cover URL");
            }
            return tryUploadWithRetry(response.get("url"), bookId, SRC_LONGITOOD);

        } catch (WebClientResponseException.NotFound ex) {
            return SourceAttemptResult.notFound("longitood: no cover found (404)");
        } catch (WebClientException | IllegalStateException ex) {
            log.debug("Longitood request failed for ISBN {}", isbn, ex);
            // WebClientException: other HTTP errors or connection issues
            // IllegalStateException: timeout from block()
            return SourceAttemptResult.failure("longitood request failed: " + summarizeThrowable(ex));
        }
    }

    // ── Upload with retry ───────────────────────────────────────────────

    private SourceAttemptResult tryUploadWithRetry(String imageUrl, String bookId, String source) {
        for (int attempt = 0; attempt <= MAX_RETRIES_PER_API; attempt++) {
            try {
                ImageDetails result = s3BookCoverService
                    .uploadCoverToS3Async(imageUrl, bookId, source)
                    .block(Duration.ofSeconds(30));
                if (result != null) {
                    return SourceAttemptResult.success("uploaded from " + source + " using " + imageUrl);
                }
                return SourceAttemptResult.failure("upload returned empty details from " + source + " using " + imageUrl);

            } catch (WebClientException | CoverProcessingException | IllegalStateException ex) {
                log.debug("Upload failed for book {} from source {} (attempt {})", bookId, source, attempt, ex);
                // WebClientException: HTTP/Net errors during fetch/upload
                // CoverProcessingException: Business logic rejection (e.g. placeholder) or S3/Infra error
                // IllegalStateException: timeout from block()
                
                String msg = summarizeThrowable(ex);
                if (isRateLimited(ex) && attempt < MAX_RETRIES_PER_API) {
                    Duration backoff = calculateBackoff(attempt);
                    log.info("Rate limited by {} for book {}, backing off {}s (attempt {}/{})",
                        source, bookId, backoff.toSeconds(), attempt + 1, MAX_RETRIES_PER_API);
                    sleepSafely(backoff);
                    continue;
                }
                if (isNotFoundResponse(ex) || isLikelyNoCoverImageFailure(ex)) {
                    return SourceAttemptResult.notFound("no usable cover content from " + source + " (" + msg + ")");
                }
                return SourceAttemptResult.failure("upload failed from " + source + ": " + msg);
            }
        }
        return SourceAttemptResult.failure("rate-limit retries exhausted for " + source + " (" + imageUrl + ")");
    }

    // ── Utilities ───────────────────────────────────────────────────────

    static boolean isRateLimited(Throwable ex) {
        if (containsHttpStatus(ex, 429)) return true;
        String msg = summarizeThrowable(ex);
        return msg != null && (msg.contains("rate-limited") || msg.contains("rate limit")
            || msg.contains("RateLimiter"));
    }

    static boolean isNotFoundResponse(Throwable ex) {
        return containsHttpStatus(ex, 404);
    }

    static boolean isLikelyNoCoverImageFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof CoverProcessingException cpe && cpe.isNoCoverAvailable()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsHttpStatus(Throwable throwable, int statusCode) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException
                && responseException.getStatusCode().value() == statusCode) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static String summarizeThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return throwable != null ? throwable.getClass().getSimpleName() : "unknown";
    }

    static Duration calculateBackoff(int attempt) {
        long millis = INITIAL_BACKOFF.toMillis() * (1L << attempt);
        return Duration.ofMillis(Math.min(millis, MAX_BACKOFF.toMillis()));
    }

    private void sleepSafely(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("Cover fetcher sleep interrupted");
        }
    }
}
