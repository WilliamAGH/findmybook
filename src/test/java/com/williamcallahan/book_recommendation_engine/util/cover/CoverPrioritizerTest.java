package com.williamcallahan.book_recommendation_engine.util.cover;

import com.williamcallahan.book_recommendation_engine.dto.BookCard;
import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.util.ApplicationConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverPrioritizerTest {

    @BeforeEach
    void setUp() {
        CoverUrlResolver.setCdnBase("https://cdn.test/");
    }

    @AfterEach
    void tearDown() {
        CoverUrlResolver.setCdnBase(null);
    }

    @Test
    @DisplayName("cardComparator orders cards by cover quality before original order")
    void cardComparatorOrdersByCoverScore() {
        BookCard high = new BookCard(
            "1",
            "high",
            "High Cover",
            List.of("Author A"),
            "https://cdn.test/covers/high.jpg",
            "covers/high.jpg",
            "https://cdn.test/covers/high.jpg",
            4.5,
            100,
            Map.<String, Object>of()
        );
        BookCard medium = new BookCard(
            "2",
            "medium",
            "Medium Cover",
            List.of("Author B"),
            "https://images.test/medium.jpg?w=512&h=768",
            null,
            "https://images.test/medium.jpg?w=320&h=480",
            4.2,
            50,
            Map.<String, Object>of()
        );
        BookCard low = new BookCard(
            "3",
            "low",
            "Low Cover",
            List.of("Author C"),
            "https://example.test/low.jpg?w=150&h=200",
            null,
            "https://example.test/low.jpg?w=120&h=180",
            4.0,
            10,
            Map.<String, Object>of()
        );
        BookCard placeholder = new BookCard(
            "4",
            "placeholder",
            "No Cover",
            List.of("Author D"),
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
            null,
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
            3.8,
            5,
            Map.<String, Object>of()
        );

        List<BookCard> cards = new ArrayList<>(List.of(low, placeholder, medium, high));
        Map<String, Integer> originalOrder = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            originalOrder.put(cards.get(i).id(), i);
        }

        cards.sort(CoverPrioritizer.cardComparator(originalOrder));

        assertThat(cards)
            .extracting(BookCard::id)
            .containsExactly("1", "2", "3", "4");
    }

    @Test
    @DisplayName("resolve() treats uppercase HTTP schemes as external URLs")
    void resolveHandlesUppercaseHttpScheme() {
        CoverUrlResolver.setCdnBase(null);
        try {
            CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve("HTTPS://example.test/image.jpg");
            assertThat(resolved.url()).isEqualTo("HTTPS://example.test/image.jpg");
            assertThat(resolved.fromS3()).isFalse();
        } finally {
            CoverUrlResolver.setCdnBase("https://cdn.test/");
        }
    }

    @Test
    @DisplayName("resolve() does not mark default dimensions as high resolution")
    void resolveDefaultDimensionsNotHighRes() {
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve("covers/missing.jpg");
        assertThat(resolved.width()).isEqualTo(512);
        assertThat(resolved.height()).isEqualTo(768);
        assertThat(resolved.highResolution()).isFalse();
    }

    @Test
    @DisplayName("bookComparator ranks books by best cover before insertion order")
    void bookComparatorOrdersByCoverScore() {
        Book high = book("1", "https://cdn.test/covers/high.jpg", null, 900, 1400, true);
        Book medium = book("2", "https://images.test/medium.jpg?w=512&h=768", null, 512, 768, true);
        Book low = book("3", "https://example.test/low.jpg?w=160&h=220", null, 160, 220, false);
        Book fallback = book("4", ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH, null, null, null, false);

        List<Book> books = new ArrayList<>(List.of(low, high, fallback, medium));
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        for (int i = 0; i < books.size(); i++) {
            insertionOrder.put(books.get(i).getId(), i);
        }

        books.sort(CoverPrioritizer.bookComparator(insertionOrder, null));

        assertThat(books)
            .extracting(Book::getId)
            .containsExactly("1", "2", "3", "4");
    }

    private Book book(String id,
                      String externalUrl,
                      String s3Key,
                      Integer width,
                      Integer height,
                      Boolean highResolution) {
        Book book = new Book();
        book.setId(id);
        book.setExternalImageUrl(externalUrl);
        book.setS3ImagePath(s3Key);
        book.setCoverImageWidth(width);
        book.setCoverImageHeight(height);
        book.setIsCoverHighResolution(highResolution);
        book.setInPostgres(true);
        return book;
    }
}
