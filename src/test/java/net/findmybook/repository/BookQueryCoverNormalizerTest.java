package net.findmybook.repository;

import net.findmybook.dto.BookCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link BookQueryCoverNormalizer} constructs its fallback SQL
 * correctly and normalizes cover URLs without runtime failures.
 *
 * <p>The SQL text block mixes {@code String.formatted()} with SQL {@code LIKE '%...'} patterns.
 * A missing percent-escape causes {@link java.util.UnknownFormatConversionException} at runtime
 * (e.g., {@code %p} in {@code '%placeholder...'}).  These tests guard against regressions.
 */
@ExtendWith(MockitoExtension.class)
class BookQueryCoverNormalizerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookQueryCoverNormalizer normalizer;

    @BeforeEach
    void setUp() {
        // Jackson 3.x ObjectMapper (tools.jackson namespace) — transitive via Spring Boot 4.0.x BOM
        var resultSetSupport = new BookQueryResultSetSupport(new tools.jackson.databind.ObjectMapper());
        normalizer = new BookQueryCoverNormalizer(jdbcTemplate, resultSetSupport);
    }

    @Test
    @SuppressWarnings("unchecked") // Mockito generic type erasure on ResultSetExtractor
    void should_BuildFallbackSqlWithoutFormatError_When_CardNeedsCoverFallback() {
        // Arrange: card with placeholder cover triggers needsFallback -> fetchFallbackCovers
        String bookId = UUID.randomUUID().toString();
        BookCard cardNeedingFallback = new BookCard(
            bookId, "test-slug", "Test Title", List.of("Author"),
            "https://example.com/placeholder-book-cover.svg",
            null, null, 4.0, 10, Map.of(), null, null
        );

        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
            .thenReturn(Map.of());

        // Act + Assert: no UnknownFormatConversionException
        assertThatCode(() -> normalizer.normalizeBookCardCovers(List.of(cardNeedingFallback)))
            .doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_PassValidSqlToJdbc_When_FetchingFallbackCovers() {
        // Arrange
        String bookId = UUID.randomUUID().toString();
        BookCard card = new BookCard(
            bookId, "slug", "Title", List.of("Author"),
            "", // empty cover triggers fallback
            null, null, 3.5, 5, Map.of(), null, null
        );

        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
            .thenReturn(Map.of());

        // Act
        normalizer.normalizeBookCardCovers(List.of(card));

        // Assert: SQL was dispatched, and LIKE wildcards are literal percent signs
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("NOT LIKE '%placeholder-book-cover.svg%'");
        assertThat(sql).contains("NOT LIKE '%printsec=titlepage%'");
        assertThat(sql).contains("NOT LIKE '%printsec=copyright%'");
        assertThat(sql).contains("NOT LIKE '%printsec=toc%'");
    }

    @Test
    void should_ReturnCardsUnchanged_When_CoverUrlIsValid() {
        // Arrange: valid HTTPS cover does not trigger fallback query
        BookCard validCard = new BookCard(
            UUID.randomUUID().toString(), "slug", "Title", List.of("Author"),
            "https://cdn.example.com/cover.jpg",
            "images/cover.jpg", null, 4.5, 100, Map.of(), null, null
        );

        // Act
        List<BookCard> result = normalizer.normalizeBookCardCovers(List.of(validCard));

        // Assert: card returned as-is, no JDBC interaction
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().coverUrl()).isEqualTo("https://cdn.example.com/cover.jpg");
    }

    @Test
    void should_ReturnEmptyList_When_InputIsEmpty() {
        List<BookCard> result = normalizer.normalizeBookCardCovers(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void should_ReturnEmptyList_When_InputIsNull() {
        List<BookCard> result = normalizer.normalizeBookCardCovers(null);
        assertThat(result).isEmpty();
    }
}
