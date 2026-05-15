package net.findmybook.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime controls for lightweight book similarity embedding refresh.
 */
@Component
@ConfigurationProperties(prefix = "app.similarity.embeddings")
public class BookSimilarityEmbeddingProperties {

    public static final int MAX_INPUT_TOKEN_COMFORT_LIMIT = 8_192;
    private static final int DEFAULT_MAX_SECTION_TEXT_CHARS = 15_000;
    private static final int DEFAULT_INPUT_TOKEN_COMFORT_LIMIT = MAX_INPUT_TOKEN_COMFORT_LIMIT;
    private static final int DEFAULT_REQUEST_INPUT_BATCH_SIZE = 32;
    private static final String CONTRACT_VERSION = "v1";

    private boolean enabled = true;
    private int refreshBatchSize = 25;
    private int schedulerEnqueueLimit = 25;
    private int schedulerMaxPending = 100;
    private int maxSectionTextChars = DEFAULT_MAX_SECTION_TEXT_CHARS;
    private int inputTokenComfortLimit = DEFAULT_INPUT_TOKEN_COMFORT_LIMIT;
    private int requestInputBatchSize = DEFAULT_REQUEST_INPUT_BATCH_SIZE;

    /**
     * Indicates whether scheduled background refresh is enabled.
     *
     * @return true when the scheduler should enqueue missing or stale embeddings
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Binds the scheduled refresh flag.
     *
     * @param enabled true to enable scheduled refresh
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns how many books one scheduler pass may inspect.
     *
     * @return bounded database candidate batch size
     */
    public int refreshBatchSize() {
        return Math.max(1, refreshBatchSize);
    }

    /**
     * Binds the scheduler database candidate batch size.
     *
     * @param refreshBatchSize candidate rows to inspect
     */
    public void setRefreshBatchSize(int refreshBatchSize) {
        this.refreshBatchSize = Math.max(1, refreshBatchSize);
    }

    /**
     * Returns how many background tasks one scheduler pass may enqueue.
     *
     * @return bounded enqueue limit
     */
    public int schedulerEnqueueLimit() {
        return Math.max(1, schedulerEnqueueLimit);
    }

    /**
     * Binds the scheduler enqueue limit.
     *
     * @param schedulerEnqueueLimit maximum background queue submissions
     */
    public void setSchedulerEnqueueLimit(int schedulerEnqueueLimit) {
        this.schedulerEnqueueLimit = Math.max(1, schedulerEnqueueLimit);
    }

    /**
     * Returns the maximum central AI queue pending depth allowed before the scheduler pauses.
     *
     * @return pending task ceiling for scheduled embedding refresh
     */
    public int schedulerMaxPending() {
        return Math.max(1, schedulerMaxPending);
    }

    /**
     * Binds the scheduler pending queue ceiling.
     *
     * @param schedulerMaxPending maximum pending central AI queue tasks before pausing
     */
    public void setSchedulerMaxPending(int schedulerMaxPending) {
        this.schedulerMaxPending = Math.max(1, schedulerMaxPending);
    }

    /**
     * Returns the maximum character length for each embedding section input.
     *
     * <p>When enabled, longer rendered section text is truncated before hashing
     * to bound the persisted source contract. Provider token-window safety is
     * handled later by the embedding client, which splits oversized embedding
     * inputs before outbound requests.</p>
     *
     * @return per-section character ceiling; values less than one disable truncation
     */
    public int maxSectionTextChars() {
        return maxSectionTextChars;
    }

    /**
     * Binds the per-section character ceiling.
     *
     * @param maxSectionTextChars configured value; values less than one disable truncation
     */
    public void setMaxSectionTextChars(int maxSectionTextChars) {
        this.maxSectionTextChars = maxSectionTextChars;
    }

    /**
     * Returns the source-text contract segment for vector freshness and reads.
     *
     * @return stable contract identity for the effective section source-text limit
     */
    public String sourceTextContract() {
        if (maxSectionTextChars <= 0) {
            return "source_full_" + CONTRACT_VERSION;
        }
        return "source_chars_" + maxSectionTextChars + "_" + CONTRACT_VERSION;
    }

    /**
     * Returns the conservative per-input token budget used before embedding calls.
     *
     * <p>The client estimates tokens by UTF-8 byte length, which intentionally
     * overestimates ordinary English text and keeps each array element well below
     * qwen3-embedding-4b's 32_768-token hard limit.</p>
     *
     * @return per-request-item estimated token budget
     */
    public int inputTokenComfortLimit() {
        return normalizedInputTokenComfortLimit(inputTokenComfortLimit);
    }

    /**
     * Binds the conservative per-input token budget.
     *
     * @param inputTokenComfortLimit maximum estimated tokens per embeddings input item
     */
    public void setInputTokenComfortLimit(int inputTokenComfortLimit) {
        this.inputTokenComfortLimit = Math.max(1, inputTokenComfortLimit);
    }

    /**
     * Returns the embedding input contract segment for section-cache keys.
     *
     * @return stable contract identity for the effective input chunk size
     */
    public String embeddingInputContract() {
        return embeddingInputContract(inputTokenComfortLimit);
    }

    /**
     * Normalizes configured embedding chunk limits to the supported runtime range.
     *
     * @param configuredInputTokenLimit configured estimated token budget
     * @return effective per-input token budget
     */
    public static int normalizedInputTokenComfortLimit(int configuredInputTokenLimit) {
        return Math.min(Math.max(1, configuredInputTokenLimit), MAX_INPUT_TOKEN_COMFORT_LIMIT);
    }

    /**
     * Returns the section-cache contract for a configured embedding chunk limit.
     *
     * @param configuredInputTokenLimit configured estimated token budget
     * @return stable contract identity for the effective input chunk size
     */
    public static String embeddingInputContract(int configuredInputTokenLimit) {
        return "chunked_" + normalizedInputTokenComfortLimit(configuredInputTokenLimit) + "_" + CONTRACT_VERSION;
    }

    /**
     * Returns how many embeddings input items may be sent in one provider request.
     *
     * @return request array size ceiling
     */
    public int requestInputBatchSize() {
        return Math.max(1, requestInputBatchSize);
    }

    /**
     * Binds the embeddings request array size ceiling.
     *
     * @param requestInputBatchSize maximum input items per embeddings API request
     */
    public void setRequestInputBatchSize(int requestInputBatchSize) {
        this.requestInputBatchSize = Math.max(1, requestInputBatchSize);
    }
}
