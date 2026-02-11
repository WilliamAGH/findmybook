package net.findmybook.application.ai;

import com.openai.errors.OpenAIException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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

        assertThatThrownBy(() -> service.generateAndPersist(bookId, delta -> {}))
            .isInstanceOfSatisfying(BookAiGenerationException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT);
                assertThat(exception.getMessage()).contains("missing or too short");
            });
    }

    @Test
    void should_ThrowEnrichmentFailed_When_AllEnrichmentProvidersFail() {
        BookAiContentService service = newService();
        UUID bookId = UUID.randomUUID();
        BookDetail detail = bookDetailWithDescription("short");
        when(bookSearchService.fetchBookDetail(bookId)).thenReturn(java.util.Optional.of(detail));
        when(bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, "short", 50))
            .thenThrow(new IllegalStateException("All description enrichment providers failed"));

        assertThatThrownBy(() -> service.generateAndPersist(bookId, delta -> {}))
            .isInstanceOfSatisfying(BookAiGenerationException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(BookAiGenerationException.ErrorCode.ENRICHMENT_FAILED);
                assertThat(exception.getMessage()).contains("enrichment failed");
                assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
            });
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
    void should_ReturnTrueForRetryableFailure_When_GenerationFailureIsCausedByOpenAiException() {
        BookAiContentService service = newService();
        OpenAIException openAiException = mock(OpenAIException.class);
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): HTTP 503 server error",
            openAiException
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryableGenerationFailure", generationFailure);

        assertThat(retryable).isTrue();
    }

    @Test
    void should_ReturnTrueForRetryableFailure_When_ParseFailureHasRetryableMessage() {
        BookAiContentService service = newService();
        BookAiGenerationException generationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.GENERATION_FAILED,
            "AI content generation failed (gpt-5-mini): AI response did not include a valid JSON object",
            new IllegalStateException("AI response did not include a valid JSON object")
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryableGenerationFailure", generationFailure);

        assertThat(retryable).isTrue();
    }

    @Test
    void should_ReturnFalseForRetryableFailure_When_ErrorCodeIsNotGenerationFailed() {
        BookAiContentService service = newService();
        BookAiGenerationException validationFailure = new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT,
            "Book description is missing or too short for faithful AI generation."
        );

        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryableGenerationFailure", validationFailure);

        assertThat(retryable).isFalse();
    }

    @Test
    void should_ParsePlainTextFallback_When_ModelReturnsNonJsonSections() {
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

        BookAiContent parsed = parser.parse(plainTextResponse);

        assertThat(parsed.summary()).contains("practical guide");
        assertThat(parsed.readerFit()).contains("Engineers and technical leads");
        assertThat(parsed.keyThemes()).contains("incident response", "observability");
        assertThat(parsed.takeaways()).contains("Establish shared ownership for reliability outcomes.");
        assertThat(parsed.context()).contains("SRE ideas");
    }

    @Test
    void should_ThrowWhenParserCannotBuildSummary_When_ResponseHasNoUsefulContent() {
        AiContentJsonParser parser = new AiContentJsonParser(new ObjectMapper());

        assertThatThrownBy(() -> parser.parse("   "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI content response was empty");
    }

    private BookAiContentService newService() {
        return new BookAiContentService(
            repository,
            identifierResolver,
            bookSearchService,
            bookDataOrchestrator,
            objectMapper,
            "fake-key",
            "https://api.openai.com/v1",
            "gpt-5-mini",
            120,
            75
        );
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
