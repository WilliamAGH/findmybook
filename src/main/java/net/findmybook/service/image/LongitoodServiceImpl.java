/**
 * Implementation of the Longitood service for retrieving high-quality book cover images
 * 
 * @author William Callahan
 * 
 * Features:
 * - Integrates with external Longitood API to fetch book cover images
 * - Implements circuit breaker, rate limiting, and timeout protections
 * - Handles image resolution preferences and source attribution
 * - Provides fallback mechanisms when service is unavailable
 * - Supports ISBN-based lookups for precise image matching
 */
package net.findmybook.service.image;

import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.util.ValidationUtils;
import net.findmybook.util.cover.CoverIdentifierResolver;
// LongitoodService is in the same package; explicit import not required

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class LongitoodServiceImpl implements LongitoodService { 

    private static final Logger logger = LoggerFactory.getLogger(LongitoodServiceImpl.class);
    private static final String LONGITOOD_SOURCE_NAME = "Longitood";

    private final WebClient webClient;

    /**
     * Builds a {@link WebClient} using the shared builder configured in {@link net.findmybook.config.WebClientConfig}.
     * Individual services can still customize the client before executing requests while inheriting the standard timeouts and codecs.
     *
     * @param webClientBuilder pre-configured builder supplied by the application context
     */
    public LongitoodServiceImpl(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Fetches book cover image from Longitood API
     *
     * @param book The book to fetch a cover for
     * @return A CompletableFuture emitting Optional<ImageDetails> if a cover is found, or an empty Optional if not available
     */
    @Override
    @CircuitBreaker(name = "longitoodService", fallbackMethod = "fetchCoverFallback")
    @TimeLimiter(name = "longitoodService")
    @RateLimiter(name = "longitoodServiceRateLimiter", fallbackMethod = "fetchCoverRateLimitFallback")
    public CompletableFuture<Optional<ImageDetails>> fetchCover(Book book) {
        String isbn = CoverIdentifierResolver.getPreferredIsbn(book);
        if (!ValidationUtils.hasText(isbn)) {
            logger.warn("No ISBN found for book ID: {}, cannot fetch cover from Longitood", book.getId());
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String apiUrl = "https://bookcover.longitood.com/bookcover/" + isbn;
        final String finalIsbn = isbn; 

        // IMPORTANT: Do NOT use onErrorResume() to swallow exceptions here.
        // Exceptions must propagate so the @CircuitBreaker annotation can intercept them.
        // The fallback methods (fetchCoverFallback, fetchCoverRateLimitFallback) handle
        // returning empty results when the circuit opens or rate limit is exceeded.
        //
        // Only 404 (not found) is handled gracefully as an expected "no cover available" case.
        Mono<ImageDetails> imageDetailsMono = webClient.get()
            .uri(apiUrl)
            .retrieve()
            .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {})
            .flatMap(response -> {
                if (response != null && response.containsKey("url")) {
                    String coverUrl = response.get("url");
                    if (coverUrl != null && !coverUrl.isEmpty()) {
                        logger.debug("Found cover URL from Longitood API for book {}: {}", book.getId(), coverUrl);
                        String sourceSystemId = String.format("%s-%s", LONGITOOD_SOURCE_NAME, finalIsbn);
                        ImageDetails imageDetails = new ImageDetails(
                            coverUrl,
                            LONGITOOD_SOURCE_NAME,
                            sourceSystemId,
                            CoverImageSource.LONGITOOD,
                            ImageResolutionPreference.ORIGINAL
                        );
                        return Mono.just(imageDetails);
                    }
                }
                logger.debug("No valid cover URL found from Longitood API for book {}", book.getId());
                return Mono.empty();
            })
            // Only handle 404 gracefully - this is expected when no cover exists
            .onErrorResume(WebClientResponseException.NotFound.class, e -> {
                logger.debug("No cover found at Longitood for book {} (404)", book.getId());
                return Mono.empty();
            })
            // Log non-404 errors before they propagate to trip the circuit breaker.
            // Note: 404 errors are already handled above, so doOnError only sees other failures.
            .doOnError(e -> logger.warn(
                "Longitood API error for book {} (will propagate to circuit breaker): {}",
                book.getId(), e.getMessage()));

        // Convert Mono<ImageDetails> to CompletableFuture<Optional<ImageDetails>>
        // Errors will propagate and be handled by Resilience4j fallback methods
        return imageDetailsMono
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .toFuture();
    }

    /**
     * Fallback method for fetchCover when circuit breaker is triggered
     * - Handles when the Longitood service is degraded or unavailable
     * - Returns empty result to prevent cascade failures
     *
     * @param book The book that triggered the circuit breaker
     * @param t The throwable from the circuit breaker
     * @return Empty Optional wrapped in CompletableFuture
     */
    public CompletableFuture<Optional<ImageDetails>> fetchCoverFallback(Book book, Throwable t) {
        String isbn = CoverIdentifierResolver.getPreferredIsbn(book);
        logger.warn("LongitoodService.fetchCover circuit breaker opened for book ID: {}, ISBN: {}. Error: {}", 
            book.getId(), isbn, t.getMessage());
        return CompletableFuture.completedFuture(Optional.empty()); // Return empty Optional in CompletableFuture
    }
    
    /**
     * Fallback method for fetchCover when rate limit is exceeded
     * - Handles when too many Longitood API requests are made in the time period
     * - Logs the rate limiting event for monitoring
     * - Returns empty result to prevent excessive API calls
     *
     * @param book The book that triggered the rate limiter
     * @param t The throwable from the rate limiter
     * @return Empty Optional wrapped in CompletableFuture
     */
    public CompletableFuture<Optional<ImageDetails>> fetchCoverRateLimitFallback(Book book, Throwable t) {
        String isbn = CoverIdentifierResolver.getPreferredIsbn(book);
        logger.warn("LongitoodService.fetchCover rate limit exceeded for book ID: {}, ISBN: {}. Error: {}", 
            book.getId(), isbn, t.getMessage());
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
