package net.findmybook.support.seo;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import net.findmybook.model.Book;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BookStructuredDataRendererTest {

    @Test
    void should_RenderBookGraphJsonLd_When_BookMetadataProvided() {
        BookStructuredDataRenderer renderer = new BookStructuredDataRenderer(new SeoMarkupFormatter());
        Book book = new Book();
        book.setId("book-id");
        book.setSlug("the-catcher-in-the-rye");
        book.setTitle("The Catcher in the Rye");
        book.setDescription("A classic novel about teenage rebellion and alienation in 1950s New York City.");
        book.setAuthors(List.of("J.D. Salinger"));
        book.setPublisher("Little, Brown and Company");
        book.setCategories(List.of("Fiction", "Literary Fiction"));
        book.setLanguage("en");
        book.setIsbn13("9780316769488");
        book.setInfoLink("https://books.example.com/the-catcher-in-the-rye");
        book.setPreviewLink("https://books.example.com/the-catcher-in-the-rye/preview");
        book.setListPrice(18.99);
        book.setCurrencyCode("USD");
        book.setPurchaseLink("https://books.example.com/the-catcher-in-the-rye/buy");
        book.setPageCount(214);
        book.setAverageRating(4.5);
        book.setRatingsCount(1200);
        book.setPublishedDate(Date.from(Instant.parse("1951-07-16T00:00:00Z")));

        String json = renderer.renderBookGraph(
            new BookGraphRenderRequest(
                book,
                "https://findmybook.net/book/the-catcher-in-the-rye",
                "The Catcher in the Rye | findmybook",
                "The Catcher in the Rye",
                "Fallback description",
                "https://findmybook.net/images/cover.jpg",
                "findmybook",
                "https://findmybook.net"
            )
        );

        assertTrue(json.contains("\"@type\":\"Book\""));
        assertTrue(json.contains("\"isbn\":\"9780316769488\""));
        assertTrue(json.contains("\"datePublished\":\"1951-07-16\""));
        assertTrue(json.contains("\"numberOfPages\":214"));
        assertTrue(json.contains("\"aggregateRating\""));
        assertTrue(json.contains("\"name\":\"J.D. Salinger\""));
        assertTrue(json.contains("\"genre\":[\"Fiction\",\"Literary Fiction\"]"));
        assertTrue(json.contains("\"sameAs\":\"https://books.example.com/the-catcher-in-the-rye\""));
        assertTrue(json.contains("\"offers\""));
        assertTrue(json.contains("\"@type\":\"ReadAction\""));
    }

    @Test
    void should_EmitIsbn10AsIdentifier_When_Isbn13Missing() {
        BookStructuredDataRenderer renderer = new BookStructuredDataRenderer(new SeoMarkupFormatter());
        Book book = new Book();
        book.setTitle("Legacy ISBN Book");
        book.setIsbn10("0316769487");

        String json = renderer.renderBookGraph(
            new BookGraphRenderRequest(
                book,
                "https://findmybook.net/book/legacy-isbn",
                "Legacy ISBN Book | findmybook",
                "Legacy ISBN Book",
                "Fallback description",
                "https://findmybook.net/images/cover.jpg",
                "findmybook",
                "https://findmybook.net"
            )
        );

        assertTrue(json.contains("\"propertyID\":\"ISBN-10\""));
        assertTrue(!json.contains("\"isbn\":\"0316769487\""));
    }

    @Test
    void should_UseFallbackDescription_When_BookDescriptionIsMissing() {
        BookStructuredDataRenderer renderer = new BookStructuredDataRenderer(new SeoMarkupFormatter());
        Book book = new Book();
        book.setTitle("Untitled");

        String json = renderer.renderBookGraph(
            new BookGraphRenderRequest(
                book,
                "https://findmybook.net/book/untitled",
                "Untitled | findmybook",
                "Untitled",
                "Fallback description",
                "https://findmybook.net/images/cover.jpg",
                "findmybook",
                "https://findmybook.net"
            )
        );

        assertTrue(json.contains("\"description\":\"Fallback description\""));
    }
}
