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
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.TextUtils;
import net.findmybook.util.ApplicationConstants;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.PrematureCloseException;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
public class OpenLibraryBookDataService {

    private static final int DEFAULT_SEARCH_LIMIT = 40;
    private static final int OPEN_LIBRARY_PAGE_SIZE = 100;
    private static final int WORK_DETAILS_CONCURRENCY = 6;
    private static final int WORK_DETAILS_MAX_ENRICHMENTS = 12;
    private static final Duration WORK_DETAILS_TIMEOUT = Duration.ofSeconds(3);
    private static final String SEARCH_FIELDS =
        "key,title,author_name,isbn,cover_i,first_publish_year,number_of_pages_median,subject,publisher,language,first_sentence";

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
     * @param title the book title to search for
     * @return a Flux of matching books, or empty if disabled or no results found
     */
    @RateLimiter(name = "openLibraryDataService")
    @CircuitBreaker(name = "openLibraryDataService", fallbackMethod = "searchBooksFallback")
    public Flux<Book> queryBooksByTitle(String title) {
        return queryBooks("title", title, "SEARCH_TITLE", false, null, 0, DEFAULT_SEARCH_LIMIT);
    }

    /**
     * Searches OpenLibrary for books by the given author name.
     *
     * @param author the author name to search for
     * @return a Flux of matching books, or empty if disabled or no results found
     */
    @RateLimiter(name = "openLibraryDataService")
    @CircuitBreaker(name = "openLibraryDataService", fallbackMethod = "searchBooksFallback")
    public Flux<Book> queryBooksByAuthor(String author) {
        return queryBooks("author", author, "SEARCH_AUTHOR", false, null, 0, DEFAULT_SEARCH_LIMIT);
    }

    /**
     * Searches OpenLibrary using the same query contract as the public web search page.
     *
     * <p>This uses {@code q=} with {@code mode=everything} to preserve provider ranking parity
     * with direct OpenLibrary searches.</p>
     *
     * @param query the free-text query to search
     * @return a Flux of matching books, or empty if disabled or no results found
     */
    @RateLimiter(name = "openLibraryDataService")
    @CircuitBreaker(name = "openLibraryDataService", fallbackMethod = "searchBooksFallback")
    public Flux<Book> queryBooksByEverything(String query) {
        return queryBooksByEverything(query, null);
    }

    /**
     * Searches OpenLibrary using mode=everything with provider-specific sort mapping.
     *
     * @param query the free-text query to search
     * @param orderBy requested FindMyBook orderBy value
     * @return a Flux of matching books, or empty if disabled or no results found
     */
    @RateLimiter(name = "openLibraryDataService")
    @CircuitBreaker(name = "openLibraryDataService", fallbackMethod = "searchBooksFallback")
    public Flux<Book> queryBooksByEverything(String query, String orderBy) {
        return queryBooksByEverything(query, orderBy, 0, DEFAULT_SEARCH_LIMIT);
    }

    /**
     * Searches OpenLibrary using mode=everything for a deterministic offset window.
     *
     * <p>FindMyBook search endpoints use a zero-based {@code startIndex} absolute offset.
     * Open Library exposes offset-based paging through {@code offset} + {@code limit};
     * this method maps the same zero-based contract directly to provider calls.</p>
     *
     * @param query the free-text query to search
     * @param orderBy requested FindMyBook orderBy value
     * @param startIndex zero-based absolute offset into provider results
     * @param maxResults number of provider rows to retrieve from startIndex
     * @return a Flux of matching books, or empty if disabled or no results found
     */
    @RateLimiter(name = "openLibraryDataService")
    @CircuitBreaker(name = "openLibraryDataService", fallbackMethod = "searchBooksFallback")
    public Flux<Book> queryBooksByEverything(String query, String orderBy, int startIndex, int maxResults) {
        return queryBooks(
            "q",
            query,
            "SEARCH_EVERYTHING",
            true,
            SearchExternalProviderUtils.normalizeOpenLibrarySortFacet(orderBy).orElse(null),
            startIndex,
            maxResults
        );
    }

