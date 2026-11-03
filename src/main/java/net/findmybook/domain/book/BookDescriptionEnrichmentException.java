package net.findmybook.domain.book;

/**
 * Identifies an unavailable external book source without classifying canonical
 * persistence or programming failures as expected enrichment failures.
 */
public final class BookDescriptionEnrichmentException extends IllegalStateException {

    /**
     * Records a provider refusal that has no underlying exception.
     *
     * @param message provider-specific reason the book source is unavailable
     */
    public BookDescriptionEnrichmentException(String message) {
        super(message);
    }

    /**
     * Preserves the underlying provider failure for diagnostics and retry policy.
     *
     * @param message provider-specific failure context
     * @param cause original provider failure
     */
    public BookDescriptionEnrichmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
