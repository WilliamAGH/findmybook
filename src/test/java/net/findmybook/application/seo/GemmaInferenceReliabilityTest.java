package net.findmybook.application.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.adapters.persistence.BookSeoMetadataRepository;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.boot.OpenAiProperties;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.domain.seo.BookSeoMetadataSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.support.llm.LlmGatewayTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GemmaInferenceReliabilityTest {

    private static final UUID BOOK_ID = UUID.fromString("019da3e5-3838-703e-9112-bad4a489239e");
    private static final String DESCRIPTION = "A detailed source description with enough grounded context for reliable AI generation and metadata tests.";
    private static final BookAiContent AI_CONTENT = new BookAiContent(
        "A grounded two-sentence summary explains the book clearly. It gives readers enough detail to judge its focus.",
        "Readers seeking a concise and practical overview.",
        List.of("clarity", "reliability", "context"),
        List.of("Use source evidence.", "Prefer explicit contracts."),
        "A practical work situated in its field."
    );
    private static final String AI_JSON = new ObjectMapper().valueToTree(AI_CONTENT).toString();

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
    void should_ReplaceFallback_When_GemmaRecoversForUnchangedPrompt() {
        server.enqueueJson(chatCompletion("length", seoJson()));
        server.enqueueJson(chatCompletion("stop", seoJson()));
        BookSeoMetadataRepository repository = mock(BookSeoMetadataRepository.class);
        AtomicReference<BookSeoMetadataSnapshot> current = new AtomicReference<>();
        when(repository.fetchCurrent(BOOK_ID)).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        when(repository.insertNewCurrentVersion(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                BookSeoMetadataSnapshot snapshot = new BookSeoMetadataSnapshot(
                    BOOK_ID, current.get() == null ? 1 : 2, Instant.EPOCH,
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(5));
                current.set(snapshot);
                return snapshot;
            });

        BookSeoMetadataGenerationService service = seoService(repository);
        assertThatThrownBy(() -> service.generateAndPersistIfPromptChanged(BOOK_ID))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("deterministic fallback persisted");
        assertThat(current.get().provider()).isEqualTo(BookSeoMetadataGenerationService.FALLBACK_PROVIDER);
        BookSeoMetadataGenerationService.GenerationOutcome second = service.generateAndPersistIfPromptChanged(BOOK_ID);
        BookSeoMetadataGenerationService.GenerationOutcome third = service.generateAndPersistIfPromptChanged(BOOK_ID);

        assertThat(second.snapshot()).get().extracting(BookSeoMetadataSnapshot::provider).isEqualTo("openai");
        assertThat(third.generated()).isFalse();
        assertThat(server.requestBodies()).hasSize(2).allSatisfy(body -> assertThat(body).contains("\"max_completion_tokens\":1000"));
    }

    @Test
    void should_RetryBlankSeoResponse_When_SecondGemmaResponseIsValid() {
        server.enqueueJson(chatCompletion("stop", ""));
        server.enqueueJson(chatCompletion("stop", seoJson()));

        SeoMetadataCandidate candidate = seoClient().generate(BOOK_ID, "Grounded prompt", LlmGatewayTier.BACKGROUND_BATCH);

        assertThat(candidate.seoTitle()).isEqualTo("Test Book - Book Details | findmybook.net");
        assertThat(server.requestBodies()).hasSize(2);
        assertThat(server.requestTiers()).containsOnly(LlmGatewayTier.BACKGROUND_BATCH.headerValue());
    }

    @Test
    void should_RetryMalformedSuccessfulSeoResponse_When_SecondGemmaResponseIsValid() {
        server.enqueueJson("{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"gemma-4-26b-a4b\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + jsonString(seoJson()) + "}}]}");
        server.enqueueJson(chatCompletion("stop", seoJson()));

        SeoMetadataCandidate candidate = seoClient().generate(BOOK_ID, "Grounded prompt", LlmGatewayTier.BACKGROUND_BATCH);

        assertThat(candidate.seoDescription()).hasSizeBetween(140, 160);
        assertThat(server.requestBodies()).hasSize(2);
    }

    @Test
    void should_RetryEmptyReaderStreamWithoutReplayingContent_When_SecondAttemptSucceeds() {
        server.enqueueSse(streamChunk("", "stop"));
        server.enqueueSse(streamChunk(AI_JSON, null) + streamChunk("", "stop"));
        BookAiContentRepository repository = mock(BookAiContentRepository.class);
        when(repository.insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> new BookAiContentSnapshot(
                BOOK_ID, 1, Instant.EPOCH, invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(1)));
        List<String> deltas = new ArrayList<>();

        BookAiContentService.GeneratedContent generated = aiService(repository)
            .generateAndPersist(BOOK_ID, deltas::add, LlmGatewayTier.LIVE_RENDER);

        assertThat(generated.snapshot().aiContent().summary()).contains("grounded two-sentence summary");
        assertThat(deltas).containsExactly(AI_JSON);
        assertThat(server.requestBodies()).hasSize(2);
    }

    @Test
    void should_RetryLiveTransportFailureWithoutReplayingContent_When_SecondAttemptSucceeds() {
        server.enqueueJson(503, "{\"error\":{\"message\":\"temporarily unavailable\"}}");
        server.enqueueSse(streamChunk(AI_JSON, null) + streamChunk("", "stop"));
        BookAiContentRepository repository = mock(BookAiContentRepository.class);
        when(repository.insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> new BookAiContentSnapshot(
                BOOK_ID, 1, Instant.EPOCH, invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(1)));
        List<String> deltas = new ArrayList<>();

        BookAiContentService.GeneratedContent generated = aiService(repository)
            .generateAndPersist(BOOK_ID, deltas::add, LlmGatewayTier.LIVE_RENDER);

        assertThat(generated.snapshot().aiContent().summary()).contains("grounded two-sentence summary");
        assertThat(deltas).containsExactly(AI_JSON);
        assertThat(server.requestBodies()).hasSize(2);
    }

    @Test
    void should_RejectReaderContent_When_GemmaEndsAtTokenLimit() {
        server.enqueueSse(streamChunk(AI_JSON, null) + streamChunk("", "length"));
        BookAiContentRepository repository = mock(BookAiContentRepository.class);

        assertThatThrownBy(() -> aiService(repository)
            .generateAndPersist(BOOK_ID, ignored -> { }, LlmGatewayTier.LIVE_RENDER))
            .isInstanceOf(BookAiGenerationException.class)
            .hasMessageContaining("completion token budget");
        verify(repository, never()).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
        assertThat(server.requestBodies()).singleElement().asString().contains("\"max_completion_tokens\":1000");
    }

    @Test
    void should_RejectReaderContentWithoutPersistence_When_GemmaRefusesRequest() {
        server.enqueueSse(streamRefusalChunk("Policy refusal", "stop"));
        BookAiContentRepository repository = mock(BookAiContentRepository.class);

        assertThatThrownBy(() -> aiService(repository)
            .generateAndPersist(BOOK_ID, ignored -> { }, LlmGatewayTier.LIVE_RENDER))
            .isInstanceOf(BookAiGenerationException.class)
            .hasMessageContaining("refused");
        verify(repository, never()).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
        assertThat(server.requestBodies()).hasSize(1);
        assertThat(server.requestTiers()).containsExactly(LlmGatewayTier.LIVE_RENDER.headerValue());
    }

    @Test
    void should_ThrowTypedFailure_When_SeoResponseMissesRequiredField() {
        SeoMetadataJsonParser parser = new SeoMetadataJsonParser(new ObjectMapper());

        assertThatThrownBy(() -> parser.parse("{\"seoTitle\":\"Title only\"}"))
            .isInstanceOf(BookSeoGenerationException.class)
            .hasMessageContaining("missing required field: seoDescription");
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
        return new BookSeoMetadataClient(new ObjectMapper(), openAiProperties());
    }

    private BookAiContentService aiService(BookAiContentRepository repository) {
        BookSearchService searchService = mock(BookSearchService.class);
        BookDataOrchestrator orchestrator = mock(BookDataOrchestrator.class);
        BookDetail detail = bookDetail();
        when(searchService.fetchBookDetail(BOOK_ID)).thenReturn(Optional.of(detail));
        when(orchestrator.enrichDescriptionForAiIfNeeded(BOOK_ID, detail, DESCRIPTION, 50)).thenReturn(DESCRIPTION);
        return new BookAiContentService(
            repository, mock(BookIdentifierResolver.class), searchService, orchestrator, new ObjectMapper(), openAiProperties());
    }

    private OpenAiProperties openAiProperties() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.getApi().setKey("test-key");
        properties.getBase().setUrl(server.baseUrl());
        properties.setModel("gemma-4-26b-a4b");
        properties.setRequestTimeoutSeconds(5);
        properties.setReadTimeoutSeconds(5);
        return properties;
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

    private static String chatCompletion(String finishReason, String content) {
        return "{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"gemma-4-26b-a4b\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
            + jsonString(content) + ",\"refusal\":null},\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    private static String streamChunk(String content, String finishReason) {
        String reason = finishReason == null ? "null" : "\"" + finishReason + "\"";
        return "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"gemma-4-26b-a4b\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"
            + jsonString(content) + "},\"finish_reason\":" + reason + "}]}\n\n";
    }

    private static String streamRefusalChunk(String refusal, String finishReason) {
        return "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"gemma-4-26b-a4b\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"refusal\":" + jsonString(refusal)
            + "},\"finish_reason\":\"" + finishReason + "\"}]}\n\n";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static final class OpenAiTestServer implements AutoCloseable {
        private final HttpServer httpServer;
        private final Deque<Response> responses = new ConcurrentLinkedDeque<>();
        private final List<String> requestBodies = new CopyOnWriteArrayList<>();
        private final List<String> requestTiers = new CopyOnWriteArrayList<>();

        private OpenAiTestServer() throws IOException {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/v1/chat/completions", this::handle);
            httpServer.start();
        }

        private void enqueueJson(String body) {
            enqueueJson(200, body);
        }

        private void enqueueJson(int statusCode, String body) {
            responses.addLast(new Response(statusCode, "application/json", body));
        }

        private void enqueueSse(String body) {
            responses.addLast(new Response(200, "text/event-stream", body + "data: [DONE]\n\n"));
        }

        private List<String> requestBodies() {
            return List.copyOf(requestBodies);
        }

        private List<String> requestTiers() {
            return List.copyOf(requestTiers);
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
