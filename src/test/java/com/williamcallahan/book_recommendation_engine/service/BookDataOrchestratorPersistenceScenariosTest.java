package com.williamcallahan.book_recommendation_engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.book_recommendation_engine.dto.BookAggregate;
import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.util.ApplicationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers retrofit checklist scenarios for dedupe and edition-chaining behavior.
 * See docs/task-retrofit-code-for-postgres-schema.md.
 */
@ExtendWith(MockitoExtension.class)
class BookDataOrchestratorPersistenceScenariosTest {

    @Mock
    private BookSearchService bookSearchService;

    @Mock
    private com.williamcallahan.book_recommendation_engine.mapper.GoogleBooksMapper googleBooksMapper;

    @Mock
    private BookUpsertService bookUpsertService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookDataOrchestrator orchestrator;

    // Concrete dependencies instantiated in setUp()
    private PostgresBookRepository postgresBookRepository;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        postgresBookRepository = new PostgresBookRepository(jdbcTemplate, om);

        orchestrator = new BookDataOrchestrator(
                om,
                bookSearchService,
                postgresBookRepository,
                bookUpsertService,
                googleBooksMapper
        );
        lenient().when(bookSearchService.searchBooks(anyString(), any())).thenReturn(List.of());
        lenient().when(bookSearchService.searchByIsbn(anyString())).thenReturn(java.util.Optional.empty());
        lenient().when(bookSearchService.searchAuthors(anyString(), any())).thenReturn(List.of());
        lenient().doNothing().when(bookSearchService).refreshMaterializedView();

        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any()))
                .thenThrow(new EmptyResultDataAccessException(1));
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
                .thenThrow(new EmptyResultDataAccessException(1));
    }

    @Test
    @Disabled("Method resolveCanonicalBookId was moved from BookDataOrchestrator to CanonicalBookPersistenceService (deprecated)")
    void resolveCanonicalBookId_prefersExistingExternalMapping() {
        whenExternalIdLookupReturns("existing-book-id");

        Book incoming = new Book();
        incoming.setId("temporary-id");
        incoming.setIsbn13("9781234567890");
        incoming.setIsbn10("1234567890");

        String resolved = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "resolveCanonicalBookId",
                incoming,
                "google-abc",
                "9781234567890",
                "1234567890"
        );

        assertThat(resolved).isEqualTo("existing-book-id");
    }

    @Test
    void parseBookJsonPayload_returnsNullForConcatenatedPayloads() {
        String payload = "{\"id\":\"-0UZAAAAYAAJ\",\"title\":\"-0UZAAAAYAAJ\",\"authors\":[\"Ralph Tate\",\"Samuel Peckworth Woodward\"],\"publisher\":\"C. Lockwood and Company\",\"publishedDate\":\"1830\",\"pageCount\":627,\"rawJsonResponse\":\"{\\\"kind\\\":\\\"books#volume\\\",\\\"id\\\":\\\"-0UZAAAAYAAJ\\\",\\\"etag\\\":\\\"PAlEva08Grw\\\",\\\"selfLink\\\":\\\"https://www.googleapis.com/books/v1/volumes/-0UZAAAAYAAJ\\\",\\\"volumeInfo\\\":{\\\"title\\\":\\\"A Manual of the Mollusca\\\",\\\"subtitle\\\":\\\"Being a Treatise on Recent and Fossil Shells\\\",\\\"authors\\\":[\\\"Samuel Peckworth Woodward\\\",\\\"Ralph Tate\\\"],\\\"publisher\\\":\\\"C. Lockwood and Company\\\",\\\"publishedDate\\\":\\\"1830\\\",\\\"pageCount\\\":627}}\",\"rawJsonSource\":\"GoogleBooks\",\"contributingSources\":[\"GoogleBooks\"]}{\"id\":\"-0UZAAAAYAAJ\",\"title\":\"-0UZAAAAYAAJ\",\"authors\":[\"Ralph Tate\",\"Samuel Peckworth Woodward\"],\"publisher\":\"C. Lockwood and Company\",\"publishedDate\":\"1830\",\"pageCount\":627,\"rawJsonResponse\":\"{\\\"kind\\\":\\\"books#volume\\\",\\\"id\\\":\\\"-0UZAAAAYAAJ\\\",\\\"volumeInfo\\\":{\\\"title\\\":\\\"A Manual of the Mollusca\\\"}}\",\"rawJsonSource\":\"GoogleBooks\",\"contributingSources\":[\"GoogleBooks\"]}";

        JsonNode result = ReflectionTestUtils.invokeMethod(BookDataOrchestrator.class, "parseBookJsonPayload", payload, "test.json");

        assertThat(result).isNull();
    }

    @Test
    void parseBookJsonPayload_unwrapsRawJsonResponse() {
        String payload = "{\"id\":\"--AMEAAAQBAJ\",\"title\":\"--AMEAAAQBAJ\",\"authors\":[\"Gabriel Gambetta\"],\"description\":\"Computer graphics book\",\"publisher\":\"No Starch Press\",\"publishedDate\":\"2021-05-18\",\"pageCount\":248,\"rawJsonResponse\":\"{\\\"volumeInfo\\\":{\\\"title\\\":\\\"Computer Graphics from Scratch\\\",\\\"authors\\\":[\\\"Gabriel Gambetta\\\"]}}\",\"rawJsonSource\":\"GoogleBooks\"}";

        JsonNode result = ReflectionTestUtils.invokeMethod(BookDataOrchestrator.class, "parseBookJsonPayload", payload, "single.json");

        assertThat(result).isNotNull();
        JsonNode safeResult = Objects.requireNonNull(result);
        assertThat(safeResult.path("id").asText()).isEqualTo("--AMEAAAQBAJ");
        assertThat(safeResult.path("rawJsonResponse").isTextual()).isTrue();
    }

    @Test
    void persistBook_usesFallbackAggregateWhenMapperReturnsNull() {
        when(googleBooksMapper.map(any())).thenReturn(null);
        when(bookUpsertService.upsert(any())).thenReturn(BookUpsertService.UpsertResult.builder()
            .bookId(UUID.randomUUID())
            .slug("fallback-book")
            .isNew(true)
            .build());

        Book fallback = new Book();
        fallback.setId("OL123M");
        fallback.setTitle("Fallback Title");
        fallback.setAuthors(List.of("Author Example"));
        fallback.setCategories(List.of("Fiction"));
        fallback.setExternalImageUrl("https://example.com/cover.jpg");
        fallback.setRetrievedFrom("OPEN_LIBRARY");
        fallback.setLanguage("en");
        fallback.setPublisher("Fallback Publishing");
        fallback.setPageCount(321);
        fallback.setPublishedDate(Date.from(Instant.parse("2020-01-01T00:00:00Z")));

        Boolean persisted = ReflectionTestUtils.invokeMethod(orchestrator, "persistBook", fallback, (JsonNode) null);

        assertThat(persisted).isTrue();
        ArgumentCaptor<BookAggregate> aggregateCaptor = ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService).upsert(aggregateCaptor.capture());

        BookAggregate aggregate = aggregateCaptor.getValue();
        assertThat(aggregate.getTitle()).isEqualTo("Fallback Title");
        assertThat(aggregate.getAuthors()).containsExactly("Author Example");
        assertThat(aggregate.getIdentifiers().getSource()).isEqualTo("OPEN_LIBRARY");
        assertThat(aggregate.getIdentifiers().getImageLinks()).containsEntry("thumbnail", "https://example.com/cover.jpg");
    }

    private void whenExternalIdLookupReturns(String bookId) {
        lenient().when(jdbcTemplate.queryForObject(
                eq("SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1"),
                eq(String.class),
                eq(ApplicationConstants.Provider.GOOGLE_BOOKS),
                any()
        )).thenReturn(bookId);
    }
}
