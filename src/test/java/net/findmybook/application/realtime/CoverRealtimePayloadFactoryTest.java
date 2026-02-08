package net.findmybook.application.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.List;
import java.util.Map;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.CoverImages;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import org.junit.jupiter.api.Test;

class CoverRealtimePayloadFactoryTest {

    private final CoverRealtimePayloadFactory payloadFactory = new CoverRealtimePayloadFactory();

    @Test
    void should_MapSearchResultPayloadWithTypedRelevance_When_QualifiersArePresent() {
        Book book = new Book();
        book.setId("book-123");
        book.setSlug("");
        book.setTitle("Working Effectively with Legacy Code");
        book.setAuthors(List.of("Michael Feathers"));
        book.setDescription("A practical refactoring guide");
        book.setPublishedDate(new Date(123456789L));
        book.setPageCount(456);
        book.setCategories(List.of("Software Engineering"));
        book.setIsbn10("0131177052");
        book.setIsbn13("9780131177055");
        book.setPublisher("Prentice Hall");
        book.setLanguage("en");
        book.setS3ImagePath("covers/book-123.webp");
        book.setExternalImageUrl("https://covers.example.com/book-123.jpg");
        book.setCoverImages(new CoverImages(
            "https://cdn.example.com/book-123.webp",
            "https://covers.example.com/book-123.jpg",
            CoverImageSource.GOOGLE_BOOKS
        ));
        book.setQualifiers(Map.of(
            "search.matchType", "TITLE",
            "search.relevanceScore", "0.91"
        ));

        SearchResultsUpdatedEvent event = new SearchResultsUpdatedEvent(
            "legacy code",
            List.of(book),
            "GOOGLE_BOOKS",
            14,
            "legacy_code",
            false
        );

        CoverRealtimePayloadFactory.SearchResultsPayload payload = payloadFactory.createSearchResultsPayload(event);

        assertThat(payload.searchQuery()).isEqualTo("legacy code");
        assertThat(payload.source()).isEqualTo("GOOGLE_BOOKS");
        assertThat(payload.totalResultsNow()).isEqualTo(14);
        assertThat(payload.newResultsCount()).isEqualTo(1);

        CoverRealtimePayloadFactory.SearchResultBookSnapshot snapshot = payload.newResults().getFirst();
        assertThat(snapshot.id()).isEqualTo("book-123");
        assertThat(snapshot.slug()).isEqualTo("book-123");
        assertThat(snapshot.matchType()).isEqualTo("TITLE");
        assertThat(snapshot.relevanceScore()).isEqualTo(0.91d);
        assertThat(snapshot.cover().s3ImagePath()).isEqualTo("covers/book-123.webp");
        assertThat(snapshot.cover().source()).isEqualTo("GOOGLE_BOOKS");
    }

    @Test
    void should_SetNullRelevance_When_RelevanceQualifierIsNotNumeric() {
        Book book = new Book();
        book.setId("book-999");
        book.setQualifiers(Map.of(
            "search.matchType", "AUTHOR",
            "search.relevanceScore", "not-a-number"
        ));

        SearchResultsUpdatedEvent event = new SearchResultsUpdatedEvent(
            "authors",
            List.of(book),
            "OPEN_LIBRARY",
            1,
            "authors",
            true
        );

        CoverRealtimePayloadFactory.SearchResultsPayload payload = payloadFactory.createSearchResultsPayload(event);

        assertThat(payload.newResults()).hasSize(1);
        assertThat(payload.newResults().getFirst().relevanceScore()).isNull();
        assertThat(payload.newResults().getFirst().matchType()).isEqualTo("AUTHOR");
    }
}
