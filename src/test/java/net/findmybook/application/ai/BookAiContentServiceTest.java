package net.findmybook.application.ai;

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
