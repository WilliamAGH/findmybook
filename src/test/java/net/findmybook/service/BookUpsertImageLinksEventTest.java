package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.test.annotations.DbIntegrationTest;
import net.findmybook.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DbIntegrationTest
@Import(BookUpsertImageLinksEventTest.EventConfig.class)
class BookUpsertImageLinksEventTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BookUpsertService bookUpsertService;

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

    private void insertBook() {
        jdbcTemplate.update(
            "INSERT INTO books (id, title, slug, isbn13, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
            bookId,
            "Existing Book",
            "existing-book-" + bookId,
            isbn13
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
