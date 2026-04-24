package net.findmybook.domain.similarity;

/**
 * Rendered source text for one weighted embedding section.
 *
 * @param sectionKey canonical section key
 * @param text stable key-value section text
 * @param inputHash SHA-256 hash of the section text
 */
public record BookSimilaritySectionInput(
    BookSimilaritySectionKey sectionKey,
    String text,
    String inputHash
) {

    /**
     * Validates the section input contract.
     */
    public BookSimilaritySectionInput {
        if (sectionKey == null) {
            throw new IllegalArgumentException("sectionKey is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("section text is required");
        }
        if (inputHash == null || inputHash.isBlank()) {
            throw new IllegalArgumentException("section inputHash is required");
        }
    }
}
