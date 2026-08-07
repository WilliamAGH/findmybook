package net.findmybook.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.annotation.Nullable;
import net.findmybook.dto.BookAggregate;
import net.findmybook.service.BookLookupService;
import net.findmybook.service.BookSupplementalPersistenceService;
import net.findmybook.service.BookUpsertService;
import net.findmybook.util.IsbnUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ExtendWith(MockitoExtension.class)
@Testcontainers
class NytBestsellerPersistenceCollaboratorTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10;
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "public.ecr.aws/docker/library/postgres:17-alpine"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    private static JdbcTemplate integrationJdbcTemplate;
    private static TransactionTemplate transactionTemplate;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BookSupplementalPersistenceService supplementalPersistenceService;

    @Mock
    private BookLookupService bookLookupService;

    @Mock
    private BookUpsertService bookUpsertService;

    private NytBestsellerPersistenceCollaborator collaborator;
    private NytBestsellerPayloadMapper payloadMapper;
    private ObjectMapper objectMapper;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        integrationJdbcTemplate = new JdbcTemplate(dataSource);
        integrationJdbcTemplate.execute(Files.readString(Path.of("migrations/10_books.sql")));
        integrationJdbcTemplate.execute(Files.readString(Path.of("migrations/11_book_external_ids.sql")));
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        integrationJdbcTemplate.update("DELETE FROM book_external_ids");
        integrationJdbcTemplate.update("DELETE FROM books");
        objectMapper = new ObjectMapper();
        payloadMapper = new NytBestsellerPayloadMapper(objectMapper);
        collaborator = new NytBestsellerPersistenceCollaborator(
            jdbcTemplate,
            supplementalPersistenceService,
            payloadMapper,
            bookLookupService,
            bookUpsertService
        );
        lenient().when(jdbcTemplate.update(
            anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);
    }

    @Test
    void should_UseBookUriIdentityAndSourceExternalIdConflictTarget_When_UpsertingNytExternalIdentifiers() {
        ObjectNode bookNode = objectMapper.createObjectNode();
        bookNode.put("book_uri", "https://www.nytimes.com/books/sample-book");
        String canonicalId = "8c7d129c-fcd0-42dc-8f04-43ecb4e303f4";

        collaborator.upsertNytExternalIdentifiers(
            canonicalId,
            bookNode,
            "https://www.nytimes.com/books/sample-book",
            "9781234567897",
            "1234567890"
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> externalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            sqlCaptor.capture(),
            any(),
            any(),
            externalIdCaptor.capture(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        );

        assertThat(sqlCaptor.getValue()).contains("ON CONFLICT (source, external_id)");
        assertThat(sqlCaptor.getValue()).doesNotContain("ON CONFLICT (book_id, source)");
        assertThat(externalIdCaptor.getValue()).isEqualTo("https://www.nytimes.com/books/sample-book");
    }

    @Test
    void should_IndexCanonicalVolumeLink_When_LoadingLegacyNytIdentity() {
        String indexDefinition = integrationJdbcTemplate.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_book_external_ids_nyt_canonical_volume_link'",
            String.class
        );

        assertThat(indexDefinition)
            .contains("canonical_volume_link")
            .contains("source = 'NEW_YORK_TIMES'::text");
    }

    @Test
    void should_SkipExternalIdentifierPersistence_When_NytIdentityIsMissing() {
        ObjectNode bookNode = objectMapper.createObjectNode();
        String canonicalId = "10e0a7b5-0d0c-4f67-84ea-6f7ef8bcaf57";

        collaborator.upsertNytExternalIdentifiers(canonicalId, bookNode, null, null, null);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void should_PreserveAuthorLabelsAndExcludeContributorProse_When_BuildingNytAggregate() {
        ObjectNode bookNode = objectMapper.createObjectNode();
        bookNode.put("book_title", "Raw Author Fixture");
        bookNode.put("book_uri", "nyt://book/raw-author-fixture");
        bookNode.put("author", "JANE DOE and JOHN ROE");
        bookNode.put("contributor", "by JANE DOE and JOHN ROE");
        bookNode.put("contributor_note", "Illustrated by SAMPLE ARTIST");
        NytListContext listContext = nytListContext();

        BookAggregate aggregate = payloadMapper.buildBookAggregateFromNyt(
            bookNode,
            listContext,
            "nyt://book/raw-author-fixture",
            null,
            null
        );

        assertThat(aggregate).isNotNull();
        assertThat(aggregate.getAuthors()).containsExactly("JANE DOE", "JOHN ROE");
    }

    @Test
    void should_UseBookUriAsExternalId_When_NytAggregateHasNoIsbn() {
        ObjectNode bookNode = objectMapper.createObjectNode();
        bookNode.put("book_title", "URI Identity Fixture");
        bookNode.put("book_uri", "  nyt://book/uri-identity-fixture  ");
        NytListContext listContext = nytListContext();

        BookAggregate aggregate = payloadMapper.buildBookAggregateFromNyt(
            bookNode,
            listContext,
            "nyt://book/uri-identity-fixture",
            null,
            null
        );

        assertThat(aggregate).isNotNull();
        assertThat(aggregate.getIdentifiers().getExternalId()).isEqualTo("nyt://book/uri-identity-fixture");
        assertThat(aggregate.getIdentifiers().getCanonicalVolumeLink()).isEqualTo("nyt://book/uri-identity-fixture");
    }

    @Test
    void should_PreferBookUriThenIsbn13ThenIsbn10_When_BuildingNytIdentity() {
        ObjectNode bookNodeWithUri = objectMapper.createObjectNode();
        bookNodeWithUri.put("book_title", "URI Identity Fixture");
        bookNodeWithUri.put("book_uri", "nyt://book/stable-identity-fixture");
        ObjectNode bookNodeWithoutUri = objectMapper.createObjectNode();
        bookNodeWithoutUri.put("book_title", "ISBN Identity Fixture");

        BookAggregate aggregateWithUri = payloadMapper.buildBookAggregateFromNyt(
            bookNodeWithUri,
            nytListContext(),
            payloadMapper.resolveNytExternalId(bookNodeWithUri, "9780306406157", "0306406152"),
            "9780306406157",
            "0306406152"
        );
        BookAggregate aggregateWithBothIsbns = payloadMapper.buildBookAggregateFromNyt(
            bookNodeWithoutUri,
            nytListContext(),
            payloadMapper.resolveNytExternalId(bookNodeWithoutUri, "9780306406157", "0306406152"),
            "9780306406157",
            "0306406152"
        );
        BookAggregate aggregateWithIsbn10 = payloadMapper.buildBookAggregateFromNyt(
            bookNodeWithoutUri,
            nytListContext(),
            payloadMapper.resolveNytExternalId(bookNodeWithoutUri, "   ", "  0306406152  "),
            "   ",
            "  0306406152  "
        );

        assertThat(aggregateWithUri).isNotNull();
        assertThat(aggregateWithUri.getIdentifiers().getExternalId())
            .isEqualTo("nyt://book/stable-identity-fixture");
        assertThat(aggregateWithBothIsbns).isNotNull();
        assertThat(aggregateWithBothIsbns.getIdentifiers().getExternalId()).isEqualTo("9780306406157");
        assertThat(aggregateWithIsbn10).isNotNull();
        assertThat(aggregateWithIsbn10.getIdentifiers().getExternalId()).isEqualTo("0306406152");
    }

    @Test
    void should_RejectAggregate_WhenNytIdentityHasNoUriOrValidIsbn() {
        ObjectNode bookNode = objectMapper.createObjectNode();
        bookNode.put("book_title", "Unstable Identity Fixture");

        BookAggregate aggregate = payloadMapper.buildBookAggregateFromNyt(
            bookNode,
            nytListContext(),
            "",
            "not-an-isbn13",
            "not-an-isbn10"
        );

        assertThat(aggregate).isNull();
    }

    @Test
    void should_EstablishStableBookUriDeterministically_When_LegacyRowsShareCanonicalBook() {
        String bookUri = "nyt://book/stable-upgrade";
        UUID bookId = seedStoredNytIdentity("nyt-z-row", "9780306406157", "9780306406157");
        seedStoredNytIdentity(bookId, "nyt-a-row", "9780316769488", "9780316769488");
        integrationJdbcTemplate.update(
            "UPDATE book_external_ids SET canonical_volume_link = ? WHERE book_id = ?",
            bookUri,
            bookId
        );
        ObjectNode bookNode = nytBookNode("Stable URI Upgrade", bookUri);
        NytBestsellerPersistenceCollaborator integrationCollaborator = integrationCollaborator(mock(BookUpsertService.class));

        String resolvedBookId = transactionTemplate.execute(status -> integrationCollaborator.resolveOrCreateCanonicalBook(
            bookNode, nytListContext(), "9780306406157", "0306406152"
        ));

        assertThat(resolvedBookId).isEqualTo(bookId.toString());
        assertThat(loadStoredIdentity("nyt-a-row")).isEqualTo(
            new StoredIdentity("nyt-a-row", bookId, bookUri, "9780316769488", "0316769487")
        );
        assertThat(loadStoredIdentity("nyt-z-row")).isEqualTo(
            new StoredIdentity("nyt-z-row", bookId, "9780306406157", "9780306406157", "0306406152")
        );
    }

    @Test
    void should_PreserveStableBookUri_When_LaterSnapshotOnlyHasIsbn() {
        UUID bookId = seedStoredNytIdentity("nyt-row", "nyt://book/stable-preserved", "9780316769488");
        ObjectNode bookNode = nytBookNode("Stable URI Preserved", null);
        NytBestsellerPersistenceCollaborator integrationCollaborator = integrationCollaborator(mock(BookUpsertService.class));

        String resolvedBookId = transactionTemplate.execute(status -> integrationCollaborator.resolveOrCreateCanonicalBook(
            bookNode, nytListContext(), "9780316769488", "0316769487"
        ));

        assertThat(resolvedBookId).isEqualTo(bookId.toString());
        assertThat(loadStoredIdentity("nyt-row")).isEqualTo(
            new StoredIdentity("nyt-row", bookId, "nyt://book/stable-preserved", "9780316769488", "0316769487")
        );
    }

    @Test
    void should_RejectAmbiguousBookUri_When_LegacyRowsPointToDifferentBooks() {
        String bookUri = "nyt://book/ambiguous-legacy-rows";
        UUID firstBookId = seedStoredNytIdentity("nyt-first-row", "9780306406157", "9780306406157");
        UUID secondBookId = seedStoredNytIdentity("nyt-second-row", "9780316769488", "9780316769488");
        integrationJdbcTemplate.update(
            "UPDATE book_external_ids SET canonical_volume_link = ? WHERE book_id IN (?, ?)",
            bookUri,
            firstBookId,
            secondBookId
        );
        ObjectNode bookNode = nytBookNode("Ambiguous Legacy Rows", bookUri);
        NytBestsellerPersistenceCollaborator integrationCollaborator = integrationCollaborator(mock(BookUpsertService.class));

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
            integrationCollaborator.resolveOrCreateCanonicalBook(
                bookNode, nytListContext(), "9780306406157", "0306406152"
            )
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("resolves to multiple canonical books");
    }

    @Test
    void should_ConvergeOnOneCanonicalBook_When_ConcurrentSnapshotsShareUnseenBookUri() throws Exception {
        AtomicInteger canonicalUpserts = new AtomicInteger();
        BookUpsertService integrationBookUpsertService = mock(BookUpsertService.class);
        when(integrationBookUpsertService.upsert(any(BookAggregate.class))).thenAnswer(invocation -> {
            BookAggregate aggregate = invocation.getArgument(0, BookAggregate.class);
            UUID bookId = UUID.randomUUID();
            canonicalUpserts.incrementAndGet();
            integrationJdbcTemplate.update(
                "INSERT INTO books (id, title, isbn13, isbn10) VALUES (?, ?, ?, ?)",
                bookId, aggregate.getTitle(), aggregate.getIsbn13(), aggregate.getIsbn10()
            );
            integrationJdbcTemplate.update(
                """
                INSERT INTO book_external_ids (
                    id, book_id, source, external_id, provider_isbn13, provider_isbn10, canonical_volume_link
                ) VALUES (?, ?, 'NEW_YORK_TIMES', ?, ?, ?, ?)
                """,
                "seed-" + canonicalUpserts.get(),
                bookId,
                aggregate.getIdentifiers().getExternalId(),
                aggregate.getIdentifiers().getProviderIsbn13(),
                aggregate.getIdentifiers().getProviderIsbn10(),
                aggregate.getIdentifiers().getCanonicalVolumeLink()
            );
            return BookUpsertService.UpsertResult.builder()
                .bookId(bookId)
                .slug("concurrent-nyt-book")
                .isNew(true)
                .build();
        });
        NytBestsellerPersistenceCollaborator integrationCollaborator = integrationCollaborator(integrationBookUpsertService);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> first = submitConcurrentResolution(
            executor, integrationCollaborator, ready, start, "9780306406157", "0306406152"
        );
        Future<String> second = submitConcurrentResolution(
            executor, integrationCollaborator, ready, start, "9780316769488", "0316769487"
        );
        try {
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(
                first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ).stream().distinct())
                .hasSize(1);
        } finally {
            start.countDown();
            if (!first.isDone()) {
                first.cancel(true);
            }
            if (!second.isDone()) {
                second.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(canonicalUpserts).hasValue(1);
        assertThat(integrationJdbcTemplate.queryForObject("SELECT count(*) FROM books", Integer.class)).isOne();
        assertThat(integrationJdbcTemplate.queryForObject(
            "SELECT count(*) FROM book_external_ids WHERE source = 'NEW_YORK_TIMES'", Integer.class
        )).isOne();
    }

    private Future<String> submitConcurrentResolution(ExecutorService executor,
                                                      NytBestsellerPersistenceCollaborator integrationCollaborator,
                                                      CountDownLatch ready,
                                                      CountDownLatch start,
                                                      String isbn13,
                                                      String isbn10) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent NYT test did not receive its start signal");
            }
            ObjectNode bookNode = nytBookNode("Concurrent Stable URI", "nyt://book/concurrent-stable-uri");
            return transactionTemplate.execute(status -> integrationCollaborator.resolveOrCreateCanonicalBook(
                bookNode, nytListContext(), isbn13, isbn10
            ));
        });
    }

    private NytBestsellerPersistenceCollaborator integrationCollaborator(BookUpsertService integrationBookUpsertService) {
        return new NytBestsellerPersistenceCollaborator(
            integrationJdbcTemplate,
            mock(BookSupplementalPersistenceService.class),
            new NytBestsellerPayloadMapper(new ObjectMapper()),
            new BookLookupService(integrationJdbcTemplate),
            integrationBookUpsertService
        );
    }

    private UUID seedStoredNytIdentity(String rowId, String externalId, String isbn13) {
        UUID bookId = UUID.randomUUID();
        integrationJdbcTemplate.update(
            "INSERT INTO books (id, title, isbn13) VALUES (?, 'Stored NYT Identity', ?)",
            bookId,
            isbn13
        );
        seedStoredNytIdentity(bookId, rowId, externalId, isbn13);
        return bookId;
    }

    private void seedStoredNytIdentity(UUID bookId, String rowId, String externalId, String isbn13) {
        integrationJdbcTemplate.update(
            """
            INSERT INTO book_external_ids (
                id, book_id, source, external_id, provider_isbn13, provider_isbn10, canonical_volume_link
            ) VALUES (?, ?, 'NEW_YORK_TIMES', ?, ?, ?, ?)
            """,
            rowId,
            bookId,
            externalId,
            isbn13,
            isbn13 == null ? null : IsbnUtils.toIsbn10(isbn13),
            externalId.startsWith("nyt://") ? externalId : null
        );
    }

    private StoredIdentity loadStoredIdentity(String rowId) {
        return integrationJdbcTemplate.queryForObject(
            """
            SELECT id, book_id, external_id, provider_isbn13, provider_isbn10
            FROM book_external_ids
            WHERE source = 'NEW_YORK_TIMES' AND id = ?
            """,
            (resultSet, rowNumber) -> new StoredIdentity(
                resultSet.getString("id"),
                resultSet.getObject("book_id", UUID.class),
                resultSet.getString("external_id"),
                resultSet.getString("provider_isbn13"),
                resultSet.getString("provider_isbn10")
            ),
            rowId
        );
    }

    private ObjectNode nytBookNode(String title, @Nullable String bookUri) {
        ObjectNode bookNode = new ObjectMapper().createObjectNode();
        bookNode.put("title", title);
        if (bookUri != null) {
            bookNode.put("book_uri", bookUri);
        }
        return bookNode;
    }

    private record StoredIdentity(String rowId, UUID bookId, String externalId, String isbn13, String isbn10) {}

    private NytListContext nytListContext() {
        return new NytListContext(
            "collection-1",
            "hardcover-fiction",
            "Hardcover Fiction",
            "Hardcover Fiction",
            "list-1",
            "WEEKLY",
            LocalDate.of(2026, 7, 26),
            LocalDate.of(2026, 8, 2)
        );
    }
}
