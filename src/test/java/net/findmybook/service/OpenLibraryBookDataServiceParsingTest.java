package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.util.DateParsingUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class OpenLibraryBookDataServiceParsingTest {

    @Test
    @DisplayName("parseOpenLibrarySearchDoc maps page count and first sentence description")
    void parseOpenLibrarySearchDoc_mapsPageCountAndDescription() {
        OpenLibraryBookDataService service = new OpenLibraryBookDataService(
            WebClient.builder(),
            "https://openlibrary.org",
            true
        );

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode doc = mapper.createObjectNode();
        doc.put("key", "/works/OL77004W");
        doc.put("title", "The Partner");
        doc.putArray("author_name").add("John Grisham");
        doc.put("first_publish_year", 1997);
        doc.put("cover_i", 9323420);
        doc.put("number_of_pages_median", 416);
        doc.putArray("first_sentence")
            .add("They found him in Ponta Porã, a pleasant little town in Brazil.");
        doc.putArray("publisher").add("Doubleday");
        doc.putArray("language").add("eng");
        doc.putArray("subject").add("Legal thrillers");

        Book parsed = ReflectionTestUtils.invokeMethod(service, "parseOpenLibrarySearchDoc", doc);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getId()).isEqualTo("OL77004W");
        assertThat(parsed.getDescription()).isEqualTo("They found him in Ponta Porã, a pleasant little town in Brazil.");
        assertThat(parsed.getPageCount()).isEqualTo(416);
        assertThat(parsed.getPublisher()).isEqualTo("Doubleday");
        assertThat(parsed.getLanguage()).isEqualTo("eng");
    }

    @Test
    @DisplayName("parseOpenLibrarySearchDoc prefers queried ISBN and suppresses aggregate edition fields")
    void parseOpenLibrarySearchDoc_prefersQueriedIsbnAndSuppressesAggregateEditionFields() {
        OpenLibraryBookDataService service = new OpenLibraryBookDataService(
            WebClient.builder(),
            "https://openlibrary.org",
            true
        );

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode doc = mapper.createObjectNode();
        doc.put("key", "/works/OL3140822W");
        doc.put("title", "To Kill a Mockingbird");
        doc.putArray("author_name").add("Harper Lee");
        doc.put("first_publish_year", 1960);
        doc.put("cover_i", 14351077);
        doc.put("number_of_pages_median", 320);
        doc.putArray("isbn")
            .add("0446310786")
            .add("9780061120084")
            .add("0061120081");
        doc.putArray("publisher").add("Sel Yayıncılık");
        doc.putArray("language").add("tur");

        Book parsed = ReflectionTestUtils.invokeMethod(service, "parseOpenLibrarySearchDoc", doc, "0061120081");

        assertThat(parsed).isNotNull();
        assertThat(parsed.getIsbn13()).isEqualTo("9780061120084");
        assertThat(parsed.getIsbn10()).isEqualTo("0061120081");
        assertThat(parsed.getPublisher()).isNull();
        assertThat(parsed.getLanguage()).isNull();
        assertThat(parsed.getPageCount()).isNull();
        assertThat(parsed.getPublishedDate()).isNull();
        assertThat(parsed.getExternalImageUrl()).isNull();
    }

    @Test
    @DisplayName("mergeIsbnEditionDetails uses Open Library edition metadata for exact ISBN results")
    void mergeIsbnEditionDetails_usesEditionMetadataForExactIsbnResults() {
        OpenLibraryBookDataService service = new OpenLibraryBookDataService(
            WebClient.builder(),
            "https://openlibrary.org",
            true
        );

        Book book = new Book();
        book.setId("OL3140822W");
        book.setTitle("To Kill a Mockingbird");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode edition = root.putObject("ISBN:0061120081");
        ObjectNode details = edition.putObject("details");
        details.put("publish_date", "2006");
        details.put("number_of_pages", 323);
        details.putArray("publishers").add("Harper Perennial Modern Classics");
        details.putArray("languages").addObject().put("key", "/languages/eng");
        details.putArray("covers").add(15162569);
        details.putArray("isbn_10").add("0061120081");
        details.putArray("isbn_13").add("9780061120084");

        Book merged = ReflectionTestUtils.invokeMethod(
            service,
            "mergeIsbnEditionDetails",
            book,
            root,
            "ISBN:0061120081",
            "9780061120084"
        );

        assertThat(merged).isNotNull();
        assertThat(merged.getPublisher()).isEqualTo("Harper Perennial Modern Classics");
        assertThat(merged.getLanguage()).isEqualTo("eng");
        assertThat(merged.getPageCount()).isEqualTo(323);
        assertThat(DateParsingUtils.formatIsoDate(merged.getPublishedDate())).isEqualTo("2006-01-01");
        assertThat(merged.getExternalImageUrl()).isEqualTo("https://covers.openlibrary.org/b/id/15162569-L.jpg");
        assertThat(merged.getCoverImages()).isNotNull();
        assertThat(merged.getCoverImages().getPreferredUrl()).isEqualTo("https://covers.openlibrary.org/b/id/15162569-L.jpg");
        assertThat(merged.getIsbn13()).isEqualTo("9780061120084");
        assertThat(merged.getIsbn10()).isEqualTo("0061120081");
    }

    @Test
    @DisplayName("mergeWorkDetails replaces short description with full work description")
    void mergeWorkDetails_replacesWithFullDescription() {
        OpenLibraryBookDataService service = new OpenLibraryBookDataService(
            WebClient.builder(),
            "https://openlibrary.org",
            true
        );

        Book book = new Book();
        book.setId("OL77004W");
        book.setDescription("Short first sentence.");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode workNode = mapper.createObjectNode();
        ObjectNode description = workNode.putObject("description");
        description.put("value", "This is the complete work description from Open Library with substantially more detail.");

        Book merged = ReflectionTestUtils.invokeMethod(service, "mergeWorkDetails", book, workNode);

        assertThat(merged).isNotNull();
        assertThat(merged.getDescription())
            .isEqualTo("This is the complete work description from Open Library with substantially more detail.");
    }
}
