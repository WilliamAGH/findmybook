package net.findmybook.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
}
