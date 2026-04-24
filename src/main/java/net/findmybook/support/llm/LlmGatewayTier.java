package net.findmybook.support.llm;

/**
 * LLM-gateway priority class applied to outbound model calls via the {@code X-Tier} header.
 *
 * <p>The upstream gateway at {@code api.llm-gateway.iocloudhost.net} reserves separate queues
 * and concurrency budgets per tier. Live, user-facing render paths must select
 * {@link #LIVE_RENDER} so they land in a reserved-concurrency queue with a short timeout;
 * scheduler, backfill, and background queue work must select {@link #BACKGROUND_BATCH} so it
 * occupies the long-queue batch pool without competing with user requests. This enum applies to
 * every outbound gateway call — embeddings, chat completions, responses — so one header token
 * governs routing uniformly.</p>
 */
public enum LlmGatewayTier {

    /**
     * Live, user-facing render path. Maps to gateway tier {@code production-z} (priority 4,
     * reserved concurrency 1, queue depth 20, queue timeout 30s).
     */
    LIVE_RENDER("production-z"),

    /**
     * Non-urgent background work (scheduler, demand queue, upsert event, backfill, ingestion
     * coordinator). Maps to gateway tier {@code batch} (priority 9, reserved concurrency 0,
     * queue depth 50, queue timeout 600s).
     */
    BACKGROUND_BATCH("batch");

    /** HTTP header name the gateway expects on outbound calls. */
    public static final String HEADER_NAME = "X-Tier";

    private final String headerValue;

    LlmGatewayTier(String headerValue) {
        this.headerValue = headerValue;
    }

    /**
     * Returns the literal value sent in the {@code X-Tier} header.
     *
     * @return gateway tier token
     */
    public String headerValue() {
        return headerValue;
    }
}
