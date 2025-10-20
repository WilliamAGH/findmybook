package com.williamcallahan.book_recommendation_engine.dto;

import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.model.image.CoverImageSource;
import com.williamcallahan.book_recommendation_engine.util.BookDomainMapper;
import com.williamcallahan.book_recommendation_engine.util.cover.ImageDimensionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookDomainMapperCoverSourceTest {

    @Test
    @DisplayName("toBook(BookCard) detects Google Books cover source")
    void toBookFromCard_detectsGoogleBooksSource() {
        BookCard card = new BookCard(
            "card-id",
            "card-slug",
            "Card Title",
            List.of("Author"),
            "https://books.google.com/books/content?id=ABC123&printsec=frontcover&edge=curl",
            null,
            "https://books.google.com/books/content?id=ABC123&printsec=frontcover&zoom=1&edge=curl",
            4.2,
            120,
            Map.<String, Object>of()
        );

        Book book = BookDomainMapper.fromCard(card);

        assertThat(book).isNotNull();
        assertThat(book.getCoverImages()).isNotNull();
        assertThat(book.getCoverImages().getSource()).isEqualTo(CoverImageSource.GOOGLE_BOOKS);
    }

    @Test
    @DisplayName("toBook(BookCard) suppresses covers that do not meet display requirements")
    void toBookFromCard_suppressesUndersizedCover() {
        BookCard card = new BookCard(
            "undersized-card",
            "undersized-slug",
            "Undersized Cover Title",
            List.of("Author"),
            "https://cdn.example.com/covers/undersized.jpg?w=120&h=160",
            null,
            "https://cdn.example.com/covers/undersized-fallback.jpg?w=120&h=160",
            4.5,
            42,
            Map.<String, Object>of()
        );

        Book book = BookDomainMapper.fromCard(card);

        assertThat(book).isNotNull();
        assertThat(book.getCoverImages()).isNull();
        assertThat(book.getExternalImageUrl()).isNull();
        assertThat(book.getS3ImagePath()).isNull();
        assertThat(book.getQualifiers())
            .containsEntry("cover.suppressed", true)
            .containsEntry("cover.suppressed.reason", "image-below-display-requirements")
            .containsEntry("cover.suppressed.minHeight", ImageDimensionUtils.MIN_SEARCH_RESULT_HEIGHT);
    }

    @Test
    @DisplayName("toBook(BookDetail) detects Open Library cover source")
    void toBookFromDetail_detectsOpenLibrarySource() {
        BookDetail detail = buildDetail(
            "https://covers.openlibrary.org/b/id/12345-L.jpg",
            null,
            "https://covers.openlibrary.org/b/id/12345-M.jpg",
            "https://covers.openlibrary.org/b/id/12345-S.jpg"
        );

        Book book = BookDomainMapper.fromDetail(detail);

        assertThat(book).isNotNull();
        assertThat(book.getCoverImages()).isNotNull();
        assertThat(book.getCoverImages().getSource()).isEqualTo(CoverImageSource.OPEN_LIBRARY);
    }

    @Test
    @DisplayName("toBook(BookListItem) leaves unknown sources as UNDEFINED")
    void toBookFromListItem_defaultsToUndefinedForUnknownSources() {
        BookListItem item = new BookListItem(
            "item-id",
            "item-slug",
            "Item Title",
            "Description",
            List.of("Author"),
            List.of("Category"),
            "https://cdn.example.com/covers/item-id.jpg",
            "s3://covers/item-id.jpg",
            "https://cdn.example.com/covers/item-id-thumb.jpg",
            500,
            750,
            false,
            3.9,
            80,
            Map.<String, Object>of()
        );

        Book book = BookDomainMapper.fromListItem(item);

        assertThat(book).isNotNull();
        assertThat(book.getCoverImages()).isNotNull();
        assertThat(book.getCoverImages().getSource()).isEqualTo(CoverImageSource.UNDEFINED);
    }

    @Test
    @DisplayName("fromAggregate suppresses invalid aspect ratio covers")
    void fromAggregate_suppressesInvalidAspectRatio() {
        BookAggregate aggregate = BookAggregate.builder()
            .title("Agg Title")
            .authors(List.of("Author"))
            .slugBase("agg-title")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("agg-id")
                .imageLinks(Map.of(
                    "thumbnail",
                    "https://example.test/wide-cover.jpg?w=1200&h=120"
                ))
                .build())
            .build();

        Book book = BookDomainMapper.fromAggregate(aggregate);

        assertThat(book).isNotNull();
        assertThat(book.getCoverImages()).isNull();
        assertThat(book.getExternalImageUrl()).isNull();
        assertThat(book.getQualifiers())
            .containsEntry("cover.suppressed", true);
    }

    private static BookDetail buildDetail(String coverUrl,
                                          String coverS3Key,
                                          String fallbackUrl,
                                          String thumbnailUrl) {
        return new BookDetail(
            "detail-id",
            "detail-slug",
            "Detail Title",
            "Description",
            "Publisher",
            null,
            "en",
            350,
            List.of("Author"),
            List.of("Category"),
            coverUrl,
            coverS3Key,
            fallbackUrl,
            thumbnailUrl,
            Integer.valueOf(600),
            Integer.valueOf(900),
            Boolean.TRUE,
            "OPEN_LIBRARY",
            Double.valueOf(4.5),
            Integer.valueOf(340),
            "isbn10",
            "isbn13",
            "preview",
            "info",
            Map.<String, Object>of(),
            List.<EditionSummary>of()
        );
    }
}
