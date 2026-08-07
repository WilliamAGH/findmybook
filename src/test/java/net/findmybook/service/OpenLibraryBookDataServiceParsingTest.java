package net.findmybook.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.findmybook.model.Book;
import net.findmybook.util.DateParsingUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class OpenLibraryBookDataServiceParsingTest {

    @Test
    void should_PreserveLocalAdmissionFailure_When_RateLimiterRejectsRequest() {
        OpenLibraryBookDataService service = openLibraryBookDataService();
        RequestNotPermitted denial = RequestNotPermitted.createRequestNotPermitted(
            RateLimiter.ofDefaults("open-library-test")
        );

        StepVerifier.create(service.searchBooksFallback("query", denial))
            .expectErrorSatisfies(error -> assertThat(error).isSameAs(denial))
            .verify();
    }

    @Test
    void should_PreserveLocalAdmissionFailure_When_CircuitBreakerRejectsRequest() {
        OpenLibraryBookDataService service = openLibraryBookDataService();
        CallNotPermittedException denial = CallNotPermittedException.createCallNotPermittedException(
            CircuitBreaker.ofDefaults("open-library-test")
        );

        StepVerifier.create(service.searchBooksFallback("query", denial))
            .expectErrorSatisfies(error -> assertThat(error).isSameAs(denial))
            .verify();
    }

    @Test
    void should_RejectBeforeSearchExchange_When_RequestQuotaIsExhausted() {
        List<String> requestPaths = new CopyOnWriteArrayList<>();
        RateLimiterRegistry rateLimiterRegistry = rateLimiterRegistry(
            1,
            Duration.ofHours(1),
            Duration.ZERO
        );
        OpenLibraryBookDataService service = openLibraryBookDataService(
            WebClient.builder().exchangeFunction(request -> {
                requestPaths.add(request.url().getPath());
                return jsonResponse("{\"docs\":[]}");
            }),
            rateLimiterRegistry
        );

        StepVerifier.create(service.queryBooksByTitle("first search"))
            .verifyComplete();
        StepVerifier.create(service.queryBooksByTitle("denied search"))
            .expectError(RequestNotPermitted.class)
            .verify();

        assertThat(requestPaths).containsExactly("/search.json");
    }

    @Test
    void should_ConsumePermitForEveryExchange_When_ExactIsbnFansOut() {
        List<String> requestPaths = new CopyOnWriteArrayList<>();
        RateLimiterRegistry rateLimiterRegistry = rateLimiterRegistry(
            4,
            Duration.ofHours(1),
            Duration.ZERO
        );
        OpenLibraryBookDataService service = openLibraryBookDataService(
            WebClient.builder().exchangeFunction(fanOutExchange(requestPaths)),
            rateLimiterRegistry
        );

        verifyExactIsbnFanOut(service);
        assertThat(rateLimiterRegistry.find(OpenLibraryBookDataService.RATE_LIMITER_NAME).orElseThrow()
            .getMetrics().getAvailablePermissions()).isZero();

        StepVerifier.create(service.queryBooksByTitle("denied after fan-out"))
            .expectError(RequestNotPermitted.class)
            .verify();
        assertThat(requestPaths).containsExactly(
            "/search.json",
            "/api/books",
            "/works/OL1W.json",
            "/works/OL2W.json"
        );
    }

    @Test
    void should_CompleteSequentialFanOut_When_UsingProductionQuotaCadence() {
        List<String> requestPaths = new CopyOnWriteArrayList<>();
        RateLimiterRegistry rateLimiterRegistry = rateLimiterRegistry(
            1,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2)
        );
        OpenLibraryBookDataService service = openLibraryBookDataService(
            WebClient.builder().exchangeFunction(fanOutExchange(requestPaths)),
            rateLimiterRegistry
        );

        verifyExactIsbnFanOut(service);

        assertThat(requestPaths).containsExactly(
            "/search.json",
            "/api/books",
            "/works/OL1W.json",
            "/works/OL2W.json"
        );
    }

    @Test
    void should_ReturnBaseSearchBook_When_OptionalEnrichmentAdmissionIsDenied() {
        List<String> requestPaths = new CopyOnWriteArrayList<>();
        RateLimiterRegistry rateLimiterRegistry = rateLimiterRegistry(
            1,
            Duration.ofHours(1),
            Duration.ZERO
        );
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(OpenLibraryBookDataService.RATE_LIMITER_NAME);
        AtomicInteger localDenials = new AtomicInteger();
        rateLimiter.getEventPublisher().onFailure(ignoredEvent -> localDenials.incrementAndGet());
        OpenLibraryBookDataService service = openLibraryBookDataService(
            WebClient.builder().exchangeFunction(request -> {
                String requestPath = request.url().getPath();
                requestPaths.add(requestPath);
                if (!"/search.json".equals(requestPath)) {
                    return Mono.error(new AssertionError("Denied optional enrichment reached " + request.url()));
                }
                return jsonResponse("""
                    {"docs":[{"key":"/works/OL1W","title":"Base Search Book",
                    "isbn":["0061120081","9780061120084"]}]}
                    """);
            }),
            rateLimiterRegistry
        );

        StepVerifier.create(service.queryBooksByEverything("0061120081", null, 0, 1))
            .assertNext(book -> {
                assertThat(book.getId()).isEqualTo("OL1W");
                assertThat(book.getTitle()).isEqualTo("Base Search Book");
                assertThat(book.getPageCount()).isNull();
                assertThat(book.getDescription()).isNull();
            })
            .verifyComplete();
        assertThat(requestPaths).containsExactly("/search.json");
        assertThat(localDenials).hasValue(2);
    }

    @Test
    @DisplayName("paged fallback logs one bounded warning without a throwable stack")
    void searchBooksFallback_logsOneBoundedWarning() {
        OpenLibraryBookDataService service = openLibraryBookDataService();
        Logger logger = (Logger) LoggerFactory.getLogger(OpenLibraryBookDataService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            StepVerifier.create(service.searchBooksFallback(
                    "query", "relevance", 0, 6, new IllegalStateException("provider unavailable")))
                .expectErrorSatisfies(error -> assertThat(error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OpenLibrary fallback triggered"))
                .verify();

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                    .contains("query", "IllegalStateException", "provider unavailable");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("parseOpenLibrarySearchDoc maps page count and first sentence description")
    void parseOpenLibrarySearchDoc_mapsPageCountAndDescription() {
        OpenLibraryBookDataService service = openLibraryBookDataService();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode doc = mapper.createObjectNode();
        doc.put("key", "/works/OL77004W");
        doc.put("title", "The Partner");
        doc.putArray("author_name").add("John Grisham");
        doc.put("first_publish_year", 1997);
        doc.put("cover_i", 9323420);
        doc.put("number_of_pages_median", 416);
        doc.putArray("first_sentence")
            .add("They found him in Ponta Porã, a pleasant little town in Brazil.");
        doc.putArray("publisher").add("Doubleday");
        doc.putArray("language").add("eng");
        doc.putArray("subject").add("Legal thrillers");

        Book parsed = ReflectionTestUtils.invokeMethod(service, "parseOpenLibrarySearchDoc", doc, null);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getId()).isEqualTo("OL77004W");
        assertThat(parsed.getDescription()).isEqualTo("They found him in Ponta Porã, a pleasant little town in Brazil.");
        assertThat(parsed.getPageCount()).isEqualTo(416);
        assertThat(parsed.getPublisher()).isEqualTo("Doubleday");
        assertThat(parsed.getLanguage()).isEqualTo("eng");
    }

    @Test
    void should_PreserveProviderAuthorLabel_When_ParsingOpenLibrarySearchDocument() {
        OpenLibraryBookDataService service = openLibraryBookDataService();
        ObjectNode doc = new ObjectMapper().createObjectNode();
        doc.put("key", "/works/OLRAW1W");
        doc.put("title", "Raw Author Fixture");
        doc.putArray("author_name")
            .add("\"JANE DOE\",")
            .add("   ");

        Book parsed = ReflectionTestUtils.invokeMethod(service, "parseOpenLibrarySearchDoc", doc, null);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getAuthors()).containsExactly("\"JANE DOE\",");
    }

    @Test
    @DisplayName("parseOpenLibrarySearchDoc prefers queried ISBN and suppresses aggregate edition fields")
    void parseOpenLibrarySearchDoc_prefersQueriedIsbnAndSuppressesAggregateEditionFields() {
        OpenLibraryBookDataService service = openLibraryBookDataService();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode doc = mapper.createObjectNode();
        doc.put("key", "/works/OL3140822W");
        doc.put("title", "To Kill a Mockingbird");
        doc.putArray("author_name").add("Harper Lee");
        doc.put("first_publish_year", 1960);
        doc.put("cover_i", 14351077);
        doc.put("number_of_pages_median", 320);
        doc.putArray("isbn")
            .add("0446310786")
            .add("9780061120084")
            .add("0061120081");
        doc.putArray("publisher").add("Sel Yayıncılık");
        doc.putArray("language").add("tur");

        Book parsed = ReflectionTestUtils.invokeMethod(service, "parseOpenLibrarySearchDoc", doc, "0061120081");

        assertThat(parsed).isNotNull();
        assertThat(parsed.getIsbn13()).isEqualTo("9780061120084");
        assertThat(parsed.getIsbn10()).isEqualTo("0061120081");
        assertThat(parsed.getPublisher()).isNull();
        assertThat(parsed.getLanguage()).isNull();
        assertThat(parsed.getPageCount()).isNull();
        assertThat(parsed.getPublishedDate()).isNull();
        assertThat(parsed.getExternalImageUrl()).isNull();
    }

    @Test
    @DisplayName("mergeIsbnEditionDetails uses Open Library edition metadata for exact ISBN results")
    void mergeIsbnEditionDetails_usesEditionMetadataForExactIsbnResults() {
        OpenLibraryBookDataService service = openLibraryBookDataService();

        Book book = new Book();
        book.setId("OL3140822W");
        book.setTitle("To Kill a Mockingbird");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode edition = root.putObject("ISBN:0061120081");
        ObjectNode details = edition.putObject("details");
        details.put("publish_date", "2006");
        details.put("number_of_pages", 323);
        details.putArray("publishers").add("Harper Perennial Modern Classics");
        details.putArray("languages").addObject().put("key", "/languages/eng");
        details.putArray("covers").add(15162569);
        details.putArray("isbn_10").add("0061120081");
        details.putArray("isbn_13").add("9780061120084");

        Book merged = ReflectionTestUtils.invokeMethod(
            service,
            "mergeIsbnEditionDetails",
            book,
            root,
            "ISBN:0061120081",
            "9780061120084"
        );

        assertThat(merged).isNotNull();
        assertThat(merged.getPublisher()).isEqualTo("Harper Perennial Modern Classics");
        assertThat(merged.getLanguage()).isEqualTo("eng");
        assertThat(merged.getPageCount()).isEqualTo(323);
        assertThat(DateParsingUtils.formatIsoDate(merged.getPublishedDate())).isEqualTo("2006-01-01");
        assertThat(merged.getExternalImageUrl()).isEqualTo("https://covers.openlibrary.org/b/id/15162569-L.jpg");
        assertThat(merged.getCoverImages()).isNotNull();
        assertThat(merged.getCoverImages().getPreferredUrl()).isEqualTo("https://covers.openlibrary.org/b/id/15162569-L.jpg");
        assertThat(merged.getIsbn13()).isEqualTo("9780061120084");
        assertThat(merged.getIsbn10()).isEqualTo("0061120081");
    }

    @Test
    @DisplayName("mergeWorkDetails replaces short description with full work description")
    void mergeWorkDetails_replacesWithFullDescription() {
        OpenLibraryBookDataService service = openLibraryBookDataService();

        Book book = new Book();
        book.setId("OL77004W");
        book.setDescription("Short first sentence.");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode workNode = mapper.createObjectNode();
        ObjectNode description = workNode.putObject("description");
        description.put("value", "This is the complete work description from Open Library with substantially more detail.");

        Book merged = ReflectionTestUtils.invokeMethod(service, "mergeWorkDetails", book, workNode);

        assertThat(merged).isNotNull();
        assertThat(merged.getDescription())
            .isEqualTo("This is the complete work description from Open Library with substantially more detail.");
    }

    private static void verifyExactIsbnFanOut(OpenLibraryBookDataService service) {
        StepVerifier.create(service.queryBooksByEverything("0061120081", null, 0, 2))
            .assertNext(book -> {
                assertThat(book.getId()).isEqualTo("OL1W");
                assertThat(book.getPageCount()).isEqualTo(323);
                assertThat(book.getDescription()).isEqualTo("Complete first work description.");
            })
            .assertNext(book -> {
                assertThat(book.getId()).isEqualTo("OL2W");
                assertThat(book.getDescription()).isEqualTo("Complete second work description.");
            })
            .expectComplete()
            .verify(Duration.ofSeconds(10));
    }

    private static ExchangeFunction fanOutExchange(List<String> requestPaths) {
        return request -> {
            String requestPath = request.url().getPath();
            requestPaths.add(requestPath);
            return switch (requestPath) {
                case "/search.json" -> jsonResponse("""
                    {"docs":[
                      {"key":"/works/OL1W","title":"First Book","author_name":["First Author"],
                       "isbn":["0061120081","9780061120084"]},
                      {"key":"/works/OL2W","title":"Second Book","author_name":["Second Author"]}
                    ]}
                    """);
                case "/api/books" -> jsonResponse("""
                    {"ISBN:0061120081":{"details":{"number_of_pages":323}}}
                    """);
                case "/works/OL1W.json" -> jsonResponse("""
                    {"description":"Complete first work description."}
                    """);
                case "/works/OL2W.json" -> jsonResponse("""
                    {"description":"Complete second work description."}
                    """);
                default -> Mono.error(new AssertionError("Unexpected Open Library request: " + request.url()));
            };
        };
    }

    private static Mono<ClientResponse> jsonResponse(String responseBody) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(responseBody)
            .build());
    }

    private static RateLimiterRegistry rateLimiterRegistry(int limitForPeriod,
                                                           Duration limitRefreshPeriod,
                                                           Duration timeoutDuration) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(limitForPeriod)
            .limitRefreshPeriod(limitRefreshPeriod)
            .timeoutDuration(timeoutDuration)
            .build();
        return rateLimiterRegistry(rateLimiterConfig);
    }

    private static RateLimiterRegistry rateLimiterRegistry(RateLimiterConfig rateLimiterConfig) {
        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(rateLimiterConfig);
        rateLimiterRegistry.rateLimiter(OpenLibraryBookDataService.RATE_LIMITER_NAME);
        return rateLimiterRegistry;
    }

    private static OpenLibraryBookDataService openLibraryBookDataService() {
        return openLibraryBookDataService(
            WebClient.builder(),
            rateLimiterRegistry(RateLimiterConfig.ofDefaults())
        );
    }

    private static OpenLibraryBookDataService openLibraryBookDataService(WebClient.Builder webClientBuilder,
                                                                         RateLimiterRegistry rateLimiterRegistry) {
        return new OpenLibraryBookDataService(
            webClientBuilder,
            "https://openlibrary.org",
            true,
            rateLimiterRegistry
        );
    }
}
