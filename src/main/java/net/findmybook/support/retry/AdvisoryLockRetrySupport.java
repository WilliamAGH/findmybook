package net.findmybook.support.retry;

import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Executes advisory-lock-sensitive operations with bounded retry and linear backoff.
 */
public final class AdvisoryLockRetrySupport {

    private AdvisoryLockRetrySupport() {
    }

    public static <T> T execute(Logger logger,
                                String operationLabel,
                                int maxAttempts,
                                long baseBackoffMillis,
                                Supplier<T> action) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                "maxAttempts must be at least 1 for operation '" + operationLabel + "' but was " + maxAttempts
            );
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (AdvisoryLockAcquisitionException exception) {
                if (attempt == maxAttempts) {
                    throw exception;
                }
                long backoff = Math.max(baseBackoffMillis, 1L) * attempt;
                logger.warn(
                    "Advisory lock contention during {} (attempt {}/{}). Retrying in {}ms",
                    operationLabel,
                    attempt,
                    maxAttempts,
                    backoff
                );
                sleepUnchecked(backoff);
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            }
        }
        throw new IllegalStateException("Unreachable advisory lock retry path");
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
