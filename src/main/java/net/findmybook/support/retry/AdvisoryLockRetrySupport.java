package net.findmybook.support.retry;

import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Executes advisory-lock-sensitive operations with bounded retry and linear backoff.
 */
public final class AdvisoryLockRetrySupport {

    /**
     * Bundles the retry parameters that are constant per call site:
     * the logger, maximum attempts, and base backoff interval.
     */
    public record RetryConfig(Logger logger, int maxAttempts, long baseBackoffMillis) {

        /** Default retry config used by book upsert operations across the codebase. */
        public static RetryConfig forBookUpsert(Logger logger) {
            return new RetryConfig(logger, 3, 100L);
        }
    }

    private AdvisoryLockRetrySupport() {
    }

    /**
     * Executes the action with advisory-lock retry using the provided config.
     *
     * <p>Retries only on {@link AdvisoryLockAcquisitionException}; all other
     * runtime exceptions propagate immediately. Uses linear backoff
     * ({@code baseBackoffMillis * attempt}).
     */
    public static <T> T execute(RetryConfig config,
                                String operationLabel,
                                Supplier<T> action) {
        int maxAttempts = config.maxAttempts();
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                "maxAttempts must be at least 1 for operation '" + operationLabel + "' but was " + maxAttempts
            );
        }
        AdvisoryLockAcquisitionException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (AdvisoryLockAcquisitionException exception) {
                lastException = exception;
                if (attempt < maxAttempts) {
                    long backoff = Math.max(config.baseBackoffMillis(), 1L) * attempt;
                    config.logger().warn(
                        "Advisory lock contention during {} (attempt {}/{}). Retrying in {}ms",
                        operationLabel,
                        attempt,
                        maxAttempts,
                        backoff
                    );
                    sleepUnchecked(backoff);
                }
            }
        }
        throw lastException;
    }

    private static void sleepUnchecked(long durationMillis) {
        try {
            Thread.sleep(durationMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry advisory lock", interruptedException);
        }
    }
}
