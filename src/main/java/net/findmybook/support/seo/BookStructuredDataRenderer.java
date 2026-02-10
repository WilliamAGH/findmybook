package net.findmybook.support.seo;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import net.findmybook.model.Book;
import net.findmybook.util.SeoUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders Book route JSON-LD graphs for server-side head output.
 */
@Component
public class BookStructuredDataRenderer {

    private static final int MAX_BOOK_SCHEMA_DESCRIPTION_LENGTH = 600;
    private static final int MAX_BOOK_SCHEMA_LIST_VALUES = 20;
    private static final int BEST_RATING = 5;
    private static final int WORST_RATING = 1;

    private final ObjectMapper objectMapper;
    private final SeoMarkupFormatter seoMarkupFormatter;

    public BookStructuredDataRenderer(ObjectMapper objectMapper, SeoMarkupFormatter seoMarkupFormatter) {
        this.objectMapper = objectMapper;
        this.seoMarkupFormatter = seoMarkupFormatter;
    }

    /**
     * Builds a JSON-LD graph with {@code WebSite}, {@code WebPage}, and {@code Book} entities.
     *
     * @param request typed render parameters encapsulating book, URLs, and site context
     * @return serialized JSON-LD payload
     */
    public String renderBookGraph(BookGraphRenderRequest request) {
        Book book = request.book();
        String canonicalUrl = request.canonicalUrl();
        String webPageTitle = request.webPageTitle();
        String bookTitle = request.bookTitle();
        String fallbackDescription = request.fallbackDescription();
        String ogImage = request.ogImage();
        String brandName = request.brandName();
        String baseUrl = request.baseUrl();

        ObjectNode rootNode = objectMapper.createObjectNode();
        rootNode.put("@context", "https://schema.org");
        ArrayNode graphNode = rootNode.putArray("@graph");

        ObjectNode websiteNode = graphNode.addObject();
        websiteNode.put("@type", "WebSite");
        websiteNode.put("@id", baseUrl + "/#website");
        websiteNode.put("url", baseUrl + "/");
        websiteNode.put("name", brandName);
        ObjectNode searchActionNode = websiteNode.putObject("potentialAction");
        searchActionNode.put("@type", "SearchAction");
        searchActionNode.put("target", baseUrl + "/search?query={search_term_string}");
        searchActionNode.put("query-input", "required name=search_term_string");

        String webPageId = canonicalUrl + "#webpage";
        String bookId = canonicalUrl + "#book";
        String bookDescription = schemaDescription(book, fallbackDescription);

        ObjectNode webPageNode = graphNode.addObject();
        webPageNode.put("@type", "WebPage");
        webPageNode.put("@id", webPageId);
        webPageNode.put("url", canonicalUrl);
        webPageNode.put("name", webPageTitle);
        webPageNode.put("description", bookDescription);
        webPageNode.put("inLanguage", "en-US");
        webPageNode.putObject("isPartOf").put("@id", baseUrl + "/#website");
        webPageNode.putObject("mainEntity").put("@id", bookId);
        if (StringUtils.hasText(ogImage)) {
            ObjectNode primaryImageNode = webPageNode.putObject("primaryImageOfPage");
            primaryImageNode.put("@type", "ImageObject");
            primaryImageNode.put("url", ogImage);
        }

        ObjectNode bookNode = graphNode.addObject();
        bookNode.put("@type", "Book");
        bookNode.put("@id", bookId);
        bookNode.put("url", canonicalUrl);
        bookNode.put("name", StringUtils.hasText(bookTitle) ? bookTitle : "Book Details");
        bookNode.put("description", bookDescription);
        bookNode.putObject("mainEntityOfPage").put("@id", webPageId);

        if (StringUtils.hasText(ogImage)) {
            bookNode.put("image", ogImage);
        }

        String isbn13 = isbn13(book);
        if (StringUtils.hasText(isbn13)) {
            bookNode.put("isbn", isbn13);
        } else if (StringUtils.hasText(book.getIsbn10())) {
            ObjectNode identifierNode = bookNode.putObject("identifier");
            identifierNode.put("@type", "PropertyValue");
            identifierNode.put("propertyID", "ISBN-10");
            identifierNode.put("value", book.getIsbn10().trim());
        }

        String language = normalizedLanguage(book.getLanguage());
        if (StringUtils.hasText(language)) {
            bookNode.put("inLanguage", language);
        }

        String publishedDate = publishedDateIso(book);
        if (StringUtils.hasText(publishedDate)) {
            bookNode.put("datePublished", publishedDate);
        }

        if (book.getPageCount() != null && book.getPageCount() > 0) {
            bookNode.put("numberOfPages", book.getPageCount());
        }

        List<String> authors = seoMarkupFormatter.normalizeTextValues(book.getAuthors(), MAX_BOOK_SCHEMA_LIST_VALUES);
        if (!authors.isEmpty()) {
            ArrayNode authorArray = bookNode.putArray("author");
            for (String authorName : authors) {
                ObjectNode authorNode = authorArray.addObject();
                authorNode.put("@type", "Person");
                authorNode.put("name", authorName);
            }
        }

        if (StringUtils.hasText(book.getPublisher())) {
            ObjectNode publisherNode = bookNode.putObject("publisher");
            publisherNode.put("@type", "Organization");
            publisherNode.put("name", book.getPublisher().trim());
        }
        if (StringUtils.hasText(book.getInfoLink())) {
            bookNode.put("sameAs", book.getInfoLink().trim());
        }

        if (book.getListPrice() != null
            && book.getListPrice() > 0
            && StringUtils.hasText(book.getCurrencyCode())) {
            ObjectNode offersNode = bookNode.putObject("offers");
            offersNode.put("@type", "Offer");
            offersNode.put("price", book.getListPrice());
            offersNode.put("priceCurrency", book.getCurrencyCode().trim());
            if (StringUtils.hasText(book.getPurchaseLink())) {
                offersNode.put("url", book.getPurchaseLink().trim());
            }
        }

        List<String> genres = seoMarkupFormatter.normalizeTextValues(book.getCategories(), MAX_BOOK_SCHEMA_LIST_VALUES);
        if (!genres.isEmpty()) {
            ArrayNode genreArray = bookNode.putArray("genre");
            for (String genre : genres) {
                genreArray.add(genre);
            }
        }

        if (book.getAverageRating() != null
            && book.getAverageRating() > 0
            && book.getRatingsCount() != null
            && book.getRatingsCount() > 0) {
            ObjectNode aggregateRatingNode = bookNode.putObject("aggregateRating");
            aggregateRatingNode.put("@type", "AggregateRating");
            aggregateRatingNode.put("ratingValue", book.getAverageRating());
            aggregateRatingNode.put("ratingCount", book.getRatingsCount());
            aggregateRatingNode.put("bestRating", BEST_RATING);
            aggregateRatingNode.put("worstRating", WORST_RATING);
        }

        String actionTarget = readActionTarget(book);
        if (StringUtils.hasText(actionTarget)) {
            ObjectNode potentialActionNode = bookNode.putObject("potentialAction");
            potentialActionNode.put("@type", "ReadAction");
            potentialActionNode.put("target", actionTarget);
        }

        try {
            return objectMapper.writeValueAsString(rootNode);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize book route structured data", ex);
        }
    }

