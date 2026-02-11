package net.findmybook.support.ai;

/**
 * Raised when background ingestion enqueues exceed the configured pending cap.
 */
public final class BookAiQueueCapacityExceededException extends IllegalStateException {

    private final int maxPending;
    private final int currentPending;

    public BookAiQueueCapacityExceededException(int maxPending, int currentPending) {
        super("Background queue pending limit reached (pending=%d, max=%d)".formatted(currentPending, maxPending));
        this.maxPending = maxPending;
        this.currentPending = currentPending;
    }

    public int maxPending() {
        return maxPending;
    }

    public int currentPending() {
        return currentPending;
    }
}
