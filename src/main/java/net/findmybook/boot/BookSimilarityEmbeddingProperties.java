package net.findmybook.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime controls for lightweight book similarity embedding refresh.
 */
@Component
@ConfigurationProperties(prefix = "app.similarity.embeddings")
public class BookSimilarityEmbeddingProperties {

    private boolean enabled = true;
    private int refreshBatchSize = 25;
    private int schedulerEnqueueLimit = 25;
    private int schedulerMaxPending = 100;

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
}
