package com.williamcallahan.book_recommendation_engine.util.cover;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.book_recommendation_engine.model.image.CoverImageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class S3KeyGeneratorTest {

    @Test
    @DisplayName("generateCoverKeyFromRawSource normalizes whitespace and casing")
    void generateCoverKeyFromRawSource_normalizesSpaces() {
        String key = S3KeyGenerator.generateCoverKeyFromRawSource(
            "nNgwEAAAQBAJ",
            ".jpg",
            "GOOGLE_BOOKS 2"
        );

        assertThat(key)
            .isEqualTo("images/book-covers/nNgwEAAAQBAJ-lg-google_books-2.jpg");
    }

    @Test
    @DisplayName("generateCoverKeyFromRawSource slugifies arbitrary labels")
    void generateCoverKeyFromRawSource_slugifiesLabels() {
        String key = S3KeyGenerator.generateCoverKeyFromRawSource(
            "abcd1234",
            "jpg",
            "GoogleHint-AsIs"
        );

        assertThat(key)
            .isEqualTo("images/book-covers/abcd1234-lg-googlehint-asis.jpg");
    }

    @Test
    @DisplayName("generateCoverKeyFromRawSource falls back to unknown for empty input")
    void generateCoverKeyFromRawSource_handlesNull() {
        String key = S3KeyGenerator.generateCoverKeyFromRawSource(
            "abcd",
            null,
            null
        );

        assertThat(key)
            .isEqualTo("images/book-covers/abcd-lg-unknown.jpg");
    }

    @Test
    @DisplayName("generateCoverKey still supports enum-based source")
    void generateCoverKey_existingEnumRemainsStable() {
        String key = S3KeyGenerator.generateCoverKey(
            "abcd",
            ".png",
            CoverImageSource.GOOGLE_BOOKS
        );

        assertThat(key)
            .isEqualTo("images/book-covers/abcd-lg-google-books.png");
    }
}
