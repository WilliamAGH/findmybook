package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.test.annotations.DbIntegrationTest;
import net.findmybook.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DbIntegrationTest
@Import(BookUpsertImageLinksEventTest.EventConfig.class)
class BookUpsertImageLinksEventTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BookUpsertService bookUpsertService;

    @Autowired
    private CoverPersistenceService coverPersistenceService;

    @Autowired
    private EventCollector eventCollector;

    private UUID bookId;
    private String isbn13;

    @BeforeEach
    void setUp() {
        eventCollector.clear();
        bookId = UUID.randomUUID();
        isbn13 = "9780132350884";
        insertBook();
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

    @Test
    void should_ThrowDataAccessException_When_PersistFromGoogleImageLinksFailsToWrite() {
        JdbcTemplate failingJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        CoverPersistenceService failingService = new CoverPersistenceService(failingJdbcTemplate);
        UUID failingBookId = UUID.randomUUID();

        Mockito.doThrow(new DataAccessResourceFailureException("book_image_links unavailable"))
            .when(failingJdbcTemplate)
            .update(ArgumentMatchers.anyString(), ArgumentMatchers.<Object[]>any());

        assertThatThrownBy(() -> failingService.persistFromGoogleImageLinks(
            failingBookId,
            Map.of("thumbnail", "https://example.com/thumb.jpg"),
            "GOOGLE_BOOKS"
        ))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("book_image_links unavailable");
    }

    @Test
    void should_ThrowDataAccessException_When_UpdateAfterS3UploadFailsToWrite() {
        JdbcTemplate failingJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        CoverPersistenceService failingService = new CoverPersistenceService(failingJdbcTemplate);
        UUID failingBookId = UUID.randomUUID();

        Mockito.doThrow(new DataAccessResourceFailureException("s3 metadata write failed"))
            .when(failingJdbcTemplate)
            .update(ArgumentMatchers.anyString(), ArgumentMatchers.<Object[]>any());

        assertThatThrownBy(() -> failingService.updateAfterS3Upload(
            failingBookId,
            new CoverPersistenceService.S3UploadResult(
                "images/book-covers/" + failingBookId + ".jpg",
                "https://book-finder.sfo3.digitaloceanspaces.com/images/book-covers/" + failingBookId + ".jpg",
                640,
                960,
                CoverImageSource.GOOGLE_BOOKS
            )
        ))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("s3 metadata write failed");
    }

    @Test
    void should_ThrowDataAccessException_When_PersistExternalCoverFailsToWrite() {
        JdbcTemplate failingJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        CoverPersistenceService failingService = new CoverPersistenceService(failingJdbcTemplate);
        UUID failingBookId = UUID.randomUUID();

        Mockito.doThrow(new DataAccessResourceFailureException("external cover write failed"))
            .when(failingJdbcTemplate)
            .update(ArgumentMatchers.anyString(), ArgumentMatchers.<Object[]>any());

        assertThatThrownBy(() -> failingService.persistExternalCover(
            failingBookId,
            "https://example.com/external-cover.jpg",
            "OPEN_LIBRARY",
            500,
            800
        ))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessageContaining("external cover write failed");
    }

    private void insertBook() {
        insertBook(bookId, isbn13, "Existing Book");
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

    private record ImageLinkAuditRow(String imageType, String s3ImagePath, OffsetDateTime s3UploadedAt) {}

    @Configuration
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
