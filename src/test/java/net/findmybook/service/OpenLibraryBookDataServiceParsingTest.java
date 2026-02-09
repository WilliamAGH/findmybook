package net.findmybook.service;

import net.findmybook.model.Book;
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
