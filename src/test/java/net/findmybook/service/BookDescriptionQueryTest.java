package net.findmybook.service;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.findmybook.dto.BookDetail;
import net.findmybook.mapper.GoogleBooksMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookDescriptionQueryTest {

    private final UUID bookId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<URI> openLibraryRequest = new CopyOnWriteArrayList<>();
    private final List<String> googleQuery = new CopyOnWriteArrayList<>();
    private final AtomicReference<HttpStatus> openLibraryStatus = new AtomicReference<>(HttpStatus.OK);
    private OpenLibraryBookDataService openLibrary;
    private BookDataOrchestrator orchestrator;

    @BeforeEach
    void prepareProviders() {
        RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
        registry.rateLimiter(OpenLibraryBookDataService.RATE_LIMITER_NAME);
        openLibrary = new OpenLibraryBookDataService(
            WebClient.builder().exchangeFunction(request -> {
                openLibraryRequest.add(request.url());
                return Mono.just(ClientResponse.create(openLibraryStatus.get())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"docs\":[]}")
                    .build());
            }),
            "https://openlibrary.test", true, registry);
        GoogleApiFetcher google = mock(GoogleApiFetcher.class);
        when(google.isGoogleFallbackEnabled()).thenReturn(true);
        when(google.streamSearchItems(anyString(), anyInt(), anyString(), isNull(), eq(false)))
            .thenAnswer(invocation -> {
                googleQuery.add(invocation.getArgument(0, String.class));
                return Flux.empty();
            });
        orchestrator = new BookDataOrchestrator(
            mock(BookSearchService.class), mock(PostgresBookRepository.class),
            mock(BookExternalBatchPersistenceService.class), Optional.of(openLibrary),
            Optional.of(google), Optional.of(new GoogleBooksMapper()), mock(BookUpsertService.class));
    }

    @AfterEach
    void stopProviderAdmission() {
        openLibrary.stopAdmission();
    }

    @Test
    void should_EscapeStoredMetadata_WithoutChangingGoogleQuery() {
        ObjectNode json = book("Retired from business:\\s\\ua comedy");
        json.putArray("authors").add("Douglas William Jerrold");

        assertThat(enrich(json)).isEqualTo("short");

        assertThat(googleQuery).containsExactly("Retired from business:\\s\\ua comedy Douglas William Jerrold");
        assertThat(openLibraryRequest).singleElement().satisfies(uri -> {
            assertThat(parameter(uri, "q"))
                .isEqualTo("Retired from business\\:\\\\s\\\\ua comedy Douglas William Jerrold");
            assertThat(parameter(uri, "offset")).isEqualTo("0");
            assertThat(parameter(uri, "limit")).isEqualTo("6");
            assertThat(parameter(uri, "mode")).isEqualTo("everything");
            assertThat(parameter(uri, "sort")).isNull();
            assertThat(parameter(uri, "fields")).contains("title", "author_name");
        });
    }

    @ParameterizedTest
    @CsvSource({"isbn_13,9780060935467", "isbn_10,0060935464"})
    void should_KeepBothIsbnBranches(String field, String isbn) {
        ObjectNode json = book("A title that must not become the search");
        json.put(field, isbn);

        assertThat(enrich(json)).isEqualTo("short");

        assertThat(googleQuery).containsExactly("isbn:" + isbn);
        assertThat(openLibraryRequest).singleElement().satisfies(uri ->
            assertThat(parameter(uri, "q")).isEqualTo(isbn));
    }

    @Test
    void should_SeparateIsbnAndLiteralCaches_WhileReusingUnchangedQueries() {
        String isbn = "9780060935467";
        ObjectNode identified = book("An ISBN lookup").put("isbn_13", isbn);
        ObjectNode literal = book("isbn:" + isbn);

        enrich(identified);
        enrich(literal);
        enrich(literal);

        assertThat(openLibraryRequest).extracting(uri -> parameter(uri, "q"))
            .containsExactly(isbn, "isbn\\:" + isbn);
        assertThat(googleQuery).containsExactly("isbn:" + isbn, "isbn:" + isbn);
    }

    @Test
    void should_PreserveQualifierWords_When_TheyArePartOfMetadata() {
        ObjectNode json = book("intitle: Clean Code isbn: notes");
        json.putArray("authors").add("inauthor: Someone");

        enrich(json);

        assertThat(openLibraryRequest).singleElement().satisfies(uri ->
            assertThat(parameter(uri, "q"))
                .isEqualTo("intitle\\: Clean Code isbn\\: notes inauthor\\: Someone"));
        assertThat(googleQuery).containsExactly("intitle: Clean Code isbn: notes inauthor: Someone");
    }

    @Test
    void should_KeepCaseInsensitiveReuse_When_BothPreparedQueriesAreEquivalent() {
        enrich(book("Canonical title"));
        enrich(book("CANONICAL TITLE"));

        assertThat(openLibraryRequest).hasSize(1);
        assertThat(googleQuery).containsExactly("Canonical title");
    }

    @Test
    void should_SearchAnAsteriskTitleAsLiteralMetadata() {
        enrich(book("*"));

        assertThat(openLibraryRequest).singleElement().satisfies(uri ->
            assertThat(parameter(uri, "q")).isEqualTo("\\*"));
        assertThat(googleQuery).isEmpty();
    }

    @Test
    void should_PropagateLiteralLookupFailure_AndAllowTheNextAttempt() {
        ObjectNode json = book("*");
        openLibraryStatus.set(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThatThrownBy(() -> enrich(json))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OpenLibrary q search failed")
            .hasRootCauseInstanceOf(WebClientResponseException.InternalServerError.class);
        int failedRequestCount = openLibraryRequest.size();
        assertThat(failedRequestCount).isPositive();

        openLibraryStatus.set(HttpStatus.OK);
        assertThat(enrich(json)).isEqualTo("short");
        assertThat(enrich(json)).isEqualTo("short");

        assertThat(openLibraryRequest).hasSize(failedRequestCount + 1);
        assertThat(googleQuery).isEmpty();
    }

    @Test
    void should_KeepPublicSolrSyntaxAndReservedUriValues() {
        String query = "title:(+red OR blue) AND author:\"Müller\" AND title:\"C++ 100% {Stars}\"";

        openLibrary.queryBooksByEverything(query, "newest", 12, 6).collectList().block();

        assertThat(openLibraryRequest).singleElement().satisfies(uri -> {
            assertThat(parameter(uri, "q")).isEqualTo(query);
            assertThat(parameter(uri, "offset")).isEqualTo("12");
            assertThat(parameter(uri, "limit")).isEqualTo("6");
            assertThat(parameter(uri, "sort")).isEqualTo("new");
        });
    }

    @Test
    void should_SkipProviders_When_NoSearchIdentityIsAvailable() {
        assertThat(enrich(book(" "))).isEqualTo("short");
        assertThat(openLibraryRequest).isEmpty();
        assertThat(googleQuery).isEmpty();
    }

    private ObjectNode book(String title) {
        return mapper.createObjectNode().put("id", bookId.toString()).put("title", title)
            .put("description", "short");
    }

    private String enrich(ObjectNode json) {
        return orchestrator.enrichDescriptionForAiIfNeeded(
            bookId, mapper.treeToValue(json, BookDetail.class), "short", 50);
    }

    private static String parameter(URI uri, String name) {
        String encoded = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
        return encoded == null ? null : URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
