package net.findmybook.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves the canonical PostgreSQL author-write contract with real, independent transactions.
 */
@Testcontainers
class BookAuthorUpsertIntegrationTest {
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10;
    private static final List<String> AUTHOR_BASE_MIGRATIONS = List.of(
        "10_books.sql", "12_authors.sql", "13_author_external_ids.sql",
        "14_book_authors_join.sql", "32_author_normalization_functions.sql");
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "public.ecr.aws/docker/library/postgres:17-alpine").asCompatibleSubstituteFor("postgres");
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;
    private static BookUpsertTransactionService bookUpsertTransactionService;
    private static UpgradeProof upgradeProof;
    private static String legacyTabNameAfterExpand;
    private static String legacyTabNameAfterContract;
    @BeforeAll
    static void initializePostgres() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        bookUpsertTransactionService = new BookUpsertTransactionService(jdbcTemplate,
            new BookCollectionPersistenceService(jdbcTemplate));
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        for (String migration : AUTHOR_BASE_MIGRATIONS) applyMigration(migration);
        jdbcTemplate.update("""
            INSERT INTO authors (id, name, normalized_name, created_at, updated_at)
            VALUES ('legacy-tab', E'\\tTAB AUTHOR', 'stale tab key', now(), now())
            """);
        applyMigration("52_canonical_author_upsert.sql");
        legacyTabNameAfterExpand = jdbcTemplate.queryForObject(
            "SELECT name FROM authors WHERE id = 'legacy-tab'", String.class);
        seedDirtyUpgradeFixture();
        applyMigration("53_contract_canonical_author_identity.sql");
        legacyTabNameAfterContract = jdbcTemplate.queryForObject(
            "SELECT name FROM authors WHERE id = 'legacy-tab'", String.class);
        upgradeProof = loadUpgradeProof();
    }
    @BeforeEach
    void clearAuthorFixtures() {
        jdbcTemplate.update("DELETE FROM book_authors_join");
        jdbcTemplate.update("DELETE FROM author_external_ids");
        jdbcTemplate.update("DELETE FROM authors");
        jdbcTemplate.update("DELETE FROM books");
    }
    @Test
    void should_CanonicalizeDeduplicateAndPreserveValidPositions_When_RawAuthorsContainNoise() {
        UUID bookId = insertBook("Canonical Author Fixture");
        bookUpsertTransactionService.upsertAuthors(bookId, Arrays.asList(
                "\"ALICE WALKER\"", "ÉLODIE DURAND", "AMÉLIE", "Ame\u0301lie", " ", null,
                "— ALPHA AUTHOR", "• BETA AUTHOR", "🧪 GAMMA AUTHOR", "A", "Aлексей", "王小明",
                "Alice\u061CSmith", "\u200EAlice", "Alice\u200FSmith", "Alice\u202ESmith",
                "\u2066Leading Bidi", "Control\u0001Name", "alice walker"
            ));
        assertThat(loadAuthorRows(bookId)).containsExactly(
            new AuthorRow("Alice Walker", "alice walker", 0),
            new AuthorRow("Élodie Durand", "élodie durand", 1),
            new AuthorRow("Amélie", "amélie", 2),
            new AuthorRow("Alpha Author", "alpha author", 3),
            new AuthorRow("Beta Author", "beta author", 4),
            new AuthorRow("Gamma Author", "gamma author", 5),
            new AuthorRow("A", "a", 6),
            new AuthorRow("Aлексей", "aлексей", 7),
            new AuthorRow("王小明", "王小明", 8)
        );
        assertThat(loadRelationIdLengths(bookId)).allMatch(lengths -> lengths.equals(Map.entry(10, 12)));
    }
    @Test
    void should_SelectStableDisplayName_When_EquivalentNamesArriveInReversedOrder() {
        String punctuatedAuthor = "J.K. Rowling";
        String spacedAuthor = "J K Rowling";
        UUID forwardBookId = insertBook("Forward Display Name Fixture");
        bookUpsertTransactionService.upsertAuthors(forwardBookId, List.of(punctuatedAuthor));
        bookUpsertTransactionService.upsertAuthors(forwardBookId, List.of(spacedAuthor));
        String forwardWinner = loadAuthorRows(forwardBookId).getFirst().name();
        clearAuthorFixtures();
        UUID reverseBookId = insertBook("Reverse Display Name Fixture");
        bookUpsertTransactionService.upsertAuthors(reverseBookId, List.of(spacedAuthor));
        bookUpsertTransactionService.upsertAuthors(reverseBookId, List.of(punctuatedAuthor));
        assertThat(loadAuthorRows(reverseBookId).getFirst().name())
            .isEqualTo(forwardWinner)
            .isEqualTo(spacedAuthor);
    }
    @Test
    void should_RejectResourceExhaustingInput_When_AuthorBoundaryIsExceeded() {
        UUID bookId = insertBook("Bounded Author Fixture");
        assertPostgresInvalidParameter(() -> bookUpsertTransactionService.upsertAuthors(bookId,
            Collections.nCopies(65, "Author")), "author count must not exceed 64");
        assertPostgresInvalidParameter(() -> bookUpsertTransactionService.upsertAuthors(bookId,
            List.of("A".repeat(513))), "must not exceed 512 characters");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM authors", Integer.class)).isZero();
    }
    @Test
    void should_MergeCanonicalUpgradeRows_When_PreexistingAuthorsShareIdentity() {
        assertThat(upgradeProof).isEqualTo(new UpgradeProof(
            "Jane Doe", "jane doe", 1, 2, List.of(0, 1), "Biography", "US"));
        assertThat(legacyTabNameAfterExpand).isEqualTo("\tTAB AUTHOR");
        assertThat(legacyTabNameAfterContract).isEqualTo("Tab Author");
    }
    @Test
    void should_ResolveExistingCanonicalKey_When_PreContractNormalizedKeyIsStale() throws Exception {
        jdbcTemplate.execute("DROP INDEX public.uq_authors_normalized_name");
        UUID bookId = insertBook("Legacy Normalized Key Fixture");
        try {
            jdbcTemplate.update("""
                INSERT INTO authors (id, name, normalized_name, created_at, updated_at)
                VALUES ('legacy-key', 'LEGACY KEY AUTHOR', 'stale key', now(), now())
                """);
            bookUpsertTransactionService.upsertAuthors(bookId, List.of("Legacy Key Author"));
            assertThat(loadAuthorRows(bookId)).containsExactly(
                new AuthorRow("Legacy Key Author", "legacy key author", 0));
            assertThat(jdbcTemplate.queryForObject(
                "SELECT author_id FROM book_authors_join WHERE book_id = ?", String.class, bookId))
                .isEqualTo("legacy-key");
            List<String> timestampsBeforeMigration = jdbcTemplate.queryForList("""
                SELECT updated_at::text FROM authors WHERE id = 'legacy-key'
                UNION ALL SELECT updated_at::text FROM book_authors_join WHERE book_id = ? ORDER BY 1
                """, String.class, bookId);
            applyMigration("53_contract_canonical_author_identity.sql");
            assertThat(jdbcTemplate.queryForList("""
                SELECT updated_at::text FROM authors WHERE id = 'legacy-key'
                UNION ALL SELECT updated_at::text FROM book_authors_join WHERE book_id = ? ORDER BY 1
                """, String.class, bookId)).containsExactlyElementsOf(timestampsBeforeMigration);
        } finally {
            applyMigration("53_contract_canonical_author_identity.sql");
        }
    }
    @Test
    void should_KeepBootstrapAndCutoverFailClosed_When_InspectingOrchestrationSources() throws IOException {
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        String makefile = Files.readString(Path.of("Makefile"));
        String slugExpansion = Files.readString(Path.of("migrations/33_slug_migration_helpers.sql"));
        String callerContract = Files.readString(Path.of("migrations/53_contract_canonical_author_identity.sql"));
        assertThat(schema).contains("findmybook_schema_bootstrap_state",
            "fresh-bootstrap retry found author data and will not auto-contract it", "canonical_author_contract_is_applied()",
            "Existing database detected; destructive historical migration 14 will not replay.");
        assertThat(schema).containsSubsequence(
            "\\ir ../../../migrations/53_contract_canonical_author_identity.sql",
            "DROP TABLE public.findmybook_schema_bootstrap_state;");
        assertThat(makefile).contains("AUTHOR_WRITERS_DRAINED", "AUTHOR_WRITES_QUIESCED", "AUTHOR_CALLERS_DEPLOYED",
            "public.canonical_author_contract_is_applied()", "public.ensure_unique_slug(text)",
            "public.generate_slug(text,text)");
        assertThat(slugExpansion).doesNotContain(
            "drop function if exists public.ensure_unique_slug(text)",
            "drop function if exists public.generate_slug(text, text)"
        );
        assertThat(callerContract).contains(
            "DROP FUNCTION IF EXISTS public.ensure_unique_slug(text)",
            "DROP FUNCTION IF EXISTS public.generate_slug(text, text)"
        );
    }
    @Test
    void should_UseOnlyIndexedLookup_When_CanonicalAuthorContractIsApplied() throws IOException {
        String authorExpansion = Files.readString(Path.of("migrations/52_canonical_author_upsert.sql"));
        int candidateResolutionStart = authorExpansion.indexOf(
            "  FOR candidate IN\n    SELECT candidates.name");
        int authorInsertStart = authorExpansion.indexOf(
            "      INSERT INTO public.authors", candidateResolutionStart);
        assertThat(candidateResolutionStart).isNotNegative();
        assertThat(authorInsertStart).isGreaterThan(candidateResolutionStart);
        assertThat(authorExpansion.substring(candidateResolutionStart, authorInsertStart))
            .containsSubsequence(
                "IF canonical_contract_applied THEN",
                "WHERE existing_authors.normalized_name = candidate.normalized_name",
                "ELSE",
                "WHERE public.canonical_author_normalized_name("
            )
            .containsOnlyOnce("WHERE public.canonical_author_normalized_name(");
    }
    @ParameterizedTest
    @EnumSource(UpgradeAmbiguity.class)
    void should_RollBackAllAuthorState_When_ContractIdentityIsAmbiguous(UpgradeAmbiguity ambiguity)
        throws Exception {
        jdbcTemplate.execute("DROP INDEX IF EXISTS public.uq_authors_normalized_name");
        applyMigration("32_author_normalization_functions.sql");
        UUID bookId = insertBook("Ambiguous Upgrade Fixture");
        jdbcTemplate.update("""
            INSERT INTO authors (id, name, normalized_name, biography, created_at, updated_at)
            VALUES ('ambiguous-one', 'AMBIGUOUS AUTHOR', 'legacy one',
                CASE WHEN ? = 'PERSONAL_METADATA' THEN 'Biography B' END, now() - interval '1 day', now()),
              ('ambiguous-two', 'Ambiguous Author', 'legacy two',
                CASE WHEN ? = 'PERSONAL_METADATA' THEN 'Biography A' END, now(), now())
            """,
            ambiguity.name(), ambiguity.name());
        jdbcTemplate.update("""
            INSERT INTO book_authors_join (id, book_id, author_id, position, created_at, updated_at)
            VALUES ('ambjoin001', ?, 'ambiguous-one', 0, now(), now()),
                   ('ambjoin002', ?, 'ambiguous-two', 1, now(), now())
            """,
            bookId, bookId);
        if (ambiguity == UpgradeAmbiguity.SAME_SOURCE_EXTERNAL_ID) {
            jdbcTemplate.update("""
                INSERT INTO author_external_ids (id, author_id, source, external_id, created_at)
                VALUES ('ambextid1', 'ambiguous-one', 'GOOGLE_BOOKS', 'external-one', now()),
                       ('ambextid2', 'ambiguous-two', 'GOOGLE_BOOKS', 'external-two', now())
                """);
        }
        String stateQuery = """
            SELECT 'author:' || row_to_json(authors)::text AS state FROM authors
            UNION ALL SELECT 'join:' || row_to_json(book_authors_join)::text FROM book_authors_join
            UNION ALL SELECT 'external:' || row_to_json(author_external_ids)::text FROM author_external_ids
            ORDER BY state
            """;
        List<String> stateBeforeMigration = jdbcTemplate.queryForList(stateQuery, String.class);
        try {
            Throwable failure = catchThrowable(() -> applyMigration("53_contract_canonical_author_identity.sql"));
            assertThat(failure).isNotNull().rootCause().hasMessageContaining(ambiguity.expectedMessage);
            assertThat(jdbcTemplate.queryForList(stateQuery, String.class))
                .containsExactlyElementsOf(stateBeforeMigration);
        } finally {
            clearAuthorFixtures();
            applyMigration("53_contract_canonical_author_identity.sql");
        }
    }
    @Test
    void should_RejectSameNamedWrongIndex_When_ContractMigrationReruns() throws Exception {
        jdbcTemplate.execute("DROP INDEX public.uq_authors_normalized_name");
        jdbcTemplate.execute("CREATE UNIQUE INDEX uq_authors_normalized_name ON public.authors(name)");
        try {
            Throwable failure = catchThrowable(() -> applyMigration("53_contract_canonical_author_identity.sql"));
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .hasMessageContaining("unexpected definition");
        } finally {
            jdbcTemplate.execute("DROP INDEX public.uq_authors_normalized_name");
            applyMigration("53_contract_canonical_author_identity.sql");
        }
    }
    @ParameterizedTest
    @EnumSource(AuthorState.class)
    void should_CompleteBothTransactions_When_SharedAuthorsArriveInOppositeOrder(
        AuthorState authorState) throws Exception {
        UUID firstBookId = insertBook("Concurrent Author Fixture One");
        UUID secondBookId = insertBook("Concurrent Author Fixture Two");
        String authorSuffix = UUID.randomUUID().toString();
        String alphaAuthor = "Alpha Author " + authorSuffix;
        String betaAuthor = "Beta Author " + authorSuffix;
        if (authorState == AuthorState.EXISTING) {
            UUID seedBookId = insertBook("Existing Author Seed Fixture");
            bookUpsertTransactionService.upsertAuthors(seedBookId, List.of(alphaAuthor, betaAuthor));
        }
        CountDownLatch blockerLocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger();
        AtomicInteger firstWorkerPid = new AtomicInteger();
        AtomicInteger secondWorkerPid = new AtomicInteger();
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        try {
            futures.add(executorService.submit(() ->
                holdCanonicalAuthorLock(alphaAuthor, blockerPid, blockerLocked, releaseBlocker)));
            awaitLatch(blockerLocked);
            futures.add(executorService.submit(() -> runCoordinatedAuthorUpsertTransaction(
                firstBookId, List.of(alphaAuthor, betaAuthor), firstWorkerPid, workersReady, releaseWorkers)));
            futures.add(executorService.submit(() -> runCoordinatedAuthorUpsertTransaction(
                secondBookId, List.of(betaAuthor, alphaAuthor), secondWorkerPid, workersReady, releaseWorkers)));
            awaitLatch(workersReady);
            releaseWorkers.countDown();
            awaitBlockedBy(firstWorkerPid.get(), blockerPid.get());
            awaitBlockedBy(secondWorkerPid.get(), blockerPid.get());
            assertThat(List.of(blockerPid.get(), firstWorkerPid.get(), secondWorkerPid.get()))
                .allMatch(pid -> pid > 0)
                .doesNotHaveDuplicates();
            releaseBlocker.countDown();
            for (Future<?> future : futures) future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(loadAuthorRows(firstBookId).stream()
                .map(authorRow -> Map.entry(authorRow.name(), authorRow.position())).toList()).containsExactly(
                Map.entry(alphaAuthor, 0), Map.entry(betaAuthor, 1));
            assertThat(loadAuthorRows(secondBookId).stream()
                .map(authorRow -> Map.entry(authorRow.name(), authorRow.position())).toList()).containsExactly(
                Map.entry(betaAuthor, 0), Map.entry(alphaAuthor, 1));
            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM authors
                WHERE normalized_name IN (canonical_author_normalized_name(?), canonical_author_normalized_name(?))
                """, Integer.class, alphaAuthor, betaAuthor)).isEqualTo(2);
        } finally {
            releaseWorkers.countDown();
            releaseBlocker.countDown();
            futures.stream().filter(future -> !future.isDone()).forEach(future -> future.cancel(true));
            executorService.shutdownNow();
            assertThat(executorService.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }
    private static void assertPostgresInvalidParameter(Runnable operation, String expectedMessage) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isNotNull();
        assertThat(NestedExceptionUtils.getMostSpecificCause(failure)).isInstanceOfSatisfying(
            PSQLException.class, databaseException -> {
                assertThat(databaseException.getSQLState()).isEqualTo("22023");
                assertThat(databaseException.getMessage()).contains(expectedMessage);
            });
    }
    private static void applyMigration(String migration) throws Exception {
        Path source = Path.of("migrations", migration).toAbsolutePath();
        assertThat(source).exists();
        jdbcTemplate.execute(Files.readString(source));
    }
    private static void seedDirtyUpgradeFixture() {
        UUID firstBookId = UUID.randomUUID();
        UUID secondBookId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO books (id, title, created_at, updated_at) VALUES (?, ?, now(), now()), (?, ?, now(), now())",
            firstBookId, "Upgrade Fixture One", secondBookId, "Upgrade Fixture Two"
        );
        jdbcTemplate.update(
            """
            INSERT INTO authors (id, name, normalized_name, biography, nationality, created_at, updated_at)
            VALUES
                ('legacy-one', 'JANE DOE', 'jane doe', 'Biography', NULL, now() - interval '1 day', now()),
                ('legacy-two', 'Jane Doe', 'jane doe', NULL, 'US', now(), now())
            """
        );
        jdbcTemplate.update(
            """
            INSERT INTO book_authors_join (id, book_id, author_id, position, created_at, updated_at)
            VALUES ('legacyjoin01', ?, 'legacy-one', 2, now(), now()),
                ('legacyjoin02', ?, 'legacy-two', 0, now(), now()),
                ('legacyjoin03', ?, 'legacy-one', 1, now(), now())
            """,
            firstBookId, firstBookId, secondBookId
        );
        jdbcTemplate.update(
            """
            INSERT INTO author_external_ids (id, author_id, source, external_id, created_at)
            VALUES ('external01', 'legacy-one', 'GOOGLE_BOOKS', 'jane-google', now()),
                ('external02', 'legacy-two', 'OPEN_LIBRARY', 'jane-open', now())
            """
        );
    }
    private static UpgradeProof loadUpgradeProof() {
        return jdbcTemplate.queryForObject(
            """
            SELECT authors.name,
                   authors.normalized_name,
                   (SELECT count(*)::integer FROM authors WHERE normalized_name = 'jane doe') AS author_count,
                   (SELECT count(*)::integer FROM author_external_ids WHERE author_id = authors.id) AS external_count,
                   authors.biography,
                   authors.nationality
            FROM authors
            WHERE authors.normalized_name = 'jane doe'
            """,
            (resultSet, rowNumber) -> new UpgradeProof(
                resultSet.getString("name"), resultSet.getString("normalized_name"),
                resultSet.getInt("author_count"), resultSet.getInt("external_count"),
                jdbcTemplate.queryForList("SELECT position FROM book_authors_join ORDER BY position", Integer.class),
                resultSet.getString("biography"), resultSet.getString("nationality")
            )
        );
    }
    private UUID insertBook(String title) {
        UUID bookId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO books (id, title, created_at, updated_at) "
            + "VALUES (?, ?, now(), now())", bookId, title);
        return bookId;
    }
    private void holdCanonicalAuthorLock(String authorName, AtomicInteger backendPid,
                                         CountDownLatch blockerLocked, CountDownLatch releaseBlocker) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'");
            jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(" +
                    "hashtextextended('findmybook.author:' || canonical_author_normalized_name(?), 0))",
                resultSet -> { },
                authorName
            );
            backendPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            blockerLocked.countDown();
            awaitLatch(releaseBlocker);
        });
    }
    private void runCoordinatedAuthorUpsertTransaction(
        UUID bookId, List<String> authors, AtomicInteger backendPid,
        CountDownLatch workersReady, CountDownLatch releaseWorkers
    ) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
            jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'");
            backendPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            workersReady.countDown();
            awaitLatch(releaseWorkers);
            bookUpsertTransactionService.upsertAuthors(bookId, authors);
        });
    }
    private void awaitBlockedBy(int workerPid, int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbcTemplate.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blockerPid, workerPid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new IllegalStateException("PostgreSQL worker " + workerPid + " did not block behind " + blockerPid);
    }
    private List<AuthorRow> loadAuthorRows(UUID bookId) {
        return jdbcTemplate.query("""
            SELECT authors.name, authors.normalized_name, book_authors_join.position
            FROM book_authors_join
            JOIN authors ON authors.id = book_authors_join.author_id
            WHERE book_authors_join.book_id = ?
            ORDER BY book_authors_join.position
            """,
            (resultSet, rowNumber) -> new AuthorRow(resultSet.getString("name"),
                resultSet.getString("normalized_name"), resultSet.getInt("position")),
            bookId);
    }
    private List<Map.Entry<Integer, Integer>> loadRelationIdLengths(UUID bookId) {
        return jdbcTemplate.query("""
            SELECT char_length(authors.id), char_length(book_authors_join.id)
            FROM book_authors_join
            JOIN authors ON authors.id = book_authors_join.author_id
            WHERE book_authors_join.book_id = ?
            """,
            (resultSet, rowNumber) -> Map.entry(resultSet.getInt(1), resultSet.getInt(2)),
            bookId);
    }
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out coordinating concurrent author upserts");
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrent author upserts",
                interruptedException);
        }
    }
    private record AuthorRow(String name, String normalizedName, int position) {}
    private record UpgradeProof(String name, String normalizedName, int authorCount,
                                int externalCount, List<Integer> positions, String biography, String nationality) {}
    private enum AuthorState { EXISTING, MISSING }
    private enum UpgradeAmbiguity {
        PERSONAL_METADATA("multiple distinct non-null personal metadata values"),
        SAME_SOURCE_EXTERNAL_ID("conflicting external IDs for the same source");
        private final String expectedMessage;
        UpgradeAmbiguity(String expectedMessage) { this.expectedMessage = expectedMessage; }
    }
}
