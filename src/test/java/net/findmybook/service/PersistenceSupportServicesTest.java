package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.service.image.CoverPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistenceSupportServicesTest {

    @Test
    void bookImageLinkPersistenceService_shouldReturnFallbackCanonicalUrl_When_CoverPersistenceReturnsUnsuccessful() {
        JdbcTemplate imageJdbcTemplate = mock(JdbcTemplate.class);
        CoverPersistenceService coverPersistenceService = mock(CoverPersistenceService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = new BookImageLinkPersistenceService(
            imageJdbcTemplate,
            coverPersistenceService
        );
        stubAbsentExistingCoverQuality(imageJdbcTemplate);
        UUID bookId = UUID.randomUUID();
        BookAggregate.ExternalIdentifiers identifiers = BookAggregate.ExternalIdentifiers.builder()
            .source("GOOGLE_BOOKS")
            .imageLinks(Map.of("thumbnail", "http://example.com/cover.jpg"))
            .build();

        when(coverPersistenceService.persistFromGoogleImageLinks(eq(bookId), anyMap(), eq("GOOGLE_BOOKS")))
            .thenReturn(new CoverPersistenceService.PersistenceResult(false, null, null, null, false));

        BookImageLinkPersistenceService.ImageLinkPersistenceResult result =
            imageLinkPersistenceService.persistImageLinks(bookId, identifiers);

        assertThat(result.persisted()).isFalse();
        assertThat(result.normalizedImageLinks()).containsEntry("thumbnail", "https://example.com/cover.jpg");
        assertThat(result.canonicalImageUrl()).isEqualTo("https://example.com/cover.jpg");
    }

    @Test
    void bookImageLinkPersistenceService_shouldRethrowDataAccessException_When_CoverPersistenceFailsWithDatabaseError() {
        JdbcTemplate imageJdbcTemplate = mock(JdbcTemplate.class);
        CoverPersistenceService coverPersistenceService = mock(CoverPersistenceService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = new BookImageLinkPersistenceService(
            imageJdbcTemplate,
            coverPersistenceService
        );
        stubAbsentExistingCoverQuality(imageJdbcTemplate);
        UUID bookId = UUID.randomUUID();
        BookAggregate.ExternalIdentifiers identifiers = BookAggregate.ExternalIdentifiers.builder()
            .source("GOOGLE_BOOKS")
            .imageLinks(Map.of("thumbnail", "https://example.com/cover.jpg"))
            .build();

        when(coverPersistenceService.persistFromGoogleImageLinks(eq(bookId), anyMap(), eq("GOOGLE_BOOKS")))
            .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> imageLinkPersistenceService.persistImageLinks(bookId, identifiers))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("database unavailable");
        verify(coverPersistenceService, never()).updateAfterS3Upload(any(), any());
    }

    @Test
    void bookUpsertService_findExistingBookId_acquiresAdvisoryLockWithQueryExecution() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = mock(BookUpsertTransactionService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = mock(BookImageLinkPersistenceService.class);
        BookOutboxEventService outboxEventService = mock(BookOutboxEventService.class);
        stubAdvisoryLock(lockJdbcTemplate);
        stubExternalIdLookup(lockJdbcTemplate, "OL-LOCK-1", null);

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
        stubAdvisoryLock(lockJdbcTemplate);
        stubExternalIdLookup(lockJdbcTemplate, "OL-SLUG-1", null);

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
        stubAdvisoryLock(lockJdbcTemplate);
        stubExternalIdLookup(lockJdbcTemplate, "OL-SLUG-MATCH-1", null);
        stubSlugLookup(lockJdbcTemplate, "the-partner-john-grisham", existingBookId);

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

    private void stubAdvisoryLock(JdbcTemplate lockJdbcTemplate) {
        when(lockJdbcTemplate.query(
            eq("SELECT pg_advisory_xact_lock(?)"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
            anyLong()
        )).thenReturn(null);
    }

    private void stubExternalIdLookup(JdbcTemplate lockJdbcTemplate, String externalId, UUID resultBookId) {
        when(lockJdbcTemplate.query(
            eq("SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("OPEN_LIBRARY"),
            eq(externalId)
        )).thenReturn(resultBookId);
    }

    private void stubSlugLookup(JdbcTemplate lockJdbcTemplate, String slug, UUID resultBookId) {
        when(lockJdbcTemplate.query(
            eq("SELECT id FROM books WHERE slug = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq(slug)
        )).thenReturn(resultBookId);
    }

    private void stubAbsentExistingCoverQuality(JdbcTemplate imageJdbcTemplate) {
        when(imageJdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any(),
            any()
        )).thenAnswer(invocation -> {
            ResultSetExtractor<Object> extractor = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(false);
            return extractor.extractData(resultSet);
        });
    }
}
