package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.test.annotations.DbIntegrationTest;
import net.findmybook.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DbIntegrationTest
@Import(BookUpsertImageLinksEventTest.EventConfig.class)
class BookUpsertImageLinksEventTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BookUpsertService bookUpsertService;
    @Autowired private BookUpsertTransactionService bookUpsertTransactionService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CoverPersistenceService coverPersistenceService;
    @Autowired private EventCollector eventCollector;

    private UUID bookId;
    private String isbn13;

    @BeforeEach
    void setUp() {
        eventCollector.clear();
        bookId = UUID.randomUUID();
        isbn13 = "9780132350884";
        insertBook(bookId, isbn13, "Existing Book");
        insertHighQualityCover();
    }

    @Test
    void eventOmitsImageLinksWhenExistingCoverIsBetter() {
        BookAggregate aggregate = BookAggregate.builder()
            .title("Existing Book Updated")
            .slugBase("existing-book-updated")
            .isbn13(isbn13)
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("google-" + bookId)
                .imageLinks(Map.of("thumbnail", "https://example.com/thumb.jpg"))
                .build())
            .build();

        BookUpsertService.UpsertResult result = bookUpsertService.upsert(aggregate);

        assertThat(result.getBookId()).isEqualTo(bookId);

        List<BookUpsertEvent> events = eventCollector.eventsForBook(bookId.toString());

        assertThat(events).hasSize(1);
        BookUpsertEvent event = events.getFirst();

        assertThat(event.getImageLinks()).isEmpty();
        assertThat(event.getCanonicalImageUrl()).isNull();
    }

    @Test
    void should_IgnoreInvalidImageLinks_When_IncomingPayloadContainsPlaceholderAndUnsupportedTypes() {
        UUID invalidBookId = UUID.randomUUID();
        String invalidIsbn13 = "9780132350990";
        insertBook(invalidBookId, invalidIsbn13, "Invalid Link Fixture");

        BookAggregate aggregate = BookAggregate.builder()
            .title("Invalid Link Fixture")
            .slugBase("invalid-link-fixture")
            .isbn13(invalidIsbn13)
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("google-invalid-" + invalidBookId)
                .imageLinks(Map.of(
                    "thumbnail", "/images/placeholder-book-cover.svg",
                    "s3", "https://example.com/invalid-type.jpg",
                    "large", "http://localhost:8095/images/book-covers/local.jpg"
                ))
                .build())
            .build();

        BookUpsertService.UpsertResult result = bookUpsertService.upsert(aggregate);
        assertThat(result.getBookId()).isEqualTo(invalidBookId);

        Integer persistedRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM book_image_links WHERE book_id = ?",
            Integer.class,
            invalidBookId
        );
        assertThat(persistedRows).isZero();

        List<BookUpsertEvent> events = eventCollector.eventsForBook(invalidBookId.toString());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getImageLinks()).isEmpty();
        assertThat(events.getFirst().getCanonicalImageUrl()).isNull();
    }

    @Test
    void should_WriteAuthorJoinsInAuthorIdOrder_When_AuthorIdsReverseCanonicalNameOrder() {
        JdbcTemplate authorJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        List<String> authorLockOrder = new ArrayList<>();
        List<Object[]> joinWrites = new ArrayList<>();
        String aliceAuthorId = "author-z-alice-walker";
        String zoraAuthorId = "author-a-zora-neale-hurston";
        Map<String, String> authorIdsByName = Map.of(
            "Alice Walker", aliceAuthorId,
            "Zora Neale Hurston", zoraAuthorId
        );
        Mockito.when(authorJdbcTemplate.queryForObject(
                ArgumentMatchers.anyString(), ArgumentMatchers.<RowMapper<String>>any(),
                ArgumentMatchers.any(Object[].class)
            ))
            .thenAnswer(invocation -> {
                String authorName = invocation.getArgument(3, String.class);
                authorLockOrder.add(authorName);
                return authorIdsByName.get(authorName);
            });
        Mockito.when(authorJdbcTemplate.update(
                ArgumentMatchers.contains("INSERT INTO book_authors_join"),
                ArgumentMatchers.any(Object[].class)
            ))
            .thenAnswer(invocation -> {
                joinWrites.add(invocation.getArgument(1, Object[].class));
                return 1;
            });
        BookUpsertTransactionService transactionService = new BookUpsertTransactionService(
            authorJdbcTemplate, Mockito.mock(BookCollectionPersistenceService.class)
        );
        transactionService.upsertAuthors(UUID.randomUUID(), List.of("Zora Neale Hurston", "Alice Walker"));

        assertThat(authorLockOrder).containsExactly("Alice Walker", "Zora Neale Hurston");
        assertThat(joinWrites).hasSize(2);
        assertThat(joinWrites.get(0)[2]).isEqualTo(zoraAuthorId);
        assertThat(joinWrites.get(0)[3]).isEqualTo(0);
        assertThat(joinWrites.get(1)[2]).isEqualTo(aliceAuthorId);
        assertThat(joinWrites.get(1)[3]).isEqualTo(1);
    }

    @Test
    void should_CompleteConcurrentAuthorUpserts_When_SharedAuthorsArriveInReverseOrder() throws Exception {
        UUID firstBookId = UUID.randomUUID();
        UUID secondBookId = UUID.randomUUID();
        String authorSuffix = UUID.randomUUID().toString();
        String alphaAuthor = "Alpha Author " + authorSuffix;
        String betaAuthor = "Beta Author " + authorSuffix;
        CountDownLatch blockerLocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch firstWorkerStarted = new CountDownLatch(1);
        CountDownLatch secondWorkerStarted = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger();
        AtomicInteger firstWorkerPid = new AtomicInteger();
        AtomicInteger secondWorkerPid = new AtomicInteger();
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        try {
            insertBook(firstBookId, "9780000000001", "Concurrent Author Fixture One");
            insertBook(secondBookId, "9780000000002", "Concurrent Author Fixture Two");
            insertAuthor(alphaAuthor);
            insertAuthor(betaAuthor);
            futures.add(executorService.submit(() ->
                holdAuthorLock(alphaAuthor, blockerPid, blockerLocked, releaseBlocker)));
            awaitLatch(blockerLocked);
            Future<?> firstUpsert = executorService.submit(
                () -> runAuthorUpsertTransaction(firstBookId, List.of(alphaAuthor, betaAuthor),
                    firstWorkerPid, firstWorkerStarted));
            futures.add(firstUpsert);
            awaitLatch(firstWorkerStarted);
            awaitBlockedBy(firstWorkerPid.get(), blockerPid.get());
            Future<?> secondUpsert = executorService.submit(
                () -> runAuthorUpsertTransaction(secondBookId, List.of(betaAuthor, alphaAuthor),
                    secondWorkerPid, secondWorkerStarted));
            futures.add(secondUpsert);
            awaitLatch(secondWorkerStarted);
            awaitBlockedBy(secondWorkerPid.get(), firstWorkerPid.get());
            releaseBlocker.countDown();
            for (Future<?> future : futures) {
                future.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            assertThat(loadAuthorPositions(firstBookId))
                .containsExactly(Map.entry(alphaAuthor, 0), Map.entry(betaAuthor, 1));
            assertThat(loadAuthorPositions(secondBookId))
                .containsExactly(Map.entry(betaAuthor, 0), Map.entry(alphaAuthor, 1));
        } finally {
            releaseBlocker.countDown();
            futures.stream().filter(future -> !future.isDone()).forEach(future -> future.cancel(true));
            executorService.shutdownNow();
            try {
                assertThat(executorService.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } finally {
                deleteAuthorFixtures(firstBookId, secondBookId, alphaAuthor, betaAuthor);
            }
        }
    }

    @Test
    void should_PersistImageLinksWithoutS3UploadTimestamp_When_S3PathIsAbsent() {
        UUID imageLinksBookId = UUID.randomUUID();
        String imageLinksIsbn13 = "9780132350662";
        insertBook(imageLinksBookId, imageLinksIsbn13, "Google Image Link Fixture");

        CoverPersistenceService.PersistenceResult result = coverPersistenceService.persistFromGoogleImageLinks(
            imageLinksBookId,
            Map.of("thumbnail", "https://example.com/thumbnail.jpg"),
            "GOOGLE_BOOKS"
        );

        assertThat(result.success()).isTrue();

        List<ImageLinkAuditRow> persistedRows = jdbcTemplate.query(
            """
            SELECT image_type, s3_image_path, s3_uploaded_at
            FROM book_image_links
            WHERE book_id = ?
            ORDER BY image_type
            """,
            (rs, rowNum) -> new ImageLinkAuditRow(
                rs.getString("image_type"),
                rs.getString("s3_image_path"),
                rs.getObject("s3_uploaded_at", OffsetDateTime.class)
            ),
            imageLinksBookId
        );

        assertThat(persistedRows)
            .extracting(ImageLinkAuditRow::imageType)
            .containsExactlyInAnyOrder("canonical", "thumbnail");
        assertThat(persistedRows)
            .extracting(ImageLinkAuditRow::s3ImagePath)
            .containsOnlyNulls();
        assertThat(persistedRows)
            .extracting(ImageLinkAuditRow::s3UploadedAt)
            .containsOnlyNulls();
    }

    @Test
    void should_SetAuditTimestamps_When_S3UploadMetadataIsPersisted() {
        UUID s3BookId = UUID.randomUUID();
        String s3Isbn13 = "9780132350778";
        insertBook(s3BookId, s3Isbn13, "S3 Audit Fixture");

        String s3Key = "images/book-covers/" + s3BookId + ".jpg";
        String cdnUrl = "https://book-finder.sfo3.digitaloceanspaces.com/" + s3Key;

        CoverPersistenceService.PersistenceResult result = coverPersistenceService.updateAfterS3Upload(
            s3BookId,
            new CoverPersistenceService.S3UploadResult(
                s3Key,
                cdnUrl,
                640,
                960,
                CoverImageSource.GOOGLE_BOOKS
            )
        );

        assertThat(result.success()).isTrue();

        Map<String, Object> persisted = jdbcTemplate.queryForMap(
            """
            SELECT image_type, s3_image_path, s3_uploaded_at, created_at, updated_at
            FROM book_image_links
            WHERE book_id = ? AND image_type = 'canonical'
            """,
            s3BookId
        );

        assertThat(persisted.get("image_type")).isEqualTo("canonical");
        assertThat(persisted.get("s3_image_path")).isEqualTo(s3Key);
        assertThat(persisted.get("s3_uploaded_at")).isNotNull();
        assertThat(persisted.get("created_at")).isNotNull();
        assertThat(persisted.get("updated_at")).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CoverWriteOperation.class)
    void should_ThrowDataAccessException_When_CoverWriteFails(CoverWriteOperation operation) {
        JdbcTemplate failingJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        CoverPersistenceService failingService = new CoverPersistenceService(failingJdbcTemplate);
        UUID failingBookId = UUID.randomUUID();

        Mockito.doThrow(new DataAccessResourceFailureException("cover write failed"))
            .when(failingJdbcTemplate)
            .update(ArgumentMatchers.anyString(), ArgumentMatchers.<Object[]>any());

        assertThatThrownBy(() -> {
            switch (operation) {
                case GOOGLE_IMAGE_LINKS -> failingService.persistFromGoogleImageLinks(
                    failingBookId,
                    Map.of("thumbnail", "https://example.com/thumb.jpg"),
                    "GOOGLE_BOOKS"
                );
                case S3_UPLOAD -> failingService.updateAfterS3Upload(
                    failingBookId,
                    new CoverPersistenceService.S3UploadResult(
                        "images/book-covers/" + failingBookId + ".jpg",
                        "https://book-finder.sfo3.digitaloceanspaces.com/images/book-covers/" + failingBookId + ".jpg",
                        640,
                        960,
                        CoverImageSource.GOOGLE_BOOKS
                    )
                );
                case EXTERNAL_COVER -> failingService.persistExternalCover(
                    failingBookId,
                    "https://example.com/external-cover.jpg",
                    "OPEN_LIBRARY",
                    500,
                    800
                );
            }
        })
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("cover write failed");
    }

    private void insertBook(UUID targetBookId, String targetIsbn13, String title) {
        jdbcTemplate.update(
            "INSERT INTO books (id, title, slug, isbn13, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
            targetBookId,
            title,
            "existing-book-" + targetBookId,
            targetIsbn13
        );
    }

    private void insertHighQualityCover() {
        jdbcTemplate.update(
            """
            INSERT INTO book_image_links (
                id, book_id, image_type, url, s3_image_path, source, width, height, is_high_resolution, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            """,
            IdGenerator.generate(),
            bookId,
            "large",
            "https://example.com/large.jpg",
            "covers/" + bookId + "/large.jpg",
            "GOOGLE_BOOKS",
            800,
            1200,
            true
        );
    }

    private void insertAuthor(String authorName) {
        jdbcTemplate.update(
            "INSERT INTO authors (id, name, normalized_name, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
            IdGenerator.generate(), authorName, authorName.toLowerCase(java.util.Locale.ROOT)
        );
    }

    private void holdAuthorLock(String authorName, AtomicInteger backendPid,
                                CountDownLatch blockerLocked, CountDownLatch releaseBlocker) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'");
            jdbcTemplate.queryForObject("SELECT id FROM authors WHERE name = ? FOR UPDATE", String.class, authorName);
            backendPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            blockerLocked.countDown();
            awaitLatch(releaseBlocker);
        });
    }

    private void runAuthorUpsertTransaction(UUID targetBookId, List<String> authors,
                                            AtomicInteger backendPid,
                                            CountDownLatch workersStarted) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
            jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'");
            backendPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            workersStarted.countDown();
            bookUpsertTransactionService.upsertAuthors(targetBookId, authors);
        });
    }

    private void awaitBlockedBy(int workerPid, int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbcTemplate.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blockerPid, workerPid
            );
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("PostgreSQL worker " + workerPid + " did not block behind " + blockerPid);
    }

    private void deleteAuthorFixtures(UUID firstBookId, UUID secondBookId,
                                      String alphaAuthor, String betaAuthor) {
        jdbcTemplate.update("DELETE FROM book_authors_join WHERE book_id IN (?, ?)", firstBookId, secondBookId);
        jdbcTemplate.update("DELETE FROM authors WHERE name IN (?, ?)", alphaAuthor, betaAuthor);
        jdbcTemplate.update("DELETE FROM books WHERE id IN (?, ?)", firstBookId, secondBookId);
    }

    private List<Map.Entry<String, Integer>> loadAuthorPositions(UUID targetBookId) {
        return jdbcTemplate.query(
            """
            SELECT authors.name, book_authors_join.position
            FROM book_authors_join
            JOIN authors ON authors.id = book_authors_join.author_id
            WHERE book_authors_join.book_id = ?
            ORDER BY book_authors_join.position
            """,
            (resultSet, rowNumber) -> Map.entry(resultSet.getString("name"), resultSet.getInt("position")),
            targetBookId
        );
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

    private record ImageLinkAuditRow(String imageType, String s3ImagePath, OffsetDateTime s3UploadedAt) {}

    private enum CoverWriteOperation { GOOGLE_IMAGE_LINKS, S3_UPLOAD, EXTERNAL_COVER }

    @TestConfiguration
    static class EventConfig {
        @Bean
        EventCollector bookUpsertEventCollector() {
            return new EventCollector();
        }
    }

    static final class EventCollector implements ApplicationListener<PayloadApplicationEvent<BookUpsertEvent>> {
        private final List<BookUpsertEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(PayloadApplicationEvent<BookUpsertEvent> event) {
            BookUpsertEvent payload = event != null ? event.getPayload() : null;
            if (payload != null) {
                events.add(payload);
            }
        }

        List<BookUpsertEvent> eventsForBook(String targetBookId) {
            return events.stream()
                .filter(event -> targetBookId.equals(event.getBookId()))
                .toList();
        }

        void clear() {
            events.clear();
        }
    }
}
