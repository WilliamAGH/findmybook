package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.model.Book;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.util.JdbcUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void bookUpsertTransactionService_shouldDelegateNewSlugsToPostgres_When_TitlesMatch() {
        JdbcTemplate slugJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = new BookUpsertTransactionService(
            slugJdbcTemplate,
            mock(BookCollectionPersistenceService.class)
        );
        UUID firstBookId = UUID.fromString("01989d24-0000-7000-8000-000000000001");
        UUID secondBookId = UUID.fromString("01989d24-0000-7000-8000-000000000002");
        String firstPersistedSlug = "database-owned-" + firstBookId;
        String secondPersistedSlug = "database-owned-" + secondBookId;
        when(slugJdbcTemplate.query(
            eq("SELECT public.generate_slug(?, ?)"),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
            eq("Shared & Title"),
            eq(firstBookId)
        )).thenReturn(firstPersistedSlug);
        when(slugJdbcTemplate.query(
            eq("SELECT public.generate_slug(?, ?)"),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
            eq("Shared & Title"),
            eq(secondBookId)
        )).thenReturn(secondPersistedSlug);

        String firstSlug = transactionService.resolvePersistedSlug("Shared & Title", firstBookId, true);
        String secondSlug = transactionService.resolvePersistedSlug("Shared & Title", secondBookId, true);

        assertThat(firstSlug).isEqualTo(firstPersistedSlug);
        assertThat(secondSlug).isEqualTo(secondPersistedSlug);
        assertThat(firstSlug).isNotEqualTo(secondSlug);
        verify(slugJdbcTemplate).query(
            eq("SELECT public.generate_slug(?, ?)"),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
            eq("Shared & Title"),
            eq(firstBookId)
        );
        verify(slugJdbcTemplate).query(
            eq("SELECT public.generate_slug(?, ?)"),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
            eq("Shared & Title"),
            eq(secondBookId)
        );
    }

    @Test
    void bookUpsertService_findExistingBookId_shouldNotMatchTitleSlug_When_ProviderIdentityDoesNotResolve() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = mock(BookUpsertTransactionService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = mock(BookImageLinkPersistenceService.class);
        BookOutboxEventService outboxEventService = mock(BookOutboxEventService.class);
        stubAdvisoryLock(lockJdbcTemplate);
        stubExternalIdLookup(lockJdbcTemplate, "OL-SLUG-MATCH-1", null);

        BookUpsertService upsertService = new BookUpsertService(
            lockJdbcTemplate,
            transactionService,
            imageLinkPersistenceService,
            outboxEventService
        );

        BookAggregate aggregate = BookAggregate.builder()
            .title("Shared Title")
            .authors(List.of("First Author"))
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-SLUG-MATCH-1")
                .build())
            .build();

        Optional<UUID> existing = ReflectionTestUtils.invokeMethod(upsertService, "findExistingBookId", aggregate);

        assertThat(existing).isEmpty();
        verify(lockJdbcTemplate, never()).query(
            eq("SELECT id FROM books WHERE slug = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("shared-title")
        );
    }

    @Test
    void bookUpsertService_findExistingBookId_shouldPreferStableProviderIdentity_When_IsbnPointsElsewhere() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        UUID providerBookId = UUID.randomUUID();
        stubAdvisoryLock(lockJdbcTemplate);
        stubExternalIdLookup(lockJdbcTemplate, "OL-STABLE-1", providerBookId);
        BookUpsertService upsertService = new BookUpsertService(
            lockJdbcTemplate,
            mock(BookUpsertTransactionService.class),
            mock(BookImageLinkPersistenceService.class),
            mock(BookOutboxEventService.class)
        );
        BookAggregate aggregate = BookAggregate.builder()
            .title("Provider Identity")
            .isbn13("9780132350884")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-STABLE-1")
                .build())
            .build();

        Optional<UUID> existing = ReflectionTestUtils.invokeMethod(upsertService, "findExistingBookId", aggregate);

        assertThat(existing).contains(providerBookId);
        verify(lockJdbcTemplate, never()).query(
            eq("SELECT id FROM books WHERE isbn13 = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            anyString()
        );
    }

    @Test
    void bookUpsertTransactionService_shouldRefuseExternalIdentifierReassignment_When_ProviderRowOwnsAnotherBook() {
        JdbcTemplate persistenceJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = new BookUpsertTransactionService(
            persistenceJdbcTemplate,
            mock(BookCollectionPersistenceService.class)
        );
        BookAggregate.ExternalIdentifiers identifiers = BookAggregate.ExternalIdentifiers.builder()
            .source("GOOGLE_BOOKS")
            .externalId("provider-stable-1")
            .build();

        assertThatThrownBy(() -> transactionService.upsertExternalIds(UUID.randomUUID(), identifiers))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refusing reassignment");
    }

    @Test
    void bookUpsertTransactionService_shouldPreserveExistingSlug_When_UpdatingBook() {
        JdbcTemplate slugJdbcTemplate = mock(JdbcTemplate.class);
        UUID bookId = UUID.fromString("01989d24-0000-7000-8000-000000000003");
        when(slugJdbcTemplate.query(
            eq("SELECT slug FROM books WHERE id = ?"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(),
            eq(bookId)
        )).thenReturn("legacy-stable-slug");
        BookUpsertTransactionService transactionService = new BookUpsertTransactionService(
            slugJdbcTemplate,
            mock(BookCollectionPersistenceService.class)
        );

        String slug = transactionService.resolvePersistedSlug("Renamed Title", bookId, false);

        assertThat(slug).isEqualTo("legacy-stable-slug");
    }

    @Test
    void bookUpsertService_findExistingBookId_matchesEquivalentIsbn13_When_AggregateOnlyHasIsbn10() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertTransactionService transactionService = mock(BookUpsertTransactionService.class);
        BookImageLinkPersistenceService imageLinkPersistenceService = mock(BookImageLinkPersistenceService.class);
        BookOutboxEventService outboxEventService = mock(BookOutboxEventService.class);
        UUID existingBookId = UUID.randomUUID();
        stubAdvisoryLock(lockJdbcTemplate);
        when(lockJdbcTemplate.query(
            eq("SELECT id FROM books WHERE isbn10 = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("0306406152")
        )).thenReturn(null);
        when(lockJdbcTemplate.query(
            eq("SELECT id FROM books WHERE isbn13 = ? LIMIT 1"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<UUID>>any(),
            eq("9780306406157")
        )).thenReturn(existingBookId);

        BookUpsertService upsertService = new BookUpsertService(
            lockJdbcTemplate,
            transactionService,
            imageLinkPersistenceService,
            outboxEventService
        );

        BookAggregate aggregate = BookAggregate.builder()
            .title("Equivalent ISBN")
            .isbn10("0-306-40615-2")
            .build();

        Optional<UUID> existing = ReflectionTestUtils.invokeMethod(upsertService, "findExistingBookId", aggregate);

        assertThat(existing).contains(existingBookId);
    }

    @Test
    void bookUpsertService_computeBookLockKeys_usesSameIdentityForEquivalentIsbnFormats() {
        JdbcTemplate lockJdbcTemplate = mock(JdbcTemplate.class);
        BookUpsertService upsertService = new BookUpsertService(
            lockJdbcTemplate,
            mock(BookUpsertTransactionService.class),
            mock(BookImageLinkPersistenceService.class),
            mock(BookOutboxEventService.class)
        );
        BookAggregate isbn10Aggregate = BookAggregate.builder()
            .title("ISBN10")
            .isbn10("0-306-40615-2")
            .build();
        BookAggregate isbn13Aggregate = BookAggregate.builder()
            .title("ISBN13")
            .isbn13("978-0-306-40615-7")
            .build();

        List<Long> isbn10Locks = ReflectionTestUtils.invokeMethod(upsertService, "computeBookLockKeys", isbn10Aggregate);
        List<Long> isbn13Locks = ReflectionTestUtils.invokeMethod(upsertService, "computeBookLockKeys", isbn13Aggregate);

        assertThat(isbn10Locks).isEqualTo(isbn13Locks);
    }

    @Test
    void bookUpsertService_computeBookLockKeys_sharesProviderLock_When_IsbnsDisagree() {
        BookUpsertService upsertService = new BookUpsertService(
            mock(JdbcTemplate.class),
            mock(BookUpsertTransactionService.class),
            mock(BookImageLinkPersistenceService.class),
            mock(BookOutboxEventService.class)
        );
        BookAggregate firstObservation = BookAggregate.builder()
            .title("First observation")
            .isbn13("978-0-306-40615-7")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-SHARED")
                .build())
            .build();
        BookAggregate secondObservation = BookAggregate.builder()
            .title("Second observation")
            .isbn13("978-0-545-01022-1")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-SHARED")
                .build())
            .build();

        List<Long> firstLocks = ReflectionTestUtils.invokeMethod(
            upsertService,
            "computeBookLockKeys",
            firstObservation
        );
        List<Long> secondLocks = ReflectionTestUtils.invokeMethod(
            upsertService,
            "computeBookLockKeys",
            secondObservation
        );

        assertThat(firstLocks).containsAnyElementsOf(secondLocks);
        assertThat(firstLocks).isSorted();
        assertThat(secondLocks).isSorted();
    }

    @Test
    void jdbcUtils_optionalString_shouldPropagateDataAccessFailures_When_DatabaseIsUnavailable() {
        JdbcTemplate lookupJdbcTemplate = mock(JdbcTemplate.class);
        when(lookupJdbcTemplate.queryForObject(
            eq("SELECT id FROM books WHERE id = ?"),
            eq(String.class),
            eq("book-1")
        )).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> JdbcUtils.optionalString(
            lookupJdbcTemplate,
            "SELECT id FROM books WHERE id = ?",
            "book-1"
        ))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("database unavailable");
    }

    @Test
    void bookLookupService_findBookById_shouldReturnEmptyWithoutQuery_When_IdentifierIsNotUuid() {
        JdbcTemplate lookupJdbcTemplate = mock(JdbcTemplate.class);
        BookLookupService lookupService = new BookLookupService(lookupJdbcTemplate);

        Optional<String> bookId = lookupService.findBookById("heavens-");

        assertThat(bookId).isEmpty();
        verifyNoInteractions(lookupJdbcTemplate);
    }

    @Test
    void bookIdentifierResolver_resolveCanonicalId_shouldNotQueryCanonicalId_When_UnknownSlugFragmentIsUnmatched() {
        BookLookupService lookupService = mock(BookLookupService.class);
        BookQueryRepository bookQueryRepository = mock(BookQueryRepository.class);
        JdbcTemplate lookupJdbcTemplate = mock(JdbcTemplate.class);
        BookIdentifierResolver resolver = new BookIdentifierResolver(
            lookupService,
            bookQueryRepository,
            lookupJdbcTemplate
        );
        when(bookQueryRepository.fetchBookDetailBySlug("heavens-")).thenReturn(Optional.empty());
        when(lookupService.findBookIdByExternalIdentifier("heavens-")).thenReturn(Optional.empty());
        when(lookupService.findBookIdByIsbn("heavens-")).thenReturn(Optional.empty());

        Optional<String> resolved = resolver.resolveCanonicalId("heavens-");

        assertThat(resolved).isEmpty();
        verify(lookupService, never()).findBookById("heavens-");
        verifyNoInteractions(lookupJdbcTemplate);
    }

    @Test
    void postgresBookRepository_fetchByCanonicalId_shouldHydrateSectionsAfterBaseQueryReturns() throws Exception {
        JdbcTemplate repositoryJdbcTemplate = mock(JdbcTemplate.class);
        UUID bookId = UUID.randomUUID();
        AtomicBoolean baseQueryActive = new AtomicBoolean(false);
        when(repositoryJdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<PreparedStatementSetter>any(),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<?>>any()
        )).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            ResultSetExtractor<?> extractor = invocation.getArgument(2);
            if (sql.contains("SELECT id::text, slug, title")) {
                baseQueryActive.set(true);
                try {
                    return extractor.extractData(baseBookResultSet(bookId));
                } finally {
                    baseQueryActive.set(false);
                }
            }
            assertThat(baseQueryActive).isFalse();
            ResultSet emptyResultSet = mock(ResultSet.class);
            when(emptyResultSet.next()).thenReturn(false);
            return extractor.extractData(emptyResultSet);
        });
        when(repositoryJdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<PreparedStatementSetter>any(),
            org.mockito.ArgumentMatchers.<RowMapper<?>>any()
        )).thenAnswer(invocation -> {
            assertThat(baseQueryActive).isFalse();
            return List.of();
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(baseQueryActive).isFalse();
            return null;
        }).when(repositoryJdbcTemplate).query(
            anyString(),
            org.mockito.ArgumentMatchers.<PreparedStatementSetter>any(),
            org.mockito.ArgumentMatchers.<RowCallbackHandler>any()
        );
        PostgresBookRepository repository = new PostgresBookRepository(
            repositoryJdbcTemplate,
            new ObjectMapper(),
            new BookLookupService(repositoryJdbcTemplate)
        );

        Optional<Book> result = repository.fetchByCanonicalId(bookId.toString());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(bookId.toString());
        assertThat(result.get().getInPostgres()).isTrue();
    }

    @Test
    void bookLookupService_shouldResolveEquivalentIsbn10_When_LookingUpIsbn13() {
        JdbcTemplate lookupJdbcTemplate = mock(JdbcTemplate.class);
        when(lookupJdbcTemplate.queryForObject(
            eq("SELECT id::text FROM books WHERE isbn13 = ? LIMIT 1"),
            eq(String.class),
            eq("9780306406157")
        )).thenThrow(new EmptyResultDataAccessException(1));
        when(lookupJdbcTemplate.queryForObject(
            eq("SELECT book_id FROM book_external_ids WHERE provider_isbn13 = ? LIMIT 1"),
            eq(String.class),
            eq("9780306406157")
        )).thenThrow(new EmptyResultDataAccessException(1));
        when(lookupJdbcTemplate.queryForObject(
            eq("SELECT id::text FROM books WHERE isbn10 = ? LIMIT 1"),
            eq(String.class),
            eq("0306406152")
        )).thenReturn("book-1");
        BookLookupService lookupService = new BookLookupService(lookupJdbcTemplate);

        Optional<String> bookId = lookupService.findBookIdByIsbn("978-0-306-40615-7");

        assertThat(bookId).contains("book-1");
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

    private ResultSet baseBookResultSet(UUID bookId) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn(bookId.toString());
        when(resultSet.getString("slug")).thenReturn("stable-book");
        when(resultSet.getString("title")).thenReturn("Stable Book");
        when(resultSet.getString("description")).thenReturn("A book that can hydrate without nested pool borrowing.");
        when(resultSet.getString("isbn10")).thenReturn(null);
        when(resultSet.getString("isbn13")).thenReturn(null);
        when(resultSet.getDate("published_date")).thenReturn(null);
        when(resultSet.getString("language")).thenReturn("en");
        when(resultSet.getString("publisher")).thenReturn("findmybook");
        when(resultSet.getObject("page_count")).thenReturn(null);
        return resultSet;
    }
}
