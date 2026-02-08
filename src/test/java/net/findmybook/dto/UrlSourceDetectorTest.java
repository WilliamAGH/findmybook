package net.findmybook.dto;

import net.findmybook.model.image.CoverImageSource;
import net.findmybook.util.cover.UrlSourceDetector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for cover source detection.
 *
 * <p>LegacyBookMapper hard-coded GOOGLE_BOOKS for many sources. The SSOT now lives in
 * {@link UrlSourceDetector}, so these tests verify the detector instead of deprecated mappers.</p>
 */
class UrlSourceDetectorTest {

    @Test
    void detectSource_returnsUndefinedForS3AndCdnUrls() {
        List<String> urls = List.of(
            "https://nyc3.digitaloceanspaces.com/mybucket/cover.jpg",
            "https://cdn.example.com/covers/book123.jpg",
            "https://mybucket.r2.dev/covers/book.jpg",
            "https://mybucket.s3.amazonaws.com/covers/book.jpg"
        );

        urls.forEach(url -> assertThat(UrlSourceDetector.detectSource(url))
            .as("Source for %s", url)
            .isEqualTo(CoverImageSource.UNDEFINED));
    }

    @Test
    void detectSource_identifiesOpenLibraryUrls() {
        BookCard card = new BookCard(
            "test-id",
            "test-slug",
            "Test Book",
            List.of("Test Author"),
            "https://covers.openlibrary.org/b/id/12345-L.jpg",
            "openlibrary/12345",
            "https://covers.openlibrary.org/b/id/12345-M.jpg",
            4.5,
            100,
            Map.<String, Object>of()
        );

        assertThat(UrlSourceDetector.detectSource(card.coverUrl()))
            .isEqualTo(CoverImageSource.OPEN_LIBRARY);
    }

    @Test
    void detectSource_identifiesGoogleBooksUrls() {
        BookCard card = new BookCard(
            "test-id",
            "test-slug",
            "Test Book",
            List.of("Test Author"),
            "https://books.google.com/books/content?id=ABC123&printsec=frontcover",
            null,
            "https://books.google.com/books/content?id=ABC123&printsec=frontcover&zoom=1",
            4.5,
            100,
            Map.<String, Object>of()
        );

        assertThat(UrlSourceDetector.detectSource(card.coverUrl()))
            .isEqualTo(CoverImageSource.GOOGLE_BOOKS);
    }

    @Test
    void detectSource_isCaseInsensitive() {
        BookCard card = new BookCard(
            "test-id",
            "test-slug",
            "Test Book",
            List.of("Test Author"),
            "HTTPS://COVERS.OPENLIBRARY.ORG/B/ID/12345-L.JPG",
            "openlibrary/12345",
            "https://covers.openlibrary.org/b/id/12345-M.jpg",
            4.5,
            100,
            Map.<String, Object>of()
        );

        assertThat(UrlSourceDetector.detectSource(card.coverUrl()))
            .isEqualTo(CoverImageSource.OPEN_LIBRARY);
    }

    @Test
    void detectSource_defaultsToUndefinedForUnknown() {
        assertThat(UrlSourceDetector.detectSource("https://unknown-provider.com/cover.jpg"))
            .isEqualTo(CoverImageSource.UNDEFINED);
    }

    @Test
    void detectSource_handlesNullOrBlank() {
        assertThat(UrlSourceDetector.detectSource(null)).isEqualTo(CoverImageSource.UNDEFINED);
        assertThat(UrlSourceDetector.detectSource(" ")).isEqualTo(CoverImageSource.UNDEFINED);
    }

    @Test
    void detectSource_supportsOpenLibraryFromBookDetail() {
        BookDetail detail = buildDetail(
            "https://covers.openlibrary.org/b/isbn/9780596520687-M.jpg",
            "openlibrary/9780596520687",
            "https://covers.openlibrary.org/b/isbn/9780596520687-L.jpg",
            "https://covers.openlibrary.org/b/isbn/9780596520687-S.jpg"
        );

        assertThat(UrlSourceDetector.detectSource(detail.coverUrl()))
            .isEqualTo(CoverImageSource.OPEN_LIBRARY);
    }

    @Test
    void detectSource_supportsGoogleBooksApiUrlsFromListItem() {
        BookListItem item = new BookListItem(
            "test-id",
            "test-slug",
            "Test Book",
            "Description",
            List.of("Author"),
            List.of("Category"),
            "https://books.googleapis.com/books/content/images/frontcover/ABC?fife=w240-h345",
            null,
            "https://books.googleapis.com/books/content/images/frontcover/ABC?fife=w120-h180",
            240,
            345,
            false,
            4.5,
            100,
            Map.<String, Object>of()
        );

        assertThat(UrlSourceDetector.detectSource(item.coverUrl()))
            .isEqualTo(CoverImageSource.GOOGLE_BOOKS);
    }

    private static BookDetail buildDetail(String coverUrl,
                                          String coverS3Key,
                                          String fallbackUrl,
                                          String thumbnailUrl) {
        return new BookDetail(
            "id2",
            "slug2",
            "Title 2",
            "Description",
            "Publisher",
            null,
            "en",
            300,
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
            Double.valueOf(4.0),
            Integer.valueOf(50),
            null,
            null,
            "preview",
            "info",
            Map.<String, Object>of(),
            List.<EditionSummary>of()
        );
    }
}
