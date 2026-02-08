package net.findmybook.support.retry;

/**
 * Signals advisory lock acquisition failures so callers can apply bounded retry.
 */
public class AdvisoryLockAcquisitionException extends IllegalStateException {

    private static final String DEFAULT_MESSAGE = "Unable to acquire advisory lock for book upsert";

    private final long lockKey;

    public AdvisoryLockAcquisitionException(long lockKey, Throwable cause) {
        super(DEFAULT_MESSAGE + " (lockKey=" + lockKey + ")", cause);
        this.lockKey = lockKey;
    }

    public long getLockKey() {
        return lockKey;
    }
}
