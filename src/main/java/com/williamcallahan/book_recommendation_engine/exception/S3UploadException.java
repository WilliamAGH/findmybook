package com.williamcallahan.book_recommendation_engine.exception;

/**
 * S3 upload operation failed (S3 service error, permissions issue, etc.).
 * RETRYABLE: Yes (transient S3 issues)
 *
 * @author William Callahan
 */
public class S3UploadException extends S3CoverUploadException {
    public S3UploadException(String bookId, String imageUrl, Throwable cause) {
        super("Failed to upload cover to S3 for book " + bookId,
              bookId, imageUrl, true, cause);
    }
}
