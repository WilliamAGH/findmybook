/**
 * Service for interacting with the Google Books API
 * This class is responsible for all direct communication with the Google Books API
 * It handles:
 * - Constructing and executing API requests for searching books and retrieving book details
 * - Applying rate limiting (via Resilience4j {@code @RateLimiter}) and circuit breaking ({@code @CircuitBreaker})
 *   to protect the application and the external API
 * - Converting JSON responses from the Google Books API into {@link Book} domain objects
 * - Providing normalized output for the Postgres-first caching pipeline
 * - Monitoring API usage and performance via {@link ApiRequestMonitor}
 * - Transforming cover image URLs for optimal quality and display
 *
 * @author William Callahan
 */
package net.findmybook.service;

import tools.jackson.databind.JsonNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import reactor.core.publisher.Mono;
import java.util.Objects;

@Service
@Slf4j
public class GoogleBooksService {

    private final ApiRequestMonitor apiRequestMonitor;
    private final GoogleApiFetcher googleApiFetcher;
    private final GoogleBooksMapper googleBooksMapper;

    /**
     * Constructs a GoogleBooksService with necessary dependencies.
     *
     * @param apiRequestMonitor Service for monitoring API usage metrics
     * @param googleApiFetcher Service for direct Google API calls
     * @param googleBooksMapper Mapper for converting Google Books JSON into domain aggregates
     */
    public GoogleBooksService(
            ApiRequestMonitor apiRequestMonitor,
            GoogleApiFetcher googleApiFetcher,
            GoogleBooksMapper googleBooksMapper) {
        this.apiRequestMonitor = apiRequestMonitor;
        this.googleApiFetcher = googleApiFetcher;
        this.googleBooksMapper = googleBooksMapper;
    }

    /**
     * Performs a search against the Google Books API using GoogleApiFetcher.
     * This is a lower-level method that fetches a single page of results.
     * It is protected by rate limiting and circuit breaking.
     *
     * @param query Search query string
     * @param startIndex Starting index for pagination (0-based)
     * @param orderBy Result ordering preference (e.g., "newest", "relevance")
     * @param langCode Optional language code filter (e.g., "en", "fr") to restrict search results
     * @return Mono containing the raw JsonNode response from the API for a single page
     */
    @CircuitBreaker(name = "googleBooksService", fallbackMethod = "searchBooksFallback")
    @TimeLimiter(name = "googleBooksService")
    @RateLimiter(name = "googleBooksServiceRateLimiter", fallbackMethod = "searchBooksRateLimitFallback")
    public Mono<JsonNode> searchBooks(String query, int startIndex, String orderBy, String langCode) {
        log.debug("GoogleBooksService.searchBooks called for query: {}, startIndex: {}. Delegating to GoogleApiFetcher.", query, startIndex);
        return googleApiFetcher.searchVolumesAuthenticated(query, startIndex, orderBy, langCode)
            .doOnNext(response -> apiRequestMonitor.recordSuccessfulRequest(
                "volumes/search/authenticated?query=" + query + "&startIndex=" + startIndex + "&orderBy=" + orderBy + "&langCode=" + langCode
            ))
            .doOnError(e -> apiRequestMonitor.recordFailedRequest(
                "volumes/search/authenticated?query=" + query + "&startIndex=" + startIndex,
                e.getMessage()));
    }

    /**
     * Fetches the Google Books ID for a given ISBN.
     * This method is specifically for the NYT scheduler to minimize data transfer
     * when only the ID is needed.
     *
     * @param isbn The ISBN (10 or 13) to search for
     * @return Mono emitting the Google Books ID, or empty if not found or error
     */
    @RateLimiter(name = "googleBooksServiceRateLimiter")
    public Mono<String> fetchGoogleBookIdByIsbn(String isbn) {
        log.debug("Fetching Google Book ID for ISBN: {}", isbn);
        return searchBooks("isbn:" + isbn, 0, "relevance", null)
            .map(responseNode -> {
                if (responseNode != null && responseNode.has("items") && responseNode.get("items").isArray() && responseNode.get("items").size() > 0) {
                    JsonNode firstItem = responseNode.get("items").get(0);
                    if (firstItem.has("id")) {
                        String googleId = firstItem.get("id").asText();
                        log.info("Found Google Book ID: {} for ISBN: {}", googleId, isbn);
                        return googleId;
                    }
                }
                log.warn("No Google Book ID found for ISBN: {}", isbn);
                return null;
            })
            .filter(Objects::nonNull)
            .doOnError(e -> {
                LoggingUtils.error(log, e, "Error fetching Google Book ID for ISBN {}", isbn);
                apiRequestMonitor.recordFailedRequest("volumes/search/isbn_to_id/" + isbn, e.getMessage());
            })
            .onErrorMap(e -> new IllegalStateException("Failed to fetch Google Book ID for ISBN " + isbn, e));
    }

    /**
     * Fallback method for searchBooks when circuit breaker is triggered.
     * Provides explicit failure when Google Books API is unavailable.
     *
     * @param query Search query that triggered the circuit breaker
     * @param startIndex Pagination index being accessed
     * @param orderBy Sort order requested
     * @param langCode Language filter if any
     * @param t The throwable that triggered the circuit breaker
     * @return Mono.error wrapping the circuit breaker cause
     */
    public Mono<JsonNode> searchBooksFallback(String query, int startIndex, String orderBy, String langCode, Throwable t) {
        log.warn("GoogleBooksService.searchBooks circuit breaker opened for query: '{}', startIndex: {}. Error: {}",
            query, startIndex, t.getMessage());

        String queryType = "general";
        if (query.contains("intitle:")) queryType = "title";
        else if (query.contains("inauthor:")) queryType = "author";
        else if (query.contains("isbn:")) queryType = "isbn";
        String apiEndpoint = "volumes/search/" + queryType + "/authenticated";

        apiRequestMonitor.recordFailedRequest(apiEndpoint, "Circuit breaker opened for query: '" + query + "', startIndex: " + startIndex + ": " + t.getMessage());

        return Mono.error(new IllegalStateException(
            "GoogleBooks circuit breaker fallback triggered for query '" + query + "' startIndex " + startIndex,
            t
        ));
    }

    /**
     * Fallback method for searchBooks when rate limit is exceeded.
     * Provides explicit failure when too many requests are made within a time period.
     *
     * @param query Search query that triggered the rate limiter
     * @param startIndex Pagination index being accessed
     * @param orderBy Sort order requested
     * @param langCode Language filter if any
     * @param t The throwable from the rate limiter
     * @return Mono.error wrapping the rate limit cause
     */
    public Mono<JsonNode> searchBooksRateLimitFallback(String query, int startIndex, String orderBy, String langCode, Throwable t) {
        log.warn("GoogleBooksService.searchBooks rate limit exceeded for query: '{}', startIndex: {}. Error: {}",
            query, startIndex, t.getMessage());

        apiRequestMonitor.recordMetric("api/rate-limited", "API call rate limited for search: " + query + " (via GoogleBooksService)");

        return Mono.error(new IllegalStateException(
            "GoogleBooks rate limit fallback triggered for query '" + query + "' startIndex " + startIndex,
            t
        ));
    }

    private Book convertJsonToBook(JsonNode item) {
        if (item == null) {
            return null;
        }

        try {
            BookAggregate aggregate = googleBooksMapper.map(item);
            Book book = BookDomainMapper.fromAggregate(aggregate);
            if (book != null) {
                book.setRawJsonResponse(item.toString());
            }
            return book;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to map Google Books JSON node into domain book", ex);
        }
    }
}
