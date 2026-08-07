package net.findmybook.application.ai;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.SseException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.boot.OpenAiProperties;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;

class BookAiContentServiceTest {

    private BookAiContentRepository repository;
    private BookIdentifierResolver identifierResolver;
    private BookSearchService bookSearchService;
    private BookDataOrchestrator bookDataOrchestrator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository = mock(BookAiContentRepository.class);
        identifierResolver = mock(BookIdentifierResolver.class);
        bookSearchService = mock(BookSearchService.class);
        bookDataOrchestrator = mock(BookDataOrchestrator.class);
        objectMapper = new ObjectMapper();
    }

    @Test
    void should_ThrowDescriptionTooShort_When_DescriptionRemainsInsufficient() {
        BookAiContentService service = newService();
        UUID bookId = UUID.randomUUID();
        BookDetail detail = bookDetailWithDescription("short");
        when(bookSearchService.fetchBookDetail(bookId)).thenReturn(java.util.Optional.of(detail));
        when(bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, "short", 50))
            .thenReturn("short");

        assertThatThrownBy(() -> service.generateAndPersist(bookId, delta -> {}, net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER))
            .isInstanceOfSatisfying(BookAiGenerationException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT);
                assertThat(exception.getMessage()).contains("missing or too short");
            });
    }

    @ParameterizedTest
    @MethodSource("descriptionEnrichmentFailures")
    void should_ClassifyDescriptionEnrichmentFailure_When_ProviderOperationFails(
        RuntimeException providerFailure,
        BookAiGenerationException.ErrorCode expectedErrorCode
    ) {
        BookAiContentService service = newService();
        UUID bookId = UUID.randomUUID();
        BookDetail detail = bookDetailWithDescription("short");
        when(bookSearchService.fetchBookDetail(bookId)).thenReturn(java.util.Optional.of(detail));
        when(bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, "short", 50))
            .thenThrow(providerFailure);

        assertThatThrownBy(() -> service.generateAndPersist(bookId, delta -> {}, net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER))
            .isInstanceOfSatisfying(BookAiGenerationException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(expectedErrorCode);
                assertThat(exception.getMessage()).contains("enrichment failed");
                assertThat(exception.getCause()).isSameAs(providerFailure);
            });
    }

    @Test
    void should_PropagateProgrammingFailure_When_DescriptionEnrichmentThrowsUnexpectedRuntimeException() {
        BookAiContentService service = newService();
        UUID bookId = UUID.randomUUID();
        BookDetail detail = bookDetailWithDescription("short");
        NullPointerException programmingFailure = new NullPointerException("unexpected defect");
        when(bookSearchService.fetchBookDetail(bookId)).thenReturn(java.util.Optional.of(detail));
        when(bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, "short", 50))
            .thenThrow(programmingFailure);

        assertThatThrownBy(() -> service.generateAndPersist(
            bookId,
            delta -> {},
            net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER
        )).isSameAs(programmingFailure);
    }

    @Test
    void should_UseEnrichedDescription_When_OrchestratorReturnsLongerDescription() {
        BookAiContentService service = newService();
        UUID bookId = UUID.randomUUID();
        BookDetail detail = bookDetailWithDescription("short");
        String enrichedDescription = "This is a materially richer Open Library description with enough content for faithful AI generation.";
        when(bookSearchService.fetchBookDetail(bookId)).thenReturn(java.util.Optional.of(detail));
        when(bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, "short", 50))
            .thenReturn(enrichedDescription);

        Object context = ReflectionTestUtils.invokeMethod(service, "loadPromptContext", bookId);

        assertThat(context).isNotNull();
        assertThat(context.toString()).contains(enrichedDescription);
        verify(bookDataOrchestrator).enrichDescriptionForAiIfNeeded(bookId, detail, "short", 50);
    }

    @Test
    void should_StopBeforeGenerationAndPersistence_When_RequestIsCancelledAfterPromptLoad() {
        BookAiContentService service = newService();
        UUID bookId = UUID.randomUUID();
        String description = "A sufficiently detailed description that can support grounded reader guide generation.";
        BookDetail detail = bookDetailWithDescription(description);
        BookAiContentService.GenerationControl generationControl = new BookAiContentService.GenerationControl();
        when(bookSearchService.fetchBookDetail(bookId)).thenReturn(java.util.Optional.of(detail));
        when(bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, description, 50))
            .thenAnswer(invocation -> {
                generationControl.cancel();
                return description;
            });

        assertThatThrownBy(() -> service.generateAndPersist(
            bookId,
            ignored -> { },
            net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER,
            generationControl
        )).isInstanceOf(CancellationException.class)
            .hasMessage("AI content generation cancelled");

        verify(repository, never()).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void should_AllowClaimedPersistenceToFinish_When_CancellationArrivesAfterClaim() throws Exception {
        BookAiContentService.GenerationControl generationControl = new BookAiContentService.GenerationControl();
        CountDownLatch persistenceClaimed = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);

        CompletableFuture<String> persistenceResult = CompletableFuture.supplyAsync(() ->
            generationControl.persistIfActive(() -> {
                persistenceClaimed.countDown();
                try {
                    if (!releasePersistence.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Persistence release timed out");
                    }
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Persistence was interrupted", interruptedException);
                }
                return "persisted";
            })
        );

        assertThat(persistenceClaimed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(generationControl.cancel()).isFalse();
        releasePersistence.countDown();

        assertThat(persistenceResult.get(5, TimeUnit.SECONDS)).isEqualTo("persisted");
    }

    @Test
    void should_NotInsert_When_CancellationClaimsPersistenceBoundaryFirst() throws Exception {
        BookAiContentService.GenerationControl generationControl = new BookAiContentService.GenerationControl();
        CountDownLatch persistenceBoundaryReached = new CountDownLatch(1);
        CountDownLatch allowPersistenceClaim = new CountDownLatch(1);
        AtomicReference<Throwable> persistenceFailure = new AtomicReference<>();
        BookAiContent aiContent = new BookAiContent(
            "Summary",
            "Reader fit",
            List.of("Theme"),
            List.of("Takeaway"),
            "Context"
        );
        UUID bookId = UUID.randomUUID();

        Thread persistenceThread = Thread.ofPlatform().start(() -> {
            persistenceBoundaryReached.countDown();
            try {
                boolean released = allowPersistenceClaim.await(5, TimeUnit.SECONDS);
                if (!released) {
                    persistenceFailure.set(new IllegalStateException("Persistence boundary release timed out"));
                    return;
                }
                generationControl.persistIfActive(
                    () -> repository.insertNewCurrentVersion(bookId, aiContent, "model", "provider", "hash")
                );
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                persistenceFailure.set(interruptedException);
            } catch (CancellationException cancellation) {
                persistenceFailure.set(cancellation);
            }
        });

        assertThat(persistenceBoundaryReached.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(generationControl.cancel()).isTrue();
        allowPersistenceClaim.countDown();
        persistenceThread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(persistenceThread.isAlive()).isFalse();
        assertThat(persistenceFailure.get())
            .isInstanceOf(CancellationException.class)
            .hasMessage("AI content generation cancelled before persistence");
        verify(repository, never()).insertNewCurrentVersion(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void should_ReturnTrueForRetryableFailure_When_LiveTransportFailsBeforeContent() {
        BookAiContentService service = newService();
        OpenAIServiceException openAiException = mock(OpenAIServiceException.class);
        when(openAiException.statusCode()).thenReturn(503);
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): HTTP 503 server error",
            openAiException
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            generationFailure,
            net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER
        );

        assertThat(retryable).isTrue();
    }

    @Test
    void should_ReturnFalseForRetryableFailure_When_BackgroundServiceFailureCanBeRetriedBySdk() {
        BookAiContentService service = newService();
        OpenAIServiceException openAiException = mock(OpenAIServiceException.class);
        when(openAiException.statusCode()).thenReturn(503);
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): HTTP 503 server error",
            openAiException
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            generationFailure,
            net.findmybook.support.llm.LlmGatewayTier.BACKGROUND_BATCH
        );

        assertThat(retryable).isFalse();
    }

    @Test
    void should_ReturnTrueForRetryableFailure_When_BackgroundStreamClosesAfterHttp200() {
        BookAiContentService service = newService();
        SseException incompleteStream = SseException.builder()
            .statusCode(200)
            .headers(Headers.builder().build())
            .cause(new IOException("peer closed incomplete stream"))
            .build();
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): peer closed incomplete stream",
            incompleteStream
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            generationFailure,
            net.findmybook.support.llm.LlmGatewayTier.BACKGROUND_BATCH
        );

        assertThat(retryable).isTrue();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("backgroundSdkRetryableTransportFailures")
    void should_ReturnFalseForRetryableFailure_When_BackgroundTransportFailureCanBeRetriedBySdk(OpenAIException transportFailure) {
        BookAiContentService service = newService();
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): stream transport failure",
            transportFailure
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            generationFailure,
            net.findmybook.support.llm.LlmGatewayTier.BACKGROUND_BATCH
        );

        assertThat(retryable).isFalse();
    }

    @Test
    void should_ReturnFalseForRetryableFailure_When_BackgroundRequestIsUnauthorized() {
        BookAiContentService service = newService();
        OpenAIServiceException unauthorized = mock(OpenAIServiceException.class);
        when(unauthorized.statusCode()).thenReturn(401);
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): HTTP 401 unauthorized",
            unauthorized
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            generationFailure,
            net.findmybook.support.llm.LlmGatewayTier.BACKGROUND_BATCH
        );

        assertThat(retryable).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void should_ReturnFalseForRetryableFailure_When_LiveRequestHasNonRetryableStatus(int statusCode) {
        BookAiContentService service = newService();
        OpenAIServiceException openAiException = mock(OpenAIServiceException.class);
        when(openAiException.statusCode()).thenReturn(statusCode);
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): HTTP %d".formatted(statusCode),
            openAiException
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            generationFailure,
            net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER
        );

        assertThat(retryable).isFalse();
    }

    @Test
    void should_ReturnTrueForRetryableFailure_When_ResponseIsInvalid() {
        BookAiContentService service = newService();
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.INVALID_RESPONSE,
            "AI content generation failed (gpt-5-mini): invalid response"
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            generationFailure,
            net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER
        );

        assertThat(retryable).isTrue();
    }

    @Test
    void should_ReturnFalseForRetryableFailure_When_ErrorCodeIsNotGenerationFailed() {
        BookAiContentService service = newService();
        BookAiGenerationException validationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT,
            "Book description is missing or too short for faithful AI generation."
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(
            service,
            "isRetryableGenerationFailure",
            validationFailure,
            net.findmybook.support.llm.LlmGatewayTier.LIVE_RENDER
        );

        assertThat(retryable).isFalse();
    }

    @Test
    void should_RejectPlainText_When_ModelReturnsNonJsonSections() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());

        String plainTextResponse = """
            Summary: A practical guide to building resilient systems in fast-moving teams.
            Reader Fit: Engineers and technical leads who need actionable reliability practices.
            Key Themes:
            - incident response
            - observability
            - operational excellence
            Takeaways:
            - Establish shared ownership for reliability outcomes.
            - Invest in runbooks and post-incident learning.
            Context: Aligns modern SRE ideas with day-to-day delivery pressure.
            """;

        assertThatThrownBy(() -> parser.parse(plainTextResponse))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("valid JSON object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"```", "```json"})
    void should_AcceptCanonicalJson_When_ResponseUsesAllowedWholeResponseFence(String fenceOpener) {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());

        BookAiContent content = parser.parse(fencedAiContentJson(fenceOpener));

        assertThat(content).isEqualTo(parser.parse(validAiContentJson()));
    }

    @Test
    void should_RejectProseOutsideFence_When_ModelReturnsFencedJson() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String fencedResponse = fencedAiContentJson("```json");

        assertThatThrownBy(() -> parser.parse("Here is the requested JSON:\n" + fencedResponse))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parser.parse(fencedResponse + "\nThis is the requested JSON."))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("whole-response");
    }

    @Test
    void should_RejectUnknownOrMultipleFences_When_ResponseIsNotOneAllowedFence() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String canonicalJson = validAiContentJson();
        String fencedResponse = fencedAiContentJson("```json");

        assertThatThrownBy(() -> parser.parse("```yaml\n" + canonicalJson + "\n```"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("whole-response");
        assertThatThrownBy(() -> parser.parse(fencedResponse + "\n```json\n" + canonicalJson + "\n```"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_ThrowWhenParserCannotBuildSummary_When_ResponseHasNoUsefulContent() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());

        assertThatThrownBy(() -> parser.parse("   "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI content response was empty");
    }

    @Test
    void should_RejectMalformedJson_When_ResponseIsTruncated() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());

        String truncatedResponse = """
            {"summary":"A detailed but incomplete response", "keyThemes":["democracy"],
            """;

        assertThatThrownBy(() -> parser.parse(truncatedResponse))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("valid JSON object");
    }

    @Test
    void should_RejectUnknownTopLevelField_When_ResponseDriftsFromCanonicalContract() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson().replace("}", ",\"unexpected\":true}");

        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown field: unexpected");
    }

    @Test
    void should_RejectNonStringSummary_When_ResponseHasWrongScalarType() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson().replace("\"A reliable summary with enough words for validation.\"", "42");

        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary must be a nonblank JSON string");
    }

    @Test
    void should_RejectBlankSummary_When_ResponseHasNoRequiredProse() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson().replace("\"A reliable summary with enough words for validation.\"", "\"  \"");

        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("field must be nonblank: summary");
    }

    @Test
    void should_RejectNonStringReaderFit_When_OptionalTextHasWrongScalarType() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson().replace("\"Readers who value grounded recommendations.\"", "false");

        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("readerFit must be a JSON string or null");
    }

    @Test
    void should_RejectNonArrayKeyThemes_When_ResponseHasWrongCollectionType() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson().replace("[\"reliability\"]", "\"reliability\"");

        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("keyThemes must be an array of JSON strings");
    }

    @Test
    void should_RejectNonStringTheme_When_ResponseHasWrongArrayElementType() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson().replace("\"reliability\"", "42");

        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("keyThemes[0] must be a nonblank JSON string");
    }

    @Test
    void should_SkipBlankListItems_When_ResponseContainsOtherwiseValidContent() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson()
            .replace("[\"reliability\"]", "[\"reliability\", \"  \", \"evidence\"]");

        BookAiContent content = parser.parse(response);

        assertThat(content.keyThemes()).containsExactly("reliability", "evidence");
    }

    @Test
    void should_RejectAliasField_When_ResponseOmitsCanonicalFieldName() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = validAiContentJson().replace("\"readerFit\":", "\"reader_fit\":");

        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown field: reader_fit");
    }

    @Test
    void should_AcceptNullOptionalTextAndEmptyArrays_When_ResponseUsesCanonicalShape() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());
        String response = new ObjectMapper().valueToTree(new BookAiContent(
            "A reliable summary with enough words for validation.",
            null,
            List.of(),
            null,
            null
        )).toString();

        BookAiContent content = parser.parse(response);

        assertThat(content.readerFit()).isNull();
        assertThat(content.keyThemes()).isEmpty();
        assertThat(content.takeaways()).isNull();
        assertThat(content.context()).isNull();
    }

    private String validAiContentJson() {
        return new ObjectMapper().valueToTree(new BookAiContent(
            "A reliable summary with enough words for validation.",
            "Readers who value grounded recommendations.",
            List.of("reliability"),
            List.of("Keep contracts explicit."),
            "A concise context for the reader guide."
        )).toString();
    }

    private static Stream<OpenAIException> backgroundSdkRetryableTransportFailures() {
        return Stream.of(
            new OpenAIIoException("peer closed incomplete stream", new IOException("connection closed")),
            new OpenAIRetryableException("stream transport retryable failure", new IOException("connection reset"))
        );
    }

    private static Stream<Arguments> descriptionEnrichmentFailures() {
        RequestNotPermitted rateLimiterDenial = RequestNotPermitted.createRequestNotPermitted(
            RateLimiter.ofDefaults("reader-guide-enrichment")
        );
        CallNotPermittedException circuitDenial = CallNotPermittedException.createCallNotPermittedException(
            CircuitBreaker.ofDefaults("reader-guide-enrichment")
        );
        IllegalStateException suppressedRateLimiterDenial = new IllegalStateException(
            "Open Library provider unavailable"
        );
        suppressedRateLimiterDenial.addSuppressed(RequestNotPermitted.createRequestNotPermitted(
            RateLimiter.ofDefaults("reader-guide-enrichment-google")
        ));
        IllegalStateException suppressedCircuitDenial = new IllegalStateException(
            "Open Library provider unavailable"
        );
        suppressedCircuitDenial.addSuppressed(CallNotPermittedException.createCallNotPermittedException(
            CircuitBreaker.ofDefaults("reader-guide-enrichment-google")
        ));
        return Stream.of(
            Arguments.of(rateLimiterDenial, BookAiGenerationException.ErrorCode.LOCAL_RATE_LIMITED),
            Arguments.of(
                new IllegalStateException("Open Library admission was denied", rateLimiterDenial),
                BookAiGenerationException.ErrorCode.LOCAL_RATE_LIMITED
            ),
            Arguments.of(circuitDenial, BookAiGenerationException.ErrorCode.LOCAL_CIRCUIT_OPEN),
            Arguments.of(
                new IllegalStateException("Open Library circuit rejected the call", circuitDenial),
                BookAiGenerationException.ErrorCode.LOCAL_CIRCUIT_OPEN
            ),
            Arguments.of(
                suppressedRateLimiterDenial,
                BookAiGenerationException.ErrorCode.LOCAL_RATE_LIMITED
            ),
            Arguments.of(
                suppressedCircuitDenial,
                BookAiGenerationException.ErrorCode.LOCAL_CIRCUIT_OPEN
            ),
            Arguments.of(
                new IllegalStateException(
                    "Open Library description enrichment exceeded its deadline",
                    new TimeoutException("enrichment deadline elapsed")
                ),
                BookAiGenerationException.ErrorCode.ENRICHMENT_FAILED
            ),
            Arguments.of(
                WebClientResponseException.create(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "provider unavailable",
                    HttpHeaders.EMPTY,
                    new byte[0],
                    StandardCharsets.UTF_8
                ),
                BookAiGenerationException.ErrorCode.ENRICHMENT_FAILED
            )
        );
    }

    private String fencedAiContentJson(String fenceOpener) {
        return "%s%n%s%n```".formatted(fenceOpener, validAiContentJson());
    }

    private BookAiContentService newService() {
        return new BookAiContentService(
            repository,
            identifierResolver,
            bookSearchService,
            bookDataOrchestrator,
            objectMapper,
            openAiProperties()
        );
    }

    private OpenAiProperties openAiProperties() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.getApi().setKey("fake-key");
        properties.getBase().setUrl("https://api.openai.com/v1");
        properties.setModel("gpt-5-mini");
        properties.setRequestTimeoutSeconds(120);
        properties.setReadTimeoutSeconds(75);
        return properties;
    }

    private BookDetail bookDetailWithDescription(String description) {
        return new BookDetail(
            UUID.randomUUID().toString(),
            "test-book",
            "Test Book",
            description,
            "Test Publisher",
            LocalDate.of(2020, 1, 1),
            "en",
            200,
            List.of("Author One"),
            List.of("Category One"),
            "https://example.com/cover.jpg",
            null,
            "https://example.com/cover-fallback.jpg",
            "https://example.com/thumbnail.jpg",
            600,
            900,
            true,
            "GOOGLE_BOOKS",
            4.5,
            42,
            "1234567890",
            "1234567890123",
            "https://example.com/preview",
            "https://example.com/info",
            Map.of("source", "test"),
            List.of()
        );
    }
}
