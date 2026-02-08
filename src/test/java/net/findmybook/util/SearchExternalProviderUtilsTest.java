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
    @DisplayName("normalizeOpenLibrarySortFacet maps newest and leaves unsupported values empty")
    void normalizeOpenLibrarySortFacetContracts() {
        assertThat(SearchExternalProviderUtils.normalizeOpenLibrarySortFacet("newest")).contains("new");
        assertThat(SearchExternalProviderUtils.normalizeOpenLibrarySortFacet("relevance")).isEmpty();
        assertThat(SearchExternalProviderUtils.normalizeOpenLibrarySortFacet("author")).isEmpty();
        assertThat(SearchExternalProviderUtils.normalizeOpenLibrarySortFacet(null)).isEmpty();
    }

    @Test
    @DisplayName("orderBy validation accepts supported values and rejects removed rating")
    void orderByValidationContracts() {
        assertThat(SearchExternalProviderUtils.isSupportedOrderBy("relevance")).isTrue();
        assertThat(SearchExternalProviderUtils.isSupportedOrderBy("newest")).isTrue();
        assertThat(SearchExternalProviderUtils.isSupportedOrderBy("title")).isTrue();
        assertThat(SearchExternalProviderUtils.isSupportedOrderBy("author")).isTrue();
        assertThat(SearchExternalProviderUtils.isSupportedOrderBy("rating")).isFalse();
    }

    @Test
    @DisplayName("normalizeOrderBy defaults unsupported and missing values to newest")
    void normalizeOrderByContracts() {
        assertThat(SearchExternalProviderUtils.normalizeOrderBy("author")).isEqualTo("author");
        assertThat(SearchExternalProviderUtils.normalizeOrderBy("rating")).isEqualTo("newest");
        assertThat(SearchExternalProviderUtils.normalizeOrderBy(null)).isEqualTo("newest");
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
