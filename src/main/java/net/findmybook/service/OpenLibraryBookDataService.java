/**
 * Service for interacting with OpenLibrary's data API to fetch book information.
 *
 * <p>Provides title-based and author-based search against the OpenLibrary Search API,
 * with resilience patterns (circuit breakers and rate limiting) and reactive return types.</p>
 *
 * @author William Callahan
 */
package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.util.ExternalApiLogger;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.LoggingUtils;
import net.findmybook.util.TextUtils;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.PrematureCloseException;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class OpenLibraryBookDataService {

    private final WebClient webClient;
    private final boolean externalFallbackEnabled;

    public OpenLibraryBookDataService(WebClient.Builder webClientBuilder,
                                   @Value("${openlibrary.data.api.url:https://openlibrary.org}") String openLibraryApiUrl,
                                   @Value("${app.features.external-fallback.enabled:${app.features.google-fallback.enabled:true}}") boolean externalFallbackEnabled) {
        this.webClient = webClientBuilder.baseUrl(openLibraryApiUrl).build();
        this.externalFallbackEnabled = externalFallbackEnabled;
    }

    /**
     * Searches OpenLibrary for books matching the given title.
     *
     * <p>Uses the OpenLibrary Search API with a title-specific parameter for precise results.
     * Results are mapped to {@link Book} domain objects with normalized fields.</p>
     *
     * @param title the book title to search for
     * @return a Flux of matching books, or empty if disabled or no results found
     */
    @RateLimiter(name = "openLibraryDataService")
    @CircuitBreaker(name = "openLibraryDataService", fallbackMethod = "searchBooksFallback")
    public Flux<Book> queryBooksByTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            log.warn("Title is null or empty. Cannot search books on OpenLibrary.");
            return Flux.empty();
        }
        if (!externalFallbackEnabled) {
            log.debug("External fallback disabled; skipping OpenLibrary search for title: {}", title);
            return Flux.empty();
        }
        log.info("Attempting to search books from OpenLibrary for title: {}", title);
        ExternalApiLogger.logApiCallAttempt(log, "OpenLibrary", "SEARCH_TITLE", title, false);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search.json")
                        .queryParam("title", title)
                        .queryParam("limit", 20)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorMap(PrematureCloseException.class, e -> {
                    log.debug("OpenLibrary search connection closed early for title '{}': {}", title, e.toString());
                    return new IllegalStateException("OpenLibrary title search connection closed early for '" + title + "'", e);
                })
                .flatMapMany(responseNode -> {
                    if (!responseNode.has("docs") || !responseNode.get("docs").isArray()) {
                        ExternalApiLogger.logApiCallSuccess(log, "OpenLibrary", "SEARCH_TITLE", title, 0);
                        return Flux.empty();
                    }
                    int count = responseNode.get("docs").size();
                    ExternalApiLogger.logApiCallSuccess(log, "OpenLibrary", "SEARCH_TITLE", title, count);
                    return Flux.fromIterable(responseNode.get("docs"))
                               .map(this::parseOpenLibrarySearchDoc)
                               .filter(Objects::nonNull);
                })
                .doOnError(e -> LoggingUtils.error(log, e, "Error searching books by title '{}' from OpenLibrary", title))
                .onErrorMap(e -> {
                     LoggingUtils.warn(log, e, "Error during OpenLibrary search for title '{}', returning empty Flux", title);
                     ExternalApiLogger.logApiCallFailure(log, "OpenLibrary", "SEARCH_TITLE", title, e.getMessage());
                     return new IllegalStateException("OpenLibrary title search failed for '" + title + "'", e);
                });
    }

    /**
     * Searches OpenLibrary for books by the given author name.
     *
     * <p>Uses the OpenLibrary Search API with an author-specific parameter.
     * Results are mapped to {@link Book} domain objects with normalized fields.</p>
     *
     * @param author the author name to search for
     * @return a Flux of matching books, or empty if disabled or no results found
     */
    @RateLimiter(name = "openLibraryDataService")
    @CircuitBreaker(name = "openLibraryDataService", fallbackMethod = "searchBooksFallback")
    public Flux<Book> queryBooksByAuthor(String author) {
        if (author == null || author.trim().isEmpty()) {
            log.warn("Author is null or empty. Cannot search books on OpenLibrary.");
            return Flux.empty();
        }
        if (!externalFallbackEnabled) {
            log.debug("External fallback disabled; skipping OpenLibrary author search for: {}", author);
            return Flux.empty();
        }

        log.info("Attempting to search OpenLibrary for author: {}", author);
        ExternalApiLogger.logApiCallAttempt(log, "OpenLibrary", "SEARCH_AUTHOR", author, false);

        // Bug #6 Fix: Author search uses only author param, no sort parameter
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search.json")
                        .queryParam("author", author)
                        .queryParam("limit", 20)
                        // NEVER add sort=newest here - it's unsupported with author param
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorMap(PrematureCloseException.class, e -> {
                    log.debug("OpenLibrary author search connection closed early for '{}': {}", author, e.toString());
                    return new IllegalStateException("OpenLibrary author search connection closed early for '" + author + "'", e);
                })
                .flatMapMany(responseNode -> {
                    if (!responseNode.has("docs") || !responseNode.get("docs").isArray()) {
                        ExternalApiLogger.logApiCallSuccess(log, "OpenLibrary", "SEARCH_AUTHOR", author, 0);
                        return Flux.empty();
                    }
                    int count = responseNode.get("docs").size();
                    ExternalApiLogger.logApiCallSuccess(log, "OpenLibrary", "SEARCH_AUTHOR", author, count);
                    return Flux.fromIterable(responseNode.get("docs"))
                            .map(this::parseOpenLibrarySearchDoc)
                            .filter(Objects::nonNull);
                })
                .doOnError(e -> LoggingUtils.error(log, e, "Error searching books by author '{}' from OpenLibrary", author))
                .onErrorMap(e -> {
                    LoggingUtils.warn(log, e, "Error during OpenLibrary search for author '{}', returning empty Flux", author);
                    ExternalApiLogger.logApiCallFailure(log, "OpenLibrary", "SEARCH_AUTHOR", author, e.getMessage());
                    return new IllegalStateException("OpenLibrary author search failed for '" + author + "'", e);
                });
    }

    /**
     * Circuit-breaker fallback for title and author search methods.
     *
     * <p>Propagates the failure explicitly so callers can handle the unavailability
     * rather than receiving silent empty results.</p>
     *
     * @param query the search query that triggered the circuit breaker
     * @param cause the throwable that triggered the fallback
     * @return a Flux.error wrapping the circuit breaker cause
     */
    public Flux<Book> searchBooksFallback(String query, Throwable cause) {
        LoggingUtils.warn(log, cause, "OpenLibrary search fallback triggered for query: '{}'", query);
        return Flux.error(new IllegalStateException("OpenLibrary fallback triggered for search '" + query + "'", cause));
    }

    private Book parseOpenLibrarySearchDoc(JsonNode docNode) {
        if (docNode == null || docNode.isMissingNode() || !docNode.isObject()) {
            return null;
        }

        Book book = new Book();
        book.setId(extractOpenLibraryId(docNode));
        book.setTitle(TextUtils.normalizeBookTitle(emptyToNull(docNode.path("title").asString())));
        book.setAuthors(extractSearchAuthors(docNode));
        extractSearchIsbns(docNode, book);
        extractSearchCover(docNode, book);
        book.setRawJsonResponse(docNode.toString());

        if (book.getId() != null && book.getTitle() != null) {
            return book;
        }
        return null;
    }

    private static String extractOpenLibraryId(JsonNode node) {
        String key = node.path("key").asString();
        if (key == null || key.isEmpty()) {
            return null;
        }
        int slashIndex = key.lastIndexOf('/');
        return slashIndex >= 0 ? key.substring(slashIndex + 1) : key;
    }

    private static List<String> extractSearchAuthors(JsonNode docNode) {
        if (!docNode.has("author_name") || !docNode.get("author_name").isArray()) {
            return List.of();
        }
        List<String> authors = new ArrayList<>();
        for (JsonNode authorNameNode : docNode.get("author_name")) {
            String normalized = TextUtils.normalizeAuthorName(emptyToNull(authorNameNode.asString()));
            if (StringUtils.hasText(normalized)) {
                authors.add(normalized);
            }
        }
        return authors.isEmpty() ? List.of() : authors;
    }

    private static void extractSearchIsbns(JsonNode docNode, Book book) {
        if (!docNode.has("isbn") || !docNode.get("isbn").isArray()) {
            return;
        }
        List<String> sanitizedIsbns = new ArrayList<>();
        for (JsonNode isbnNode : docNode.get("isbn")) {
            String sanitized = IsbnUtils.sanitize(emptyToNull(isbnNode.asString()));
            if (sanitized != null) {
                sanitizedIsbns.add(sanitized);
                classifyIsbn(sanitized, book);
            }
        }
        if (book.getIsbn13() == null && book.getIsbn10() == null) {
            assignFirstMatchingIsbns(sanitizedIsbns, book);
        }
    }

    private static void classifyIsbn(String sanitized, Book book) {
        if (sanitized.length() == 13 && book.getIsbn13() == null) {
            book.setIsbn13(sanitized);
        } else if (sanitized.length() == 10 && book.getIsbn10() == null) {
            book.setIsbn10(sanitized);
        }
    }

    private static void assignFirstMatchingIsbns(List<String> isbns, Book book) {
        for (String isbn : isbns) {
            if (isbn.length() == 13) { book.setIsbn13(isbn); break; }
        }
        for (String isbn : isbns) {
            if (isbn.length() == 10) { book.setIsbn10(isbn); break; }
        }
    }

    private static void extractSearchCover(JsonNode docNode, Book book) {
        if (!docNode.has("cover_i") || docNode.path("cover_i").isNull()) {
            return;
        }
        String coverId = docNode.path("cover_i").asString();
        if (StringUtils.hasText(coverId)) {
            book.setExternalImageUrl("https://covers.openlibrary.org/b/id/" + coverId + "-L.jpg");
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