    /**
     * Shared search implementation for the OpenLibrary Search API.
     *
     * <p>Constructs a parameterized request, maps the JSON "docs" array to
     * {@link Book} domain objects, and wraps failures in typed exceptions
     * so circuit-breaker fallbacks fire correctly.</p>
     *
     * @param queryParamName the API query parameter name ("title" or "author")
     * @param queryValue     the search value to send
     * @param apiOperation   logging label for external API metrics (e.g. "SEARCH_TITLE")
     * @param openLibrarySortFacet provider-specific sort facet to pass when supported
     * @param startIndex zero-based offset into provider rows
     * @param maxResults maximum number of rows to emit from startIndex
     * @return a Flux of matching books, or empty when disabled or no results found
     */
    private Flux<Book> queryBooks(String queryParamName,
                                  String queryValue,
                                  String apiOperation,
                                  boolean includeEverythingMode,
                                  String openLibrarySortFacet,
                                  int startIndex,
                                  int maxResults) {
        if (queryValue == null || queryValue.trim().isEmpty()) {
            log.warn("{} is null or empty. Cannot search books on OpenLibrary.", queryParamName);
            return Flux.empty();
        }
        if (!externalFallbackEnabled) {
            log.debug("External fallback disabled; skipping OpenLibrary {} search for: {}", queryParamName, queryValue);
            return Flux.empty();
        }
        int safeStartIndex = Math.max(0, startIndex);
        int safeMaxResults = Math.max(1, Math.min(
            maxResults > 0 ? maxResults : DEFAULT_SEARCH_LIMIT,
            ApplicationConstants.Paging.MAX_TIERED_LIMIT
        ));
        int pageSize = includeEverythingMode
            ? Math.min(OPEN_LIBRARY_PAGE_SIZE, safeMaxResults)
            : Math.min(DEFAULT_SEARCH_LIMIT, safeMaxResults);
        int pageCount = Math.max(1, (safeMaxResults + pageSize - 1) / pageSize);

        log.info("Attempting to search OpenLibrary by {}: {}", queryParamName, queryValue);
        ExternalApiLogger.logApiCallAttempt(log, "OpenLibrary", apiOperation, queryValue, false);
        Flux<Book> parsedBooks = Flux.range(0, pageCount)
                .concatMap(pageOffset -> {
                    int offset = safeStartIndex + pageOffset * pageSize;
                    int requestedLimit = Math.min(pageSize, safeMaxResults - pageOffset * pageSize);
                    return fetchSearchPage(
                        queryParamName,
                        queryValue,
                        apiOperation,
                        includeEverythingMode,
                        openLibrarySortFacet,
                        offset,
                        requestedLimit
                    );
                })
                .take(safeMaxResults);

        Flux<Book> response = includeEverythingMode
            ? enrichWithWorkDetails(parsedBooks, queryValue)
            : parsedBooks;

        return response
                .doOnError(e -> LoggingUtils.error(log, e, "Error searching books by {} '{}' from OpenLibrary", queryParamName, queryValue))
                .onErrorMap(e -> {
                     LoggingUtils.warn(log, e, "Error during OpenLibrary search for {} '{}', returning empty Flux", queryParamName, queryValue);
                     ExternalApiLogger.logApiCallFailure(log, "OpenLibrary", apiOperation, queryValue, e.getMessage());
                     return new IllegalStateException("OpenLibrary " + queryParamName + " search failed for '" + queryValue + "'", e);
                });
    }

