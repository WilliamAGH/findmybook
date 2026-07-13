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
    LIVE_RENDER("production-z", 105L, 8192L, 2),

    /**
     * Non-urgent background work (scheduler, demand queue, upsert event, backfill, ingestion
     * coordinator). Maps to gateway tier {@code batch} (priority 9, reserved concurrency 0,
     * queue depth 50, queue timeout 600s).
     */
    BACKGROUND_BATCH("batch", 720L, 8192L, 3);

    /** HTTP header name the gateway expects on outbound calls. */
    public static final String HEADER_NAME = "X-Tier";

    private final String headerValue;
    private final long callTimeoutSeconds;
    private final long maxCompletionTokens;
    private final int maxGenerationAttempts;

    LlmGatewayTier(
        String headerValue,
        long callTimeoutSeconds,
        long maxCompletionTokens,
        int maxGenerationAttempts
    ) {
        this.headerValue = headerValue;
        this.callTimeoutSeconds = callTimeoutSeconds;
        this.maxCompletionTokens = maxCompletionTokens;
        this.maxGenerationAttempts = maxGenerationAttempts;
    }

    /**
     * Returns the literal value sent in the {@code X-Tier} header.
     *
     * @return gateway tier token
     */
    public String headerValue() {
        return headerValue;
    }

    /**
     * Returns the per-call timeout budget for this tier's queue admission plus one model
     * completion. Live callers cap attempts to this budget; background callers extend shorter
     * provider defaults to it.
     *
     * @return end-to-end timeout budget in seconds
     */
    public long callTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    /**
     * Returns the completion budget for Gemma inference through this gateway tier. The budget
     * includes provider-side reasoning tokens, so it intentionally exceeds the small JSON payload
     * rendered to callers. Consumers must still require an explicit {@code stop} finish reason.
     *
     * @return maximum completion tokens accepted from the model
     */
    public long maxCompletionTokens() {
        return maxCompletionTokens;
    }

    /**
     * Returns the application-level generation-attempt budget for this tier. The same value owns
     * retry behavior and any outer deadline derived from worst-case model-call duration.
     *
     * @return maximum application-level generation attempts
     */
    public int maxGenerationAttempts() {
        return maxGenerationAttempts;
    }
}
