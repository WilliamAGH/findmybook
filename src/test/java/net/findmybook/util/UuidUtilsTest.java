package net.findmybook.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidUtilsTest {

    @Test
    void parseUuidOrNull_acceptsStrictUuid() {
        String value = "550e8400-e29b-41d4-a716-446655440000";

        UUID parsed = UuidUtils.parseUuidOrNull(value);

        assertThat(parsed).isNotNull();
        assertThat(parsed.toString()).isEqualTo(value);
        assertThat(UuidUtils.isValidUuid(value)).isTrue();
    }

    @Test
    void parseUuidOrNull_rejectsShortSegments() {
        String malformed = "550e8400-e29b-41d4-a716-44665544000";

        assertThat(UuidUtils.looksLikeUuid(malformed)).isFalse();
        assertThat(UuidUtils.parseUuidOrNull(malformed)).isNull();
        assertThat(UuidUtils.isValidUuid(malformed)).isFalse();
    }

    @Test
    void toUuidIfValid_returnsNullForMalformedValue() {
        String malformed = "550e8400-e29b-41d4-a71-446655440000";

        assertThat(UuidUtils.toUuidIfValid(malformed)).isNull();
    }
}
