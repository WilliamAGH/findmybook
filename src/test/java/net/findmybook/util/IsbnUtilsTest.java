package net.findmybook.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsbnUtilsTest {

    @Test
    void sanitizeRemovesWhitespaceAndHyphen() {
        assertThat(IsbnUtils.sanitize("978-1 4028-9462-6")).isEqualTo("9781402894626");
    }

    @Test
    void sanitizeUppercasesCheckDigitX() {
        assertThat(IsbnUtils.sanitize("0-306-40615-x")).isEqualTo("030640615X");
    }

    @Test
    void sanitizeReturnsNullWhenNoIsbnCharactersRemain() {
        assertThat(IsbnUtils.sanitize("abc")).isNull();
    }

    @Test
    void sanitizeReturnsNullForNullInput() {
        assertThat(IsbnUtils.sanitize(null)).isNull();
    }

    @Test
    void should_ConvertIsbn10ToEquivalentIsbn13_When_InputIsSyntacticallyValid() {
        assertThat(IsbnUtils.toIsbn13("0-306-40615-2")).isEqualTo("9780306406157");
    }

    @Test
    void should_ConvertIsbn13ToEquivalentIsbn10_When_InputUsesBookPrefix() {
        assertThat(IsbnUtils.toIsbn10("978-0-306-40615-7")).isEqualTo("0306406152");
    }

    @Test
    void should_ReturnNullIsbn10_When_Isbn13CannotBeRepresentedAsIsbn10() {
        assertThat(IsbnUtils.toIsbn10("9791234567896")).isNull();
    }

    @Test
    void should_ResolveIsbn13Identity_When_InputIsEquivalentIsbn10() {
        assertThat(IsbnUtils.isbn13Identity("0-306-40615-2")).isEqualTo("9780306406157");
    }
}