    private String schemaDescription(Book book, String fallbackDescription) {
        if (book == null || !StringUtils.hasText(book.getDescription())) {
            return fallbackDescription;
        }
        return SeoUtils.truncateDescription(book.getDescription(), MAX_BOOK_SCHEMA_DESCRIPTION_LENGTH);
    }

    private String isbn13(Book book) {
        if (book == null) {
            return "";
        }
        if (StringUtils.hasText(book.getIsbn13())) {
            return book.getIsbn13().trim();
        }
        return "";
    }

    private String readActionTarget(Book book) {
        if (book == null) {
            return "";
        }
        if (StringUtils.hasText(book.getPreviewLink())) {
            return book.getPreviewLink().trim();
        }
        if (StringUtils.hasText(book.getWebReaderLink())) {
            return book.getWebReaderLink().trim();
        }
        if (StringUtils.hasText(book.getInfoLink())) {
            return book.getInfoLink().trim();
        }
        return "";
    }

    private String normalizedLanguage(String candidateLanguage) {
        if (!StringUtils.hasText(candidateLanguage)) {
            return "";
        }
        return candidateLanguage.trim();
    }

    private String publishedDateIso(Book book) {
        if (book == null || book.getPublishedDate() == null) {
            return "";
        }
        return book.getPublishedDate().toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }
}
