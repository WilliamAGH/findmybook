package net.findmybook.support.seo;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import net.findmybook.model.Book;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookOpenGraphPropertyFactoryTest {

    @Test
    void should_CreateBookNamespaceProperties_When_BookHasIsbnDateAndCategories() {
        BookOpenGraphPropertyFactory factory = new BookOpenGraphPropertyFactory(new SeoMarkupFormatter());
        Book book = new Book();
        book.setIsbn13("9780316769488");
        book.setPublishedDate(Date.from(Instant.parse("1951-07-16T00:00:00Z")));
        book.setCategories(List.of("Fiction", "Literary Fiction"));

        List<OpenGraphProperty> properties = factory.fromBook(book);
        List<String> keys = properties.stream().map(OpenGraphProperty::property).collect(Collectors.toList());

        assertTrue(keys.contains("book:isbn"));
        assertTrue(keys.contains("book:release_date"));
        assertEquals("9780316769488", properties.get(0).content());
        assertEquals("1951-07-16", properties.get(1).content());
    }

    @Test
    void should_SkipIsbnProperty_When_OnlyIsbn10IsPresent() {
        BookOpenGraphPropertyFactory factory = new BookOpenGraphPropertyFactory(new SeoMarkupFormatter());
        Book book = new Book();
        book.setIsbn10("0316769487");
        book.setCategories(List.of("Fiction"));

        List<OpenGraphProperty> properties = factory.fromBook(book);
        List<String> keys = properties.stream().map(OpenGraphProperty::property).collect(Collectors.toList());

        assertTrue(!keys.contains("book:isbn"));
        assertTrue(keys.contains("book:tag"));
    }
}
