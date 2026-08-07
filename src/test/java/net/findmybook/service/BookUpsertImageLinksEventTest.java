package net.findmybook.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.findmybook.dto.BookAggregate;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "openai.api.key=test",
    "APP_ADMIN_PASSWORD=test-password",
    "APP_USER_PASSWORD=test-password",
    "app.security.admin.password=test-password",
    "app.security.user.password=test-password"
})
@ActiveProfiles("test")
@Testcontainers
@Import(BookUpsertImageLinksEventTest.EventConfig.class)
class BookUpsertImageLinksEventTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
        "pgvector/pgvector:pg17"
    ).asCompatibleSubstituteFor("postgres");
    private static final Path REPOSITORY_ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final Path CANONICAL_SCHEMA = REPOSITORY_ROOT.resolve("src/main/resources/schema.sql");
    private static final Path MIGRATIONS_DIRECTORY = REPOSITORY_ROOT.resolve("migrations");
    private static final Path CANONICAL_AUTHOR_UPSERT_MIGRATION =
        MIGRATIONS_DIRECTORY.resolve("52_canonical_author_upsert.sql");
    private static final Path AUTHOR_CONTRACT_TRANSITION_MIGRATION =
        MIGRATIONS_DIRECTORY.resolve("53_contract_canonical_author_identity.sql");
    private static final Pattern DIRECT_AUTHOR_WRITE = Pattern.compile(
        "(?is)\\b(?:insert\\s+into|update(?:\\s+only)?|delete\\s+from(?:\\s+only)?|merge\\s+into|copy|"
            + "truncate(?:\\s+table)?)\\s+(?:public\\.)?(?:authors|book_authors_join)\\b");
    private static final Pattern CANONICAL_AUTHOR_PROCEDURE = Pattern.compile(
        "(?is)\\bcreate\\s+(?:or\\s+replace\\s+)?procedure\\s+public\\.upsert_book_authors\\s*\\(");
    private static final Pattern POST_CANONICAL_MIGRATION_FILE =
        Pattern.compile("^(?:5[2-9]|[6-9]\\d|\\d{3,})_.*\\.sql$");
    private static final Pattern EXECUTABLE_SOURCE_FILE =
        Pattern.compile(".*\\.(?:java|js|cjs|mjs|ts|tsx|kt|kts|py|sh|zsh|sql)$");
    private static final List<Path> RUNTIME_EXECUTABLE_SOURCE_ROOTS = List.of(
        "src/main", "frontend/src", "frontend/scripts", "scripts"
    ).stream().map(REPOSITORY_ROOT::resolve).toList();

    @Container
    private static final PostgreSQLContainer POSTGRES = postgresContainer();

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BookUpsertService bookUpsertService;
    @Autowired private CoverPersistenceService coverPersistenceService;
    @Autowired private EventCollector eventCollector;

    private UUID bookId;
    private String isbn13;

    @DynamicPropertySource
    static void configureIsolatedDataSource(DynamicPropertyRegistry properties) {
        POSTGRES.start();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setUp() {
        eventCollector.clear();
        bookId = UUID.randomUUID();
        isbn13 = "9780132350884";
        jdbcTemplate.update("DELETE FROM books WHERE isbn13 = ?", isbn13);
        insertBook(bookId, isbn13, "Existing Book");
        insertHighQualityCover();
    }

    @Test
    void eventOmitsImageLinksWhenExistingCoverIsBetter() {
        BookAggregate aggregate = BookAggregate.builder()
            .title("Existing Book Updated")
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
    void should_CreateDistinctBooksAndSlugs_When_SameTitleHasDistinctProviderIdentities() {
        String fixtureToken = UUID.randomUUID().toString();
        String firstAuthor = "First Slug Author " + fixtureToken;
        String secondAuthor = "Second Slug Author " + fixtureToken;
        BookAggregate firstAggregate = sameTitleAggregate(
            "OPEN-LIBRARY-SLUG-A-" + fixtureToken,
            firstAuthor
        );
        BookAggregate secondAggregate = sameTitleAggregate(
            "OPEN-LIBRARY-SLUG-B-" + fixtureToken,
            secondAuthor
        );
        List<UUID> createdBookIds = new ArrayList<>();

        try {
            BookUpsertService.UpsertResult firstResult = bookUpsertService.upsert(firstAggregate);
            createdBookIds.add(firstResult.getBookId());
            BookUpsertService.UpsertResult secondResult = bookUpsertService.upsert(secondAggregate);
            createdBookIds.add(secondResult.getBookId());

            assertThat(firstResult.getBookId()).isNotEqualTo(secondResult.getBookId());
            assertThat(firstResult.getSlug()).isEqualTo("shared-title-" + firstResult.getBookId());
            assertThat(secondResult.getSlug()).isEqualTo("shared-title-" + secondResult.getBookId());
            assertThat(firstResult.getSlug()).hasSizeLessThanOrEqualTo(100);
            assertThat(secondResult.getSlug()).hasSizeLessThanOrEqualTo(100);

            List<String> persistedSlugs = jdbcTemplate.query(
                "SELECT slug FROM books WHERE id IN (?, ?)",
                (resultSet, rowNumber) -> resultSet.getString("slug"),
                firstResult.getBookId(),
                secondResult.getBookId()
            );
            assertThat(persistedSlugs)
                .containsExactlyInAnyOrder(firstResult.getSlug(), secondResult.getSlug());
        } finally {
            createdBookIds.forEach(this::deleteBookFixture);
            jdbcTemplate.update("DELETE FROM authors WHERE name IN (?, ?)", firstAuthor, secondAuthor);
            jdbcTemplate.update("DELETE FROM books WHERE id = ?", bookId);
        }
    }

    @Test
    void should_IgnoreInvalidImageLinks_When_IncomingPayloadContainsPlaceholderAndUnsupportedTypes() {
        UUID invalidBookId = UUID.randomUUID();
        String invalidIsbn13 = "9780132350990";
        insertBook(invalidBookId, invalidIsbn13, "Invalid Link Fixture");

        BookAggregate aggregate = BookAggregate.builder()
            .title("Invalid Link Fixture")
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
    void should_ThrowIllegalStateException_When_CoverWriteFails(CoverWriteOperation operation) {
        JdbcTemplate failingJdbcTemplate = Mockito.mock(JdbcTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("update")) {
                throw new DataAccessResourceFailureException("cover write failed");
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        CoverPersistenceService failingService = new CoverPersistenceService(failingJdbcTemplate);
        UUID failingBookId = UUID.randomUUID();

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
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(DataAccessResourceFailureException.class)
            .hasRootCauseMessage("cover write failed");
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

    private static PostgreSQLContainer postgresContainer() {
        PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("findmybook")
            .withUsername("findmybook")
            .withPassword("findmybook");
        postgres.withCopyToContainer(
            MountableFile.forHostPath(CANONICAL_SCHEMA),
            "/docker-entrypoint-initdb.d/000-schema.sql"
        );
        canonicalMigrationFiles().forEach(migration -> postgres.withCopyToContainer(
            MountableFile.forHostPath(migration),
            "/migrations/" + migration.getFileName()
        ));
        return postgres;
    }

    private static List<Path> canonicalMigrationFiles() {
        try (Stream<Path> migrationFiles = Files.list(MIGRATIONS_DIRECTORY)) {
            return migrationFiles
                .filter(Files::isRegularFile)
                .filter(migration -> migration.getFileName().toString().endsWith(".sql"))
                .sorted()
                .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Unable to copy canonical SQL migrations from " + MIGRATIONS_DIRECTORY,
                exception
            );
        }
    }

    @Test
    void should_KeepProcedureAsSoleAuthorWriter_When_ScanningRuntimeSourcesAndPostCanonicalMigrations()
        throws IOException {
        List<Path> migrations = canonicalMigrationFiles();
        List<Path> procedureDefinitions = sourcesContaining(migrations, CANONICAL_AUTHOR_PROCEDURE);
        List<Path> cutoverAuthorWrites = sourcesContaining(
            migrations.stream().filter(BookUpsertImageLinksEventTest::isPostCanonicalMigration).toList(),
            DIRECT_AUTHOR_WRITE
        );

        assertThat(sourcesContaining(runtimeExecutableSources(), DIRECT_AUTHOR_WRITE)).isEmpty();
        assertThat(procedureDefinitions).containsExactly(CANONICAL_AUTHOR_UPSERT_MIGRATION);
        assertThat(cutoverAuthorWrites).containsExactlyInAnyOrder(
            CANONICAL_AUTHOR_UPSERT_MIGRATION,
            AUTHOR_CONTRACT_TRANSITION_MIGRATION
        );
        assertThat(cutoverAuthorWrites.stream().filter(path -> !procedureDefinitions.contains(path)).toList())
            .containsExactly(AUTHOR_CONTRACT_TRANSITION_MIGRATION);
    }

    private static List<Path> runtimeExecutableSources() throws IOException {
        List<Path> executableSources = new ArrayList<>();
        for (Path runtimeSourceRoot : RUNTIME_EXECUTABLE_SOURCE_ROOTS) {
            try (Stream<Path> sourcePaths = Files.walk(runtimeSourceRoot)) {
                executableSources.addAll(sourcePaths
                    .filter(Files::isRegularFile)
                    .filter(path -> EXECUTABLE_SOURCE_FILE.matcher(path.getFileName().toString()).matches())
                    .filter(path -> !path.getFileName().toString().contains(".test.")
                        && !path.getFileName().toString().contains(".spec."))
                    .toList());
            }
        }
        return executableSources;
    }

    private static List<Path> sourcesContaining(List<Path> sourcePaths, Pattern pattern) throws IOException {
        List<Path> matches = new ArrayList<>();
        for (Path sourcePath : sourcePaths) {
            if (pattern.matcher(Files.readString(sourcePath)).find()) {
                matches.add(sourcePath);
            }
        }
        return matches;
    }

    private static boolean isPostCanonicalMigration(Path sourcePath) {
        return POST_CANONICAL_MIGRATION_FILE.matcher(sourcePath.getFileName().toString()).matches();
    }

    private BookAggregate sameTitleAggregate(String externalId, String author) {
        return BookAggregate.builder()
            .title("Shared Title")
            .authors(List.of(author))
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId(externalId)
                .build())
            .build();
    }

    private void deleteBookFixture(UUID targetBookId) {
        jdbcTemplate.update("DELETE FROM events_outbox WHERE topic = ?", "/topic/book." + targetBookId);
        jdbcTemplate.update("DELETE FROM books WHERE id = ?", targetBookId);
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
