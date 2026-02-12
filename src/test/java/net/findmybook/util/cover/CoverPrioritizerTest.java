package net.findmybook.util.cover;

import net.findmybook.dto.BookCard;
import net.findmybook.model.Book;
import net.findmybook.util.ApplicationConstants;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
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
    @DisplayName("hasColorCover rejects grayscale and placeholder cards")
    void should_RejectGrayscaleAndPlaceholder_When_CheckingHasColorCover() {
        BookCard grayscale = new BookCard(
            "gray",
            "gray",
            "Gray Cover",
            List.of("Author"),
            "https://cdn.test/gray.jpg",
            null,
            "https://cdn.test/gray-fallback.jpg",
            4.0,
            20,
            Map.of(),
            Boolean.TRUE
        );
        BookCard placeholder = new BookCard(
            "placeholder",
            "placeholder",
            "Placeholder",
            List.of("Author"),
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
            null,
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
            3.0,
            1,
            Map.of()
        );
        BookCard color = new BookCard(
            "color",
            "color",
            "Color Cover",
            List.of("Author"),
            "https://cdn.test/color.jpg",
            null,
            "https://cdn.test/color-fallback.jpg",
            4.3,
            33,
            Map.of()
        );

        assertThat(CoverPrioritizer.hasColorCover(grayscale)).isFalse();
        assertThat(CoverPrioritizer.hasColorCover(placeholder)).isFalse();
        assertThat(CoverPrioritizer.hasColorCover(color)).isTrue();
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
    @DisplayName("score(BookCard) uses S3 key when cover URL is missing")
    void scoreUsesS3KeyWhenCoverUrlMissing() {
        BookCard s3Only = new BookCard(
            "s3-only",
            "s3-only",
            "S3 Only",
            List.of("Author"),
            null,
            "covers/s3-only.jpg",
            null,
            4.0,
            25,
            Map.of()
        );

        assertThat(CoverPrioritizer.score(s3Only)).isGreaterThan(0);
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
    @DisplayName("resolve() treats null-equivalent primary values as missing and uses fallback")
    void resolveUsesFallbackWhenPrimaryIsNullEquivalent() {
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            "null",
            "https://example.test/fallback.jpg"
        );

        assertThat(resolved.url()).isEqualTo("https://example.test/fallback.jpg");
        assertThat(resolved.s3Key()).isNull();
        assertThat(resolved.fromS3()).isFalse();
    }

    @Test
    @DisplayName("bookComparatorWithPrimarySort keeps no-cover rows behind color covers")
    void should_DemoteNoCoverRows_When_PrimarySortIsProvided() {
        Book noCoverHighRelevance = book("1", null, null, null, null, false);
        noCoverHighRelevance.addQualifier("search.relevanceScore", 0.99d);
        Book colorLowerRelevance = book("2", "https://cdn.test/covers/color.jpg", null, 900, 1400, true);
        colorLowerRelevance.addQualifier("search.relevanceScore", 0.50d);

        List<Book> books = new ArrayList<>(List.of(noCoverHighRelevance, colorLowerRelevance));
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        for (int index = 0; index < books.size(); index++) {
            insertionOrder.put(books.get(index).getId(), index);
        }

        Comparator<Book> relevanceSort = Comparator.<Book>comparingDouble(b -> {
            Object raw = b.getQualifiers().get("search.relevanceScore");
            return raw instanceof Number number ? number.doubleValue() : 0.0d;
        }).reversed();

        books.sort(CoverPrioritizer.bookComparatorWithPrimarySort(insertionOrder, relevanceSort));

        assertThat(books)
            .extracting(Book::getId)
            .containsExactly("2", "1");
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

        books.sort(CoverPrioritizer.bookComparator(insertionOrder));

        assertThat(books)
            .extracting(Book::getId)
            .containsExactly("1", "2", "3", "4");
    }

    @Test
    @DisplayName("bookComparator demotes null-equivalent cover values behind real covers")
    void bookComparatorDemotesNullEquivalentValues() {
        Book valid = book("1", "https://cdn.test/covers/valid.jpg", null, 800, 1200, true);
        Book invalid = book("2", null, "null", null, null, false);

        List<Book> books = new ArrayList<>(List.of(invalid, valid));
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        for (int i = 0; i < books.size(); i++) {
            insertionOrder.put(books.get(i).getId(), i);
        }

        books.sort(CoverPrioritizer.bookComparator(insertionOrder));

        assertThat(books)
            .extracting(Book::getId)
            .containsExactly("1", "2");
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

    @Test
    @DisplayName("bookComparator prefers newer published date among same-quality covers")
    void should_PreferNewerBook_When_CoverQualityIsEqual() {
        Book older = book("older", "https://cdn.test/covers/a.jpg", null, 900, 1400, true);
        older.setPublishedDate(toDate(LocalDate.of(2010, 1, 1)));

        Book newer = book("newer", "https://cdn.test/covers/b.jpg", null, 900, 1400, true);
        newer.setPublishedDate(toDate(LocalDate.of(2024, 6, 15)));

        List<Book> books = new ArrayList<>(List.of(older, newer));
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        insertionOrder.put("older", 0);
        insertionOrder.put("newer", 1);

        books.sort(CoverPrioritizer.bookComparator(insertionOrder));

        assertThat(books)
            .extracting(Book::getId)
            .containsExactly("newer", "older");
    }

    @Test
    @DisplayName("bookComparator ranks null published date after dated books")
    void should_DemoteNullDate_When_OtherBookHasDate() {
        Book dated = book("dated", "https://cdn.test/covers/a.jpg", null, 900, 1400, true);
        dated.setPublishedDate(toDate(LocalDate.of(2020, 3, 10)));

        Book undated = book("undated", "https://cdn.test/covers/b.jpg", null, 900, 1400, true);
        // publishedDate stays null

        List<Book> books = new ArrayList<>(List.of(undated, dated));
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        insertionOrder.put("undated", 0);
        insertionOrder.put("dated", 1);

        books.sort(CoverPrioritizer.bookComparator(insertionOrder));

        assertThat(books)
            .extracting(Book::getId)
            .containsExactly("dated", "undated");
    }

    @Test
    @DisplayName("cardComparator prefers newer published date among same-quality cards")
    void should_PreferNewerCard_When_CoverQualityIsEqual() {
        BookCard olderCard = new BookCard(
            "1", "older", "Older Book", List.of("Author"),
            "https://cdn.test/covers/old.jpg", "covers/old.jpg", "https://cdn.test/covers/old.jpg",
            4.0, 10, Map.<String, Object>of(), null,
            LocalDate.of(2010, 1, 1)
        );
        BookCard newerCard = new BookCard(
            "2", "newer", "Newer Book", List.of("Author"),
            "https://cdn.test/covers/new.jpg", "covers/new.jpg", "https://cdn.test/covers/new.jpg",
            4.0, 10, Map.<String, Object>of(), null,
            LocalDate.of(2024, 6, 15)
        );

        List<BookCard> cards = new ArrayList<>(List.of(olderCard, newerCard));
        Map<String, Integer> originalOrder = new LinkedHashMap<>();
        originalOrder.put("1", 0);
        originalOrder.put("2", 1);

        cards.sort(CoverPrioritizer.cardComparator(originalOrder));

        assertThat(cards)
            .extracting(BookCard::id)
            .containsExactly("2", "1");
    }

    private static Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
