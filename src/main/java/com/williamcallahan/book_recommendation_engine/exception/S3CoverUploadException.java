package com.williamcallahan.book_recommendation_engine.exception;

/**
 * Base exception for S3 cover upload failures.
 * Subclasses indicate specific failure types for retry logic and monitoring.
 *
 * @author William Callahan
 */
public abstract class S3CoverUploadException extends RuntimeException {
    private final String bookId;
    private final String imageUrl;
    private final boolean retryable;

    protected S3CoverUploadException(String message, String bookId, String imageUrl,
                                     boolean retryable, Throwable cause) {
        super(message, cause);
        this.bookId = bookId;
        this.imageUrl = imageUrl;
        this.retryable = retryable;
    }

    public String getBookId() {
        return bookId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
