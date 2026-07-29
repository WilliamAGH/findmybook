package net.findmybook.application.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.BookLookupService;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.support.llm.LlmGatewayTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private BookSeoMetadataClientWireTest.OpenAiTestServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new BookSeoMetadataClientWireTest.OpenAiTestServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
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
    void should_RetryShapeDriftWithoutPersistence_When_SecondReaderResponseIsCanonical() {
        server.enqueueSse(streamChunk(AI_JSON.replace("}", ",\"unexpected\":true}"), null) + streamChunk("", "stop"));
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
        verify(repository).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
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
        server.enqueueSse(streamChunk(AI_JSON, null) + streamChunk("", "length"));
        BookAiContentRepository repository = mock(BookAiContentRepository.class);

        assertThatThrownBy(() -> aiService(repository)
            .generateAndPersist(BOOK_ID, ignored -> { }, LlmGatewayTier.LIVE_RENDER))
            .isInstanceOf(BookAiGenerationException.class)
            .hasMessageContaining("completion token budget");
        verify(repository, never()).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
        assertThat(server.requestBodies()).hasSize(2).allSatisfy(body ->
            assertThat(body).contains("\"max_completion_tokens\":" + LlmGatewayTier.LIVE_RENDER.maxCompletionTokens()));
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
    void should_RetryNonStopReaderResponseWithoutReplayingContent_When_SecondAttemptSucceeds() {
        server.enqueueSse(streamChunk(AI_JSON, null) + streamChunk("", "content_filter"));
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
        verify(repository).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void should_PersistValidatedReaderContentBeforeDeliveringBufferedPayload() {
        server.enqueueSse(streamChunk(AI_JSON, null) + streamChunk("", "stop"));
        BookAiContentRepository repository = mock(BookAiContentRepository.class);
        AtomicBoolean persisted = new AtomicBoolean(false);
        when(repository.insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                persisted.set(true);
                return new BookAiContentSnapshot(
                    BOOK_ID, 1, Instant.EPOCH, invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(1));
            });

        assertThatThrownBy(() -> aiService(repository).generateAndPersist(
            BOOK_ID,
            validatedBufferedPayload -> {
                assertThat(persisted).isTrue();
                assertThat(validatedBufferedPayload).isEqualTo(AI_JSON);
                throw new IllegalStateException("delivery closed");
            },
            LlmGatewayTier.LIVE_RENDER
        )).isInstanceOf(IllegalStateException.class).hasMessage("delivery closed");

        verify(repository).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void should_PreserveDisplayedBookIdentity_When_ResolvingReaderGuideTarget() {
        BookIdentifierResolver identifierResolver = mock(BookIdentifierResolver.class);
        when(identifierResolver.resolveExactBookUuid("test-book")).thenReturn(Optional.of(BOOK_ID));
        BookSearchService searchService = mock(BookSearchService.class);
        BookDataOrchestrator orchestrator = mock(BookDataOrchestrator.class);

        BookAiContentService service = new BookAiContentService(
            mock(BookAiContentRepository.class),
            identifierResolver,
            searchService,
            orchestrator,
            new ObjectMapper(),
            server.openAiProperties()
        );

        assertThat(service.resolveBookId("test-book")).contains(BOOK_ID);
        verify(identifierResolver).resolveExactBookUuid("test-book");
        verify(identifierResolver, never()).resolveToUuid("test-book");
    }

    @Test
    void should_NotCanonicalizeExactUuidThroughWorkCluster_When_ResolvingDisplayedBook() {
        BookLookupService lookupService = mock(BookLookupService.class);
        BookQueryRepository queryRepository = mock(BookQueryRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BookIdentifierResolver identifierResolver = new BookIdentifierResolver(
            lookupService,
            queryRepository,
            jdbcTemplate
        );

        assertThat(identifierResolver.resolveExactBookUuid(BOOK_ID.toString())).contains(BOOK_ID);
        verifyNoInteractions(lookupService, queryRepository, jdbcTemplate);
    }

    @Test
    void should_NotCanonicalizeExactSlugThroughWorkCluster_When_ResolvingDisplayedBook() {
        BookLookupService lookupService = mock(BookLookupService.class);
        BookQueryRepository queryRepository = mock(BookQueryRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(queryRepository.fetchBookDetailBySlug("test-book")).thenReturn(Optional.of(bookDetail()));
        BookIdentifierResolver identifierResolver = new BookIdentifierResolver(
            lookupService,
            queryRepository,
            jdbcTemplate
        );

        assertThat(identifierResolver.resolveExactBookUuid("test-book")).contains(BOOK_ID);
        verifyNoInteractions(lookupService, jdbcTemplate);
    }

    private BookAiContentService aiService(BookAiContentRepository repository) {
        BookSearchService searchService = mock(BookSearchService.class);
        BookDataOrchestrator orchestrator = mock(BookDataOrchestrator.class);
        BookDetail detail = bookDetail();
        when(searchService.fetchBookDetail(BOOK_ID)).thenReturn(Optional.of(detail));
        when(orchestrator.enrichDescriptionForAiIfNeeded(BOOK_ID, detail, DESCRIPTION, 50)).thenReturn(DESCRIPTION);
        return new BookAiContentService(
            repository, mock(BookIdentifierResolver.class), searchService, orchestrator, new ObjectMapper(),
            server.openAiProperties());
    }

    private BookDetail bookDetail() {
        return new BookDetail(BOOK_ID.toString(), "test-book", "Test Book", DESCRIPTION, "Test Publisher",
            LocalDate.of(2020, 1, 1), "en", 200, List.of("Author One"), List.of("Category One"),
            "https://example.com/cover.jpg", null, "https://example.com/fallback.jpg",
            "https://example.com/thumbnail.jpg", 600, 900, true, "GOOGLE_BOOKS", 4.5, 42,
            "1234567890", "1234567890123", "https://example.com/preview", "https://example.com/info",
            Map.of("source", "test"), List.of());
    }

    private static String streamChunk(String content, String finishReason) {
        String reason = finishReason == null ? "null" : "\"" + finishReason + "\"";
        return "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"gemma-4-26b-a4b\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"
            + BookSeoMetadataClientWireTest.OpenAiTestServer.jsonString(content)
            + "},\"finish_reason\":" + reason + "}]}\n\n";
    }

    private static String streamRefusalChunk(String refusal, String finishReason) {
        return "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"gemma-4-26b-a4b\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"refusal\":"
            + BookSeoMetadataClientWireTest.OpenAiTestServer.jsonString(refusal)
            + "},\"finish_reason\":\"" + finishReason + "\"}]}\n\n";
    }
}
