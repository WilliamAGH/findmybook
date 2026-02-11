package net.findmybook.support.seo;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import net.findmybook.domain.seo.OpenGraphProperty;
import net.findmybook.model.Book;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates book-specific Open Graph extension properties from canonical book metadata.
 */
@Component
public class BookOpenGraphPropertyFactory {

    private static final int MAX_BOOK_TAGS = 8;

    private final SeoMarkupFormatter seoMarkupFormatter;

    public BookOpenGraphPropertyFactory(SeoMarkupFormatter seoMarkupFormatter) {
        this.seoMarkupFormatter = seoMarkupFormatter;
    }

    /**
     * Creates a typed list of Open Graph book namespace properties for a detail route.
     *
     * @param book canonical book metadata
     * @return ordered properties suitable for Open Graph head rendering
     */
    public List<OpenGraphProperty> fromBook(Book book) {
        if (book == null) {
            return List.of();
        }
        List<OpenGraphProperty> properties = new ArrayList<>();

        String isbn = preferredIsbn(book);
        if (StringUtils.hasText(isbn)) {
            properties.add(new OpenGraphProperty("book:isbn", isbn));
        }

        String releaseDate = publishedDateIso(book.getPublishedDate());
        if (StringUtils.hasText(releaseDate)) {
            properties.add(new OpenGraphProperty("book:release_date", releaseDate));
        }

        for (String tag : seoMarkupFormatter.normalizeTextValues(book.getCategories(), MAX_BOOK_TAGS)) {
            properties.add(new OpenGraphProperty("book:tag", tag));
        }

        // TODO: add book:author property (profile URL array per ogp.me spec) once author profile pages exist

        return List.copyOf(properties);
    }

    private String preferredIsbn(Book book) {
        if (StringUtils.hasText(book.getIsbn13())) {
            return book.getIsbn13().trim();
        }
        return "";
    }

    private String publishedDateIso(Date publishedDate) {
        if (publishedDate == null) {
            return "";
        }
        return publishedDate.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
