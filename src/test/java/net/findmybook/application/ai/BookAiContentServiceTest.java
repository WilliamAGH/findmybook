package net.findmybook.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class BookAiContentServiceTest {

    private BookAiContentRepository repository;
    private BookIdentifierResolver identifierResolver;
    private BookSearchService bookSearchService;
    private ObjectMapper objectMapper;
    private BookAiContentService service;

    @BeforeEach
    void setUp() {
        repository = mock(BookAiContentRepository.class);
        identifierResolver = mock(BookIdentifierResolver.class);
        bookSearchService = mock(BookSearchService.class);
        objectMapper = new ObjectMapper();
        
        service = new BookAiContentService(
            repository,
            identifierResolver,
            bookSearchService,
            objectMapper,
            "fake-key",
            "https://api.openai.com/v1",
            "gpt-5-mini",
            120,
            75
        );
    }

    @Test
    void isAvailable_ReturnsTrue_WhenApiKeyConfigured() {
        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_ReturnsFalse_WhenApiKeyMissing() {
        BookAiContentService disabledService = new BookAiContentService(
            repository, identifierResolver, bookSearchService, objectMapper,
            "", "", "model", 10, 10
        );
        assertThat(disabledService.isAvailable()).isFalse();
    }

    @Test
    void resolveBookId_DelegatesToResolver() {
        UUID id = UUID.randomUUID();
        when(identifierResolver.resolveToUuid("slug")).thenReturn(Optional.of(id));
        
        assertThat(service.resolveBookId("slug")).contains(id);
    }

    @ParameterizedTest
    @CsvSource({
        "https://api.openai.com,https://api.openai.com/v1",
        "https://api.openai.com/v1,https://api.openai.com/v1",
        "https://models.inference.ai.azure.com/inference,https://models.inference.ai.azure.com/inference/v1",
        "https://api.openai.com/embeddings,https://api.openai.com/v1",
        "https://api.openai.com/v1/embeddings,https://api.openai.com/v1",
        "https://popos-sf7.com/,https://popos-sf7.com/v1"
    })
    void should_NormalizeOpenAiBaseUrl_When_ValueProvided(String input, String expected) {
        assertThat(BookAiContentService.normalizeSdkBaseUrl(input)).isEqualTo(expected);
    }

    @Test
    void should_DefaultOpenAiBaseUrl_When_ValueMissing() {
        assertThat(BookAiContentService.normalizeSdkBaseUrl(" ")).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void configuredModel_ReturnsConfiguredValue() {
        assertThat(service.configuredModel()).isEqualTo("gpt-5-mini");
    }

    @Test
    void apiMode_ReturnsChat() {
        assertThat(service.apiMode()).isEqualTo("chat");
    }

    @Test
    void fromSnapshot_MapsAllFields() {
        BookAiContent aiContent = new BookAiContent(
            "Summary", "Fit", List.of("T1", "T2"),
            List.of("Takeaway A", "Takeaway B"), "A context sentence."
        );
        BookAiContentSnapshot snapshot = new BookAiContentSnapshot(
            UUID.randomUUID(), 3, Instant.parse("2026-02-08T12:00:00Z"), "gpt-5", "openai", aiContent
        );

        BookAiContentSnapshotDto dto = BookAiContentSnapshotDto.fromSnapshot(snapshot);

        assertThat(dto.summary()).isEqualTo("Summary");
        assertThat(dto.readerFit()).isEqualTo("Fit");
        assertThat(dto.keyThemes()).containsExactly("T1", "T2");
        assertThat(dto.takeaways()).containsExactly("Takeaway A", "Takeaway B");
        assertThat(dto.context()).isEqualTo("A context sentence.");
        assertThat(dto.version()).isEqualTo(3);
        assertThat(dto.model()).isEqualTo("gpt-5");
        assertThat(dto.provider()).isEqualTo("openai");
    }

    @Test
    void findCurrent_DelegatesToRepository() {
        UUID bookId = UUID.randomUUID();
        BookAiContent aiContent = new BookAiContent("S", "F", List.of("T"), null, null);
        BookAiContentSnapshot snapshot = new BookAiContentSnapshot(bookId, 1, Instant.now(), "m", "p", aiContent);
        when(repository.fetchCurrent(bookId)).thenReturn(Optional.of(snapshot));

        Optional<BookAiContentSnapshot> result = service.findCurrent(bookId);

        assertThat(result).isPresent();
        assertThat(result.get().aiContent().summary()).isEqualTo("S");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        " ",
        "short",
        "1234567890123456789012345678901234567890123456789"
    })
    void should_ThrowBookAiGenerationException_When_DescriptionIsMissingOrTooShort(String description) {
        UUID bookId = UUID.randomUUID();
        when(bookSearchService.fetchBookDetail(bookId)).thenReturn(Optional.of(bookDetailWithDescription(description)));

        assertThatThrownBy(() -> service.generateAndPersist(bookId, delta -> {}))
            .isInstanceOfSatisfying(BookAiGenerationException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT);
                assertThat(exception.getMessage()).contains("missing or too short");
            });
    }

    @Test
    void should_MapNullReaderFit_When_SnapshotHasNoReaderFit() {
        BookAiContent aiContent = new BookAiContent("Summary", null, List.of("Theme"), null, null);
        BookAiContentSnapshot snapshot = new BookAiContentSnapshot(
            UUID.randomUUID(), 4, Instant.parse("2026-02-09T00:00:00Z"), "gpt-5-mini", "openai", aiContent
        );

        BookAiContentSnapshotDto dto = BookAiContentSnapshotDto.fromSnapshot(snapshot);

        assertThat(dto.readerFit()).isNull();
        assertThat(dto.summary()).isEqualTo("Summary");
    }

    @Test
    void generateAndPersist_ThrowsWhenServiceDisabled() {
        BookAiContentService disabledService = new BookAiContentService(
            repository, identifierResolver, bookSearchService, objectMapper,
            "", "", "model", 10, 10
        );
        UUID bookId = UUID.randomUUID();

        assertThatThrownBy(() -> disabledService.generateAndPersist(bookId, delta -> {}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not configured");
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
