package net.findmybook.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookTest {

    @Test
    void setS3ImagePathAssignsS3ForAmazonHost() {
        Book book = new Book();

        String s3Url = "https://s3.amazonaws.com/example-bucket/covers/book.jpg";
        book.setS3ImagePath(s3Url);

        assertThat(book.getS3ImagePath()).isEqualTo(s3Url);
        assertThat(book.getExternalImageUrl()).isNull();
    }

    @Test
    void setS3ImagePathAssignsS3ForSpacesHost() {
        Book book = new Book();

        String spacesUrl = "https://nyc3.digitaloceanspaces.com/example/covers/book.jpg";
        book.setS3ImagePath(spacesUrl);

        assertThat(book.getS3ImagePath()).isEqualTo(spacesUrl);
        assertThat(book.getExternalImageUrl()).isNull();
    }

    @Test
    void setExternalImageUrlAssignsExternalForHttpAndHttps() {
        Book book = new Book();

        String httpUrl = "http://images.example.com/cover.jpg";
        book.setExternalImageUrl(httpUrl);

        assertThat(book.getExternalImageUrl()).isEqualTo(httpUrl);
        assertThat(book.getS3ImagePath()).isNull();

        String httpsUrl = "https://images.example.com/cover.jpg";
        book.setExternalImageUrl(httpsUrl);

        assertThat(book.getExternalImageUrl()).isEqualTo(httpsUrl);
        assertThat(book.getS3ImagePath()).isNull();
    }

    @Test
    void setExternalImageUrlIgnoresSpoofedQueryParams() {
        Book book = new Book();

        String spoofed = "https://images.example.com/cover.jpg?redirect=https://s3.amazonaws.com/evil";
        book.setExternalImageUrl(spoofed);

        assertThat(book.getExternalImageUrl()).isEqualTo(spoofed);
        assertThat(book.getS3ImagePath()).isNull();
    }

    @Test
    void should_NormalizeAuthors_When_ConstructorReceivesNullOrBlankEntries() {
        List<String> authors = new ArrayList<>(List.of(" Ada Lovelace ", "Grace Hopper"));
        authors.add(null);
        authors.add("   ");

        Book book = new Book("book-id", "Book title", authors, "description", null, null);

        assertThat(book.getAuthors()).containsExactly("Ada Lovelace", "Grace Hopper");
    }

    @Test
    void should_ExcludeMalformedMetadata_When_SettersReceiveNullOrBlankEntries() {
        Book book = new Book();
        List<Book.CollectionAssignment> collections = new ArrayList<>();
        Book.CollectionAssignment collection = new Book.CollectionAssignment();
        collections.add(collection);
        collections.add(null);
        Map<String, Serializable> qualifiers = new HashMap<>();
        qualifiers.put("valid", true);
        qualifiers.put(null, "missing-key");
        qualifiers.put("missing-value", null);
        qualifiers.put("   ", "blank-key");
        List<String> recommendationIds = new ArrayList<>(List.of(" first ", "second"));
        recommendationIds.add(null);
        recommendationIds.add("   ");
        List<Book.Edition> editions = new ArrayList<>();
        Book.Edition edition = new Book.Edition();
        editions.add(edition);
        editions.add(null);
        List<String> categories = new ArrayList<>(List.of(" Fiction ", "History"));
        categories.add(null);
        categories.add("   ");

        book.setCategories(categories);
        book.setCollections(collections);
        book.setQualifiers(qualifiers);
        assertThatThrownBy(() -> book.setQualifiers(Map.of("unsupported", new Thread())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported qualifier metadata type");
        List<Thread> unsupportedNestedValues = new ArrayList<>();
        unsupportedNestedValues.add(new Thread());
        assertThatThrownBy(() -> book.setQualifiers(
            Map.of("unsupportedNested", (Serializable) unsupportedNestedValues)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported nested qualifier metadata type");
        book.addQualifier("also-missing", null);
        book.setCachedRecommendationIds(recommendationIds);
        book.addRecommendationIds(List.of(" third ", "second", "   "));
        book.setOtherEditions(editions);

        assertThat(book.getCategories()).containsExactly("Fiction", "History");
        assertThat(book.getCollections()).containsExactly(collection);
        assertThat(book.getQualifiers()).containsOnly(Map.entry("valid", true));
        assertThat(book.getCachedRecommendationIds()).containsExactly("first", "second", "third");
        assertThat(book.getOtherEditions()).containsExactly(edition);
    }
}
