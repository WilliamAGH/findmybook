package com.williamcallahan.book_recommendation_engine.exception;

/**
 * URL blocked by SSRF protection (not in allowlist).
 * RETRYABLE: No (same URL will fail again)
 *
 * @author William Callahan
 */
public class UnsafeUrlException extends S3CoverUploadException {
    public UnsafeUrlException(String bookId, String imageUrl) {
        super("Blocked potentially unsafe image URL for book " + bookId + ": " + imageUrl,
              bookId, imageUrl, false, null);
    }
}
