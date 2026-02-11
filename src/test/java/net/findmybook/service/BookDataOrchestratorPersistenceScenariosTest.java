package net.findmybook.service;

import tools.jackson.databind.ObjectMapper;
import net.findmybook.dto.BookAggregate;
import net.findmybook.model.Book;
import net.findmybook.util.ApplicationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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
    private net.findmybook.mapper.GoogleBooksMapper googleBooksMapper;

    @Mock
    private BookUpsertService bookUpsertService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookDataOrchestrator orchestrator;
    private BookExternalBatchPersistenceService batchPersistenceService;

    // Concrete dependencies instantiated in setUp()
    private PostgresBookRepository postgresBookRepository;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        postgresBookRepository = new PostgresBookRepository(jdbcTemplate, om);
        batchPersistenceService =
            new BookExternalBatchPersistenceService(om, googleBooksMapper, bookUpsertService);

        orchestrator = new BookDataOrchestrator(
                bookSearchService,
                postgresBookRepository,
                batchPersistenceService,
                Optional.empty(),
                Optional.empty(),
                Optional.of(googleBooksMapper),
                bookUpsertService
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
        fallback.setDescription("Fallback summary");
        fallback.setExternalImageUrl("https://example.com/cover.jpg");
        fallback.setRetrievedFrom("OPEN_LIBRARY");
        fallback.setLanguage("en");
        fallback.setPublisher("Fallback Publishing");
        fallback.setPageCount(321);
        fallback.setPublishedDate(Date.from(Instant.parse("2020-01-01T00:00:00Z")));

        boolean persisted = batchPersistenceService.persistBook(fallback, null, null);

        assertThat(persisted).isTrue();
        ArgumentCaptor<BookAggregate> aggregateCaptor = ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService).upsert(aggregateCaptor.capture());

        BookAggregate aggregate = aggregateCaptor.getValue();
        assertThat(aggregate.getTitle()).isEqualTo("Fallback Title");
        assertThat(aggregate.getAuthors()).containsExactly("Author Example");
        assertThat(aggregate.getDescription()).isEqualTo("Fallback summary");
        assertThat(aggregate.getLanguage()).isEqualTo("en");
        assertThat(aggregate.getPublisher()).isEqualTo("Fallback Publishing");
        assertThat(aggregate.getPageCount()).isEqualTo(321);
        assertThat(aggregate.getPublishedDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(aggregate.getCategories()).containsExactly("Fiction");
        assertThat(aggregate.getIdentifiers().getSource()).isEqualTo("OPEN_LIBRARY");
        assertThat(aggregate.getIdentifiers().getImageLinks()).containsEntry("thumbnail", "https://example.com/cover.jpg");
    }

    @Test
    void persistBook_mapsUtcPublishedDateWithoutLocalTimezoneDrift() {
        when(googleBooksMapper.map(any())).thenReturn(null);
        when(bookUpsertService.upsert(any())).thenReturn(BookUpsertService.UpsertResult.builder()
            .bookId(UUID.randomUUID())
            .slug("utc-book")
            .isNew(true)
            .build());

        Book fallback = new Book();
        fallback.setId("OLUTC1");
        fallback.setTitle("UTC Boundary Book");
        fallback.setAuthors(List.of("Author"));
        fallback.setExternalImageUrl("https://example.com/utc-cover.jpg");
        fallback.setRetrievedFrom("OPEN_LIBRARY");
        fallback.setPublishedDate(Date.from(Instant.parse("1997-01-01T00:00:00Z")));

        boolean persisted = batchPersistenceService.persistBook(fallback, null, null);

        assertThat(persisted).isTrue();
        ArgumentCaptor<BookAggregate> aggregateCaptor = ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService).upsert(aggregateCaptor.capture());
        BookAggregate aggregate = aggregateCaptor.getValue();
        assertThat(aggregate.getPublishedDate()).isEqualTo(LocalDate.of(1997, 1, 1));
    }

    @Test
    void isSystemicDatabaseError_detectsWrappedConnectionFailure() {
        java.net.ConnectException rootCause = new java.net.ConnectException("Connection refused");
        RuntimeException wrapped = new RuntimeException("Systemic database error during upsert", rootCause);

        boolean systemic = batchPersistenceService.isSystemicDatabaseError(wrapped);

        assertThat(systemic).isTrue();
    }

    @Test
    void persistBook_returnsFalseDuringShutdown_When_SystemicDatabaseErrorOccurs() {
        when(googleBooksMapper.map(any())).thenReturn(BookAggregate.builder()
            .title("Shutdown Fixture")
            .slugBase("shutdown-fixture")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-SHUTDOWN-1")
                .build())
            .build());
        when(bookUpsertService.upsert(any()))
            .thenThrow(new DataAccessResourceFailureException("Connection closed"));
        ReflectionTestUtils.invokeMethod(batchPersistenceService, "markShutdownInProgress");

        Book incoming = new Book();
        incoming.setId("OL-SHUTDOWN-1");
        incoming.setTitle("Shutdown Fixture");

        boolean persisted = batchPersistenceService.persistBook(incoming, null, null);

        assertThat(persisted).isFalse();
    }

    @Test
    void persistBook_returnsFalse_When_BookUpsertFailsWithNonSystemicException() {
        when(googleBooksMapper.map(any())).thenReturn(BookAggregate.builder()
            .title("Non Systemic Fixture")
            .slugBase("non-systemic-fixture")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-NON-SYSTEMIC-1")
                .build())
            .build());
        when(bookUpsertService.upsert(any()))
            .thenThrow(new IllegalStateException("Cover persistence returned unsuccessful result for test-book"));

        Book incoming = new Book();
        incoming.setId("OL-NON-SYSTEMIC-1");
        incoming.setTitle("Non Systemic Fixture");

        boolean persisted = batchPersistenceService.persistBook(incoming, null, null);

        assertThat(persisted).isFalse();
    }

    @Test
    void classifyBookUpsertFailureCode_returnsSystemicCode_When_DatabaseConnectivityFailureOccurs() {
        String code = batchPersistenceService.classifyBookUpsertFailureCode(
            new DataAccessResourceFailureException("Connection refused")
        );

        assertThat(code).isEqualTo("BOOK_UPSERT_SYSTEMIC_DB");
    }

    @Test
    void classifyBookUpsertFailureCode_returnsNonSystemicCode_When_DomainValidationFailureOccurs() {
        String code = batchPersistenceService.classifyBookUpsertFailureCode(
            new IllegalStateException("Cover persistence returned unsuccessful result")
        );

        assertThat(code).isEqualTo("BOOK_UPSERT_NON_SYSTEMIC");
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
