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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
                batchPersistenceService
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
    void bookUpsertService_findExistingBookId_acquiresAdvisoryLockWithQueryExecution() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = mock(BookUpsertTransactionService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = mock(BookImageLinkPersistenceService.class);
        BookOutboxEventService outboxEventService = mock(BookOutboxEventService.class);

        when(lockJdbcTemplate.query(
            eq("SELECT pg_advisory_xact_lock(?)"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
            anyLong()
        )).thenReturn(null);
        when(lockJdbcTemplate.query(
            eq("SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("OPEN_LIBRARY"),
            eq("OL-LOCK-1")
        )).thenReturn(null);

        BookUpsertService upsertService = new BookUpsertService(
            lockJdbcTemplate,
            transactionService,
            imageLinkPersistenceService,
            outboxEventService
        );

        BookAggregate aggregate = BookAggregate.builder()
            .title("Lock Candidate")
            .slugBase("lock-candidate")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-LOCK-1")
                .build())
            .build();

        Optional<UUID> existingBookId = ReflectionTestUtils.invokeMethod(upsertService, "findExistingBookId", aggregate);

        assertThat(existingBookId).isEmpty();
        verify(lockJdbcTemplate).query(
            eq("SELECT pg_advisory_xact_lock(?)"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
            anyLong()
        );
    }

    @Test
    void bookUpsertService_shouldRetryWithNewSlug_When_SlugConstraintCollides() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = mock(BookUpsertTransactionService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = mock(BookImageLinkPersistenceService.class);
        BookOutboxEventService outboxEventService = mock(BookOutboxEventService.class);

        when(lockJdbcTemplate.query(
            eq("SELECT pg_advisory_xact_lock(?)"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
            anyLong()
        )).thenReturn(null);
        when(lockJdbcTemplate.query(
            eq("SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("OPEN_LIBRARY"),
            eq("OL-SLUG-1")
        )).thenReturn(null);
        when(transactionService.ensureUniqueSlug(eq("john-grisham"), any(UUID.class), eq(true)))
            .thenReturn("john-grisham", "john-grisham-1");
        doThrow(new DuplicateKeyException("duplicate key value violates unique constraint \"books_slug_key\""))
            .doNothing()
            .when(transactionService)
            .upsertBookRecord(any(UUID.class), any(BookAggregate.class), anyString());
        when(imageLinkPersistenceService.persistImageLinks(any(UUID.class), any()))
            .thenReturn(BookImageLinkPersistenceService.ImageLinkPersistenceResult.empty());

        BookUpsertService upsertService = new BookUpsertService(
            lockJdbcTemplate,
            transactionService,
            imageLinkPersistenceService,
            outboxEventService
        );

        BookAggregate aggregate = BookAggregate.builder()
            .title("John Grisham")
            .slugBase("john-grisham")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-SLUG-1")
                .build())
            .build();

        BookUpsertService.UpsertResult result = upsertService.upsert(aggregate);

        assertThat(result.getSlug()).isEqualTo("john-grisham-1");
        verify(transactionService, times(2)).ensureUniqueSlug(eq("john-grisham"), any(UUID.class), eq(true));
        verify(transactionService, times(2)).upsertBookRecord(any(UUID.class), any(BookAggregate.class), anyString());
    }

    @Test
    void bookUpsertService_findExistingBookId_matchesBySlug_When_IdentifiersDoNotResolve() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = mock(BookUpsertTransactionService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = mock(BookImageLinkPersistenceService.class);
        BookOutboxEventService outboxEventService = mock(BookOutboxEventService.class);

        UUID existingBookId = UUID.randomUUID();

        when(lockJdbcTemplate.query(
            eq("SELECT pg_advisory_xact_lock(?)"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
            anyLong()
        )).thenReturn(null);
        when(lockJdbcTemplate.query(
            eq("SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("OPEN_LIBRARY"),
            eq("OL-SLUG-MATCH-1")
        )).thenReturn(null);
        when(lockJdbcTemplate.query(
            eq("SELECT id FROM books WHERE slug = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("the-partner-john-grisham")
        )).thenReturn(existingBookId);

        BookUpsertService upsertService = new BookUpsertService(
            lockJdbcTemplate,
            transactionService,
            imageLinkPersistenceService,
            outboxEventService
        );

        BookAggregate aggregate = BookAggregate.builder()
            .title("The Partner")
            .slugBase("the-partner-john-grisham")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-SLUG-MATCH-1")
                .build())
            .build();

        Optional<UUID> existing = ReflectionTestUtils.invokeMethod(upsertService, "findExistingBookId", aggregate);

        assertThat(existing).contains(existingBookId);
        verify(lockJdbcTemplate).query(
            eq("SELECT id FROM books WHERE slug = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("the-partner-john-grisham")
        );
    }

    @Test
    void outboxRelay_shouldPrioritizeLowerRetryCountAndReportFullQueueStats() {
        JdbcTemplate relayJdbcTemplate = mock(JdbcTemplate.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        OutboxRelay relay = new OutboxRelay(relayJdbcTemplate, messagingTemplate);

        when(relayJdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<?>>any(), eq(100))).thenReturn(List.of());
        relay.relayEvents();

        ArgumentCaptor<String> fetchSql = ArgumentCaptor.forClass(String.class);
        verify(relayJdbcTemplate).query(fetchSql.capture(), org.mockito.ArgumentMatchers.<RowMapper<?>>any(), eq(100));
        String normalizedFetchSql = fetchSql.getValue().replaceAll("\\s+", " ").trim();
        assertThat(normalizedFetchSql).contains("ORDER BY retry_count ASC, created_at ASC");

        when(relayJdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<?>>any()))
            .thenThrow(new DataAccessResourceFailureException("simulated"));
        assertThatThrownBy(relay::getOutboxStats)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to fetch outbox stats");

        ArgumentCaptor<String> statsSql = ArgumentCaptor.forClass(String.class);
        verify(relayJdbcTemplate).queryForObject(statsSql.capture(), org.mockito.ArgumentMatchers.<RowMapper<?>>any());
        String normalizedStatsSql = statsSql.getValue().replaceAll("\\s+", " ").trim();
        assertThat(normalizedStatsSql).doesNotContain("WHERE created_at > NOW() - INTERVAL '1 hour'");
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
