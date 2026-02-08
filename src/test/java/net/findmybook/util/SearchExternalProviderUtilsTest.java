package net.findmybook.util;

import net.findmybook.model.Book;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class SearchExternalProviderUtilsTest {

    @Test
    @DisplayName("normalizeGoogleOrderBy keeps supported values and defaults unsupported values to relevance")
    void normalizeGoogleOrderByContracts() {
        assertThat(SearchExternalProviderUtils.normalizeGoogleOrderBy("newest")).isEqualTo("newest");
        assertThat(SearchExternalProviderUtils.normalizeGoogleOrderBy("relevance")).isEqualTo("relevance");
        assertThat(SearchExternalProviderUtils.normalizeGoogleOrderBy("author")).isEqualTo("relevance");
        assertThat(SearchExternalProviderUtils.normalizeGoogleOrderBy(null)).isEqualTo("relevance");
    }

    @Test
    @DisplayName("normalizeExternalQuery removes google-specific field qualifiers")
    void normalizeExternalQueryRemovesGoogleQualifiers() {
        assertThat(SearchExternalProviderUtils.normalizeExternalQuery("intitle: Clean Code inauthor: Martin"))
            .isEqualTo("Clean Code   Martin");
    }

    @Test
    @DisplayName("matchesPublishedYear uses UTC year matching and rejects null published dates")
    void matchesPublishedYearUsesUtcYear() {
        Book book = new Book();
        book.setPublishedDate(Date.from(Instant.parse("2024-01-01T00:00:00Z")));

        assertThat(SearchExternalProviderUtils.matchesPublishedYear(book, 2024)).isTrue();
        assertThat(SearchExternalProviderUtils.matchesPublishedYear(book, 2023)).isFalse();
        assertThat(SearchExternalProviderUtils.matchesPublishedYear(new Book(), 2024)).isFalse();
        assertThat(SearchExternalProviderUtils.matchesPublishedYear(book, null)).isTrue();
    }
}
