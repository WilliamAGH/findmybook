package net.findmybook.domain.similarity;

import java.util.Arrays;

/**
 * Canonical section keys used by book similarity section-fusion embeddings.
 */
public enum BookSimilaritySectionKey {
    IDENTITY("identity"),
    CLASSIFICATION("classification"),
    DESCRIPTION("description"),
    AI_CONTENT("ai_content"),
    BIBLIOGRAPHIC("bibliographic");

    private final String key;

    BookSimilaritySectionKey(String key) {
        this.key = key;
    }

    /**
     * Returns the persisted lower-case key.
     *
     * @return stable database/profile key
     */
    public String key() {
        return key;
    }

    /**
     * Resolves a persisted key into the canonical enum.
     *
     * @param rawKey lower-case persisted section key
     * @return canonical section key
     */
    public static BookSimilaritySectionKey fromKey(String rawKey) {
        return Arrays.stream(values())
            .filter(sectionKey -> sectionKey.key.equals(rawKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown book similarity section key: " + rawKey));
    }
}