    private Flux<Book> fetchSearchPage(String queryParamName,
                                       String queryValue,
                                       String apiOperation,
                                       boolean includeEverythingMode,
                                       String openLibrarySortFacet,
                                       int offset,
                                       int limit) {
        if (limit <= 0) {
            return Flux.empty();
        }
        String requestContext = queryValue + " start=" + offset + " limit=" + limit;
        return webClient.get()
            .uri(uriBuilder -> {
                var requestBuilder = uriBuilder
                    .path("/search.json")
                    .queryParam(queryParamName, queryValue)
                    .queryParam("offset", offset)
                    .queryParam("limit", limit);
                if (includeEverythingMode) {
                    requestBuilder = requestBuilder
                        .queryParam("mode", "everything")
                        .queryParam("fields", SEARCH_FIELDS);
                    if (StringUtils.hasText(openLibrarySortFacet)) {
                        requestBuilder = requestBuilder.queryParam("sort", openLibrarySortFacet);
                    }
                }
                return requestBuilder.build();
            })
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(5))
            .onErrorMap(PrematureCloseException.class, e -> {
                log.debug("OpenLibrary {} search connection closed early for '{}': {}", queryParamName, queryValue, e.toString());
                return new IllegalStateException("OpenLibrary " + queryParamName + " search connection closed early for '" + queryValue + "'", e);
            })
            .flatMapMany(responseNode -> {
                if (!responseNode.has("docs") || !responseNode.get("docs").isArray()) {
                    ExternalApiLogger.logApiCallSuccess(log, "OpenLibrary", apiOperation, requestContext, 0);
                    return Flux.empty();
                }
                int count = responseNode.get("docs").size();
                ExternalApiLogger.logApiCallSuccess(log, "OpenLibrary", apiOperation, requestContext, count);
                return Flux.fromIterable(responseNode.get("docs"))
                    .map(this::parseOpenLibrarySearchDoc)
                    .filter(Objects::nonNull);
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

    public Flux<Book> searchBooksFallback(String query, String orderBy, Throwable cause) {
        LoggingUtils.warn(log, cause, "OpenLibrary search fallback triggered for query: '{}' and orderBy '{}'", query, orderBy);
        return searchBooksFallback(query, cause);
    }

    public Flux<Book> searchBooksFallback(String query,
                                          String orderBy,
                                          int startIndex,
                                          int maxResults,
                                          Throwable cause) {
        LoggingUtils.warn(
            log,
            cause,
            "OpenLibrary search fallback triggered for query: '{}' orderBy '{}' startIndex {} maxResults {}",
            query,
            orderBy,
            startIndex,
            maxResults
        );
        return searchBooksFallback(query, cause);
    }

    private Book parseOpenLibrarySearchDoc(JsonNode docNode) {
        if (docNode == null || docNode.isMissingNode() || !docNode.isObject()) {
            return null;
        }

        Book book = new Book();
        book.setId(extractOpenLibraryId(docNode));
        book.setTitle(TextUtils.normalizeBookTitle(emptyToNull(docNode.path("title").asString())));
        book.setAuthors(extractSearchAuthors(docNode));
        book.setDescription(extractSearchDescription(docNode));
        book.setPublisher(extractSearchPublisher(docNode));
        book.setLanguage(extractSearchLanguage(docNode));
        book.setCategories(extractSearchCategories(docNode));
        extractSearchPageCount(docNode, book);
        extractSearchIsbns(docNode, book);
        extractSearchPublishedDate(docNode, book);
        extractSearchCover(docNode, book);
        book.setRawJsonResponse(docNode.toString());

        if (book.getId() != null && book.getTitle() != null) {
            return book;
        }
        return null;
    }

    private Flux<Book> enrichWithWorkDetails(Flux<Book> books, String queryValue) {
        return books
            .index()
            .flatMapSequential(indexedBook -> {
                long resultIndex = indexedBook.getT1();
                Book book = indexedBook.getT2();
                if (resultIndex >= WORK_DETAILS_MAX_ENRICHMENTS) {
                    return Mono.just(book);
                }
                return fetchWorkDetails(book, queryValue);
            }, WORK_DETAILS_CONCURRENCY);
    }

    private Mono<Book> fetchWorkDetails(Book book, String queryValue) {
        if (book == null || !StringUtils.hasText(book.getId())) {
            return Mono.justOrEmpty(book);
        }

        return webClient.get()
            .uri(uriBuilder -> uriBuilder.path("/works/{workId}.json").build(book.getId()))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(WORK_DETAILS_TIMEOUT)
            .map(workNode -> mergeWorkDetails(book, workNode))
            .onErrorResume(ex -> {
                LoggingUtils.warn(
                    log,
                    ex,
                    "OpenLibrary work detail lookup failed for work '{}' while searching '{}'; using search-doc fallback fields",
                    book.getId(),
                    queryValue
                );
                return Mono.just(book);
            });
    }

    private Book mergeWorkDetails(Book book, JsonNode workNode) {
        if (book == null || workNode == null || workNode.isMissingNode() || !workNode.isObject()) {
            return book;
        }

        String fullDescription = extractWorkDescription(workNode);
        if (shouldReplaceDescription(book.getDescription(), fullDescription)) {
            book.setDescription(fullDescription);
        }
        return book;
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

    private static void extractSearchPublishedDate(JsonNode docNode, Book book) {
        if (!docNode.has("first_publish_year") || docNode.path("first_publish_year").isNull()) {
            return;
        }
        int firstPublishYear = docNode.path("first_publish_year").asInt(0);
        if (firstPublishYear <= 0) {
            return;
        }
        book.setPublishedDate(Date.from(
            LocalDate.of(firstPublishYear, 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        ));
    }

    private static String extractSearchDescription(JsonNode docNode) {
        JsonNode firstSentenceNode = docNode.path("first_sentence");
        if (firstSentenceNode.isMissingNode() || firstSentenceNode.isNull()) {
            return null;
        }
        if (firstSentenceNode.isString()) {
            return emptyToNull(firstSentenceNode.asString());
        }
        if (firstSentenceNode.isArray()) {
            for (JsonNode sentenceNode : firstSentenceNode) {
                String result = extractFromSentenceNode(sentenceNode);
                if (StringUtils.hasText(result)) {
                    return result;
                }
            }
        }
        return null;
    }

    private static String extractFromSentenceNode(JsonNode sentenceNode) {
        if (sentenceNode == null || sentenceNode.isNull()) {
            return null;
        }
        if (sentenceNode.isString()) {
            return emptyToNull(sentenceNode.asString());
        }
        return emptyToNull(sentenceNode.path("value").asString());
    }

    private static String extractWorkDescription(JsonNode workNode) {
        JsonNode descriptionNode = workNode.path("description");
        if (descriptionNode.isMissingNode() || descriptionNode.isNull()) {
            return null;
        }
        if (descriptionNode.isString()) {
            return emptyToNull(descriptionNode.asString());
        }
        String nestedValue = emptyToNull(descriptionNode.path("value").asString());
        if (StringUtils.hasText(nestedValue)) {
            return nestedValue;
        }
        return null;
    }

    private static boolean shouldReplaceDescription(String currentDescription, String workDescription) {
        if (!StringUtils.hasText(workDescription)) {
            return false;
        }
        if (!StringUtils.hasText(currentDescription)) {
            return true;
        }
        return workDescription.length() > currentDescription.length();
    }

    private static String extractSearchPublisher(JsonNode docNode) {
        if (!docNode.has("publisher") || !docNode.get("publisher").isArray()) {
            return null;
        }
        for (JsonNode publisherNode : docNode.get("publisher")) {
            String publisher = emptyToNull(publisherNode.asString());
            if (StringUtils.hasText(publisher)) {
                return publisher;
            }
        }
        return null;
    }

    private static String extractSearchLanguage(JsonNode docNode) {
        if (!docNode.has("language") || !docNode.get("language").isArray()) {
            return null;
        }
        for (JsonNode languageNode : docNode.get("language")) {
            String language = emptyToNull(languageNode.asString());
            if (StringUtils.hasText(language)) {
                return language;
            }
        }
        return null;
    }

    private static List<String> extractSearchCategories(JsonNode docNode) {
        if (!docNode.has("subject") || !docNode.get("subject").isArray()) {
            return List.of();
        }
        Set<String> categories = new LinkedHashSet<>();
        for (JsonNode subjectNode : docNode.get("subject")) {
            String category = emptyToNull(subjectNode.asString());
            if (StringUtils.hasText(category)) {
                categories.add(category.trim());
            }
        }
        if (categories.isEmpty()) {
            return List.of();
        }
        return List.copyOf(categories);
    }

    private static void extractSearchPageCount(JsonNode docNode, Book book) {
        if (!docNode.has("number_of_pages_median") || docNode.path("number_of_pages_median").isNull()) {
            return;
        }
        int pageCount = docNode.path("number_of_pages_median").asInt(0);
        if (pageCount > 0) {
            book.setPageCount(pageCount);
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
