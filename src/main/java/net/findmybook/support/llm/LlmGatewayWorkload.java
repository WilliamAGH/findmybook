package net.findmybook.support.llm;

/**
 * Stable source and purpose labels sent to the LLM gateway's {@code X-User-ID} header.
 *
 * <p>The gateway stores this header as its workload cost dimension. The routing tier remains in
 * {@link LlmGatewayTier#HEADER_NAME}; including the abstract tier suffix here keeps attribution
 * stable when the gateway's concrete queue token changes.</p>
 */
public enum LlmGatewayWorkload {

    /** Reader-facing and ingestion-generated book content. */
    BOOK_CONTENT("bookContent"),

    /** Search-engine metadata generation. */
    BOOK_SEO_METADATA("bookSeoMetadata"),

    /** Section vectors for book similarity. */
    BOOK_SIMILARITY_EMBEDDING("bookSimilarityEmbedding");

    /** HTTP header name the gateway expects for workload attribution. */
    public static final String HEADER_NAME = "X-User-ID";

    private static final String SOURCE = "findmybook";

    private final String purpose;

    LlmGatewayWorkload(String purpose) {
        this.purpose = purpose;
    }

    /**
     * Returns the source, purpose, and abstract tier label for one gateway request.
     *
     * @param tier gateway routing tier used by the request
     * @return stable workload label
     */
    public String headerValue(LlmGatewayTier tier) {
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        return "%s:%s:%s".formatted(SOURCE, purpose, tier.workloadValue());
    }
}
