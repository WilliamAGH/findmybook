package net.findmybook.support.llm;

/**
 * Stable source and operation labels sent to the LLM gateway's {@code X-User-ID} header.
 *
 * <p>The gateway stores this header as its workload cost dimension. The routing tier remains in
 * {@link LlmGatewayTier#HEADER_NAME}; including the abstract tier suffix here keeps attribution
 * stable when the gateway's concrete queue token changes.</p>
 */
public enum LlmGatewayWorkload {

    /** Chat-completions requests for book AI and SEO generation. */
    CHAT_COMPLETIONS("chat-completions"),

    /** Embeddings requests for book similarity and search vectors. */
    EMBEDDINGS("embeddings");

    /** HTTP header name the gateway expects for workload attribution. */
    public static final String HEADER_NAME = "X-User-ID";

    private static final String SOURCE = "findmybook";

    private final String operation;

    LlmGatewayWorkload(String operation) {
        this.operation = operation;
    }

    /**
     * Returns the source, operation, and abstract tier label for one gateway request.
     *
     * @param tier gateway routing tier used by the request
     * @return stable workload label
     */
    public String headerValue(LlmGatewayTier tier) {
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        return "%s:%s:%s".formatted(SOURCE, operation, tier.workloadValue());
    }
}
