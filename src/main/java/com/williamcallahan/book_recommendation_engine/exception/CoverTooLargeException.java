package com.williamcallahan.book_recommendation_engine.exception;

/**
 * Processed image exceeds size limit (too large for S3 upload).
 * RETRYABLE: No (same image will fail again)
 *
 * @author William Callahan
 */
public class CoverTooLargeException extends S3CoverUploadException {
    private final long actualSize;
    private final long maxSize;

    public CoverTooLargeException(String bookId, String imageUrl, long actualSize, long maxSize) {
        super(String.format("Processed image too large for book %s: %d bytes (max: %d bytes)",
                           bookId, actualSize, maxSize),
              bookId, imageUrl, false, null);
        this.actualSize = actualSize;
        this.maxSize = maxSize;
    }

    public long getActualSize() {
        return actualSize;
    }

    public long getMaxSize() {
        return maxSize;
    }
}
