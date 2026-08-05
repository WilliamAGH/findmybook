package net.findmybook.application.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import net.findmybook.adapters.persistence.BookSeoMetadataRepository;
import net.findmybook.boot.OpenAiProperties;
import net.findmybook.domain.seo.BookSeoMetadataSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookSearchService;
import net.findmybook.support.llm.LlmGatewayTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BookSeoMetadataClientWireTest {

    private static final UUID BOOK_ID = UUID.fromString("019da3e5-3838-703e-9112-bad4a489239e");
    private static final String DESCRIPTION =
        "A detailed source description with enough grounded context for reliable AI generation and metadata tests.";

    private OpenAiTestServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new OpenAiTestServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_NotPersistSeoMetadata_When_RepeatedBlankResponsesExhaustRetries() {
        server.enqueueJson(chatCompletion("stop", ""));
        server.enqueueJson(chatCompletion("stop", ""));
        server.enqueueJson(chatCompletion("stop", ""));
        BookSeoMetadataRepository repository = mock(BookSeoMetadataRepository.class);

        BookSeoMetadataGenerationService service = seoService(repository);
        assertThatThrownBy(() -> service.generateAndPersistIfPromptChanged(BOOK_ID))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("response was empty");
        verify(repository, never()).insertNewCurrentVersion(any(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(server.requestBodies()).hasSize(3).allSatisfy(body ->
            assertThat(body).contains("\"max_completion_tokens\":" + LlmGatewayTier.BACKGROUND_BATCH.maxCompletionTokens()));
    }

    @Test
    void should_RetryLegacyFallback_When_GemmaReturnsValidSeoForUnchangedPrompt() {
        server.enqueueJson(chatCompletion("stop", seoJson()));
        server.enqueueJson(chatCompletion("stop", seoJson()));
        BookSeoMetadataRepository repository = mock(BookSeoMetadataRepository.class);
        BookSeoMetadataSnapshot generatedSnapshot = new BookSeoMetadataSnapshot(
            BOOK_ID,
            2,
            Instant.EPOCH,
            "gemma-4-26b-a4b",
            "openai",
            "Test Book - Book Details | findmybook.net",
            "A specific grounded description helps readers understand this test book and decide whether its practical focus matches their interests and reading goals.",
            "current-prompt-hash"
        );
        when(repository.insertNewCurrentVersion(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(generatedSnapshot);
        BookSeoMetadataGenerationService service = seoService(repository);
        BookSeoMetadataGenerationService.GenerationOutcome initialGeneration = service.generateAndPersist(BOOK_ID);
        BookSeoMetadataSnapshot legacyFallback = new BookSeoMetadataSnapshot(
            BOOK_ID,
            1,
            Instant.EPOCH,
            "gemma-4-26b-a4b",
            BookSeoMetadataGenerationService.LEGACY_FALLBACK_PROVIDER,
            "Test Book - Book Details | findmybook.net",
            "A legacy deterministic SEO description that exists only to make this row retry eligible for replacement.",
            initialGeneration.promptHash()
        );
        when(repository.fetchCurrent(BOOK_ID)).thenReturn(Optional.of(legacyFallback));

        BookSeoMetadataGenerationService.GenerationOutcome outcome = service.generateAndPersistIfPromptChanged(BOOK_ID);

        assertThat(outcome.generated()).isTrue();
        assertThat(outcome.promptHash()).isEqualTo(initialGeneration.promptHash());
        assertThat(outcome.snapshot()).contains(generatedSnapshot);
        verify(repository, times(2))
            .insertNewCurrentVersion(any(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(server.requestBodies()).hasSize(2);
    }

    @Test
    void should_RetryBlankSeoResponse_When_SecondGemmaResponseIsValid() {
        server.enqueueJson(chatCompletion("stop", ""));
        server.enqueueJson(chatCompletion("stop", seoJson()));

        SeoMetadataCandidate candidate = seoClient().generate(BOOK_ID, "Grounded prompt", LlmGatewayTier.BACKGROUND_BATCH);

        assertThat(candidate.seoTitle()).isEqualTo("Test Book - Book Details | findmybook.net");
        List<String> requestBodies = server.requestBodies();
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0)).doesNotContain("Recovery attempt");
        assertThat(requestBodies.get(1))
            .isNotEqualTo(requestBodies.get(0))
            .contains("Recovery attempt 2")
            .contains("prior response was empty or invalid")
            .contains("exact canonical JSON");
        assertThat(server.requestTiers()).containsOnly(LlmGatewayTier.BACKGROUND_BATCH.headerValue());
    }

    @Test
    void should_OmitThinkingBudgetWhen_ReasoningEffortIsUnset() throws Exception {
        server.enqueueJson(chatCompletion("stop", seoJson()));

        seoClient().generate(BOOK_ID, "Grounded prompt", LlmGatewayTier.BACKGROUND_BATCH);

        assertThat(server.requestBodies()).hasSize(1);
        String requestBody = server.requestBodies().getFirst();
        JsonNode request = new ObjectMapper().readTree(requestBody);

        assertThat(request.has("thinking_budget_tokens")).isFalse();
        assertThat(requestBody)
            .doesNotContain("\"reasoning_effort\":")
            .doesNotContain("\"enable_thinking\":false")
            .doesNotContain("\"disable_reasoning\":true");
    }

    @ParameterizedTest
    @MethodSource("net.findmybook.boot.OpenAiProperties#supportedReasoningEfforts")
    void should_SendEveryConfiguredStandardReasoningEffortWithoutThinkingBudget_When_GeneratingSeoMetadata(
        String reasoningEffort
    ) throws Exception {
        server.enqueueJson(chatCompletion("stop", seoJson()));

        seoClient(reasoningEffort).generate(BOOK_ID, "Grounded prompt", LlmGatewayTier.BACKGROUND_BATCH);

        JsonNode request = new ObjectMapper().readTree(server.requestBodies().getFirst());
        assertThat(request.path("reasoning_effort").asString()).isEqualTo(reasoningEffort);
        assertThat(request.has("thinking_budget_tokens")).isFalse();
    }

    @Test
    void should_RetryMalformedSuccessfulSeoResponse_When_SecondGemmaResponseIsValid() {
        server.enqueueJson("{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"gemma-4-26b-a4b\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + OpenAiTestServer.jsonString(seoJson())
            + "}}]}");
        server.enqueueJson(chatCompletion("stop", seoJson()));

        SeoMetadataCandidate candidate = seoClient().generate(BOOK_ID, "Grounded prompt", LlmGatewayTier.BACKGROUND_BATCH);

        assertThat(candidate.seoDescription()).hasSizeBetween(140, 160);
        assertThat(server.requestBodies()).hasSize(2);
    }

    @Test
    void should_ThrowTypedFailure_When_SeoResponseMissesCanonicalField() {
        SeoMetadataJsonParser parser = new SeoMetadataJsonParser(new ObjectMapper());

        assertThatThrownBy(() -> parser.parse("{\"seoTitle\":\"Title only\"}"))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("fields must exactly match the canonical contract");
    }

    @Test
    void should_AcceptWholeResponseFencedSeoJson_When_GemmaReturnsCanonicalJson() {
        SeoMetadataJsonParser parser = new SeoMetadataJsonParser(new ObjectMapper());
        SeoMetadataCandidate expected = new SeoMetadataCandidate(
            "Test Book - Book Details | findmybook.net",
            "A specific grounded description helps readers understand this test book and decide whether its practical focus matches their interests and reading goals."
        );

        assertThat(parser.parse(fencedSeoJson("```"))).isEqualTo(expected);
        assertThat(parser.parse(fencedSeoJson("```json"))).isEqualTo(expected);
    }

    @Test
    void should_RejectNonCanonicalSeoResponse_When_GemmaDriftsFromJsonContract() {
        SeoMetadataJsonParser parser = new SeoMetadataJsonParser(new ObjectMapper());
        String fencedResponse = fencedSeoJson("```json");

        assertThatThrownBy(() -> parser.parse("SEO title: Test Book; description: useful details"))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("valid JSON object");
        assertThatThrownBy(() -> parser.parse("{\"title\":\"Test Book\",\"description\":\"Useful details\"}"))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("exactly match the canonical contract");
        assertThatThrownBy(() -> parser.parse("{\"seoTitle\":42,\"seoDescription\":true}"))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("field must be a string: seoTitle");
        assertThatThrownBy(() -> parser.parse(
            "{\"seoTitle\":\"Test Book\",\"seoDescription\":\"Useful details\",\"extra\":true}"
        )).isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("exactly match the canonical contract");
        assertThatThrownBy(() -> parser.parse("Here is the requested JSON:\n" + fencedResponse))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("valid JSON object");
        assertThatThrownBy(() -> parser.parse(fencedResponse + "\nA trailing note"))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("whole-response");
        assertThatThrownBy(() -> parser.parse(fencedSeoJson("```markdown")))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("whole-response");
        assertThatThrownBy(() -> parser.parse(fencedResponse + "\n" + fencedResponse))
            .isInstanceOf(BookSeoGenerationException.class);
    }

    private BookSeoMetadataGenerationService seoService(BookSeoMetadataRepository repository) {
        BookSearchService searchService = mock(BookSearchService.class);
        BookDataOrchestrator orchestrator = mock(BookDataOrchestrator.class);
        BookDetail detail = bookDetail();
        when(searchService.fetchBookDetail(BOOK_ID)).thenReturn(Optional.of(detail));
        when(orchestrator.enrichDescriptionForAiIfNeeded(BOOK_ID, detail, DESCRIPTION, 50)).thenReturn(DESCRIPTION);
        return new BookSeoMetadataGenerationService(
            repository, searchService, orchestrator, seoClient(), new SeoMetadataNormalizationPolicy());
    }

    private BookSeoMetadataClient seoClient() {
        return new BookSeoMetadataClient(new ObjectMapper(), server.openAiProperties());
    }

    private BookSeoMetadataClient seoClient(String reasoningEffort) {
        OpenAiProperties properties = server.openAiProperties();
        properties.setReasoningEffort(reasoningEffort);
        return new BookSeoMetadataClient(new ObjectMapper(), properties);
    }

    private BookDetail bookDetail() {
        return new BookDetail(BOOK_ID.toString(), "test-book", "Test Book", DESCRIPTION, "Test Publisher",
            LocalDate.of(2020, 1, 1), "en", 200, List.of("Author One"), List.of("Category One"),
            "https://example.com/cover.jpg", null, "https://example.com/fallback.jpg",
            "https://example.com/thumbnail.jpg", 600, 900, true, "GOOGLE_BOOKS", 4.5, 42,
            "1234567890", "1234567890123", "https://example.com/preview", "https://example.com/info",
            Map.of("source", "test"), List.of());
    }

    private static String seoJson() {
        SeoMetadataCandidate candidate = new SeoMetadataCandidate(
            "Test Book - Book Details | findmybook.net",
            "A specific grounded description helps readers understand this test book and decide whether its practical focus matches their interests and reading goals."
        );
        return new ObjectMapper().valueToTree(candidate).toString();
    }

    private static String fencedSeoJson(String fenceOpener) {
        return fenceOpener + "\n" + seoJson() + "\n```";
    }

    private static String chatCompletion(String finishReason, String content) {
        return "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"gemma-4-26b-a4b\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
            + OpenAiTestServer.jsonString(content) + ",\"refusal\":null},\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    static final class OpenAiTestServer implements AutoCloseable {
        private final HttpServer httpServer;
        private final Deque<Response> responses = new ConcurrentLinkedDeque<>();
        private final List<String> requestBodies = new CopyOnWriteArrayList<>();
        private final List<String> requestTiers = new CopyOnWriteArrayList<>();

        OpenAiTestServer() throws IOException {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/v1/chat/completions", this::handle);
            httpServer.start();
        }

        void enqueueJson(String body) {
            enqueueJson(200, body);
        }

        void enqueueJson(int statusCode, String body) {
            responses.addLast(new Response(statusCode, "application/json", body));
        }

        void enqueueSse(String body) {
            responses.addLast(new Response(200, "text/event-stream", body + "data: [DONE]\n\n"));
        }

        List<String> requestBodies() {
            return List.copyOf(requestBodies);
        }

        List<String> requestTiers() {
            return List.copyOf(requestTiers);
        }

        OpenAiProperties openAiProperties() {
            OpenAiProperties properties = new OpenAiProperties();
            properties.getApi().setKey("test-key");
            properties.getBase().setUrl(baseUrl());
            properties.setModel("gemma-4-26b-a4b");
            properties.setRequestTimeoutSeconds(5);
            properties.setReadTimeoutSeconds(5);
            return properties;
        }

        static String jsonString(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/v1";
        }

        private void handle(HttpExchange exchange) throws IOException {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestTiers.add(exchange.getRequestHeaders().getFirst(LlmGatewayTier.HEADER_NAME));
            Response response = responses.removeFirst();
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.statusCode(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            httpServer.stop(0);
        }

        private record Response(int statusCode, String contentType, String body) { }
    }
}
