package net.findmybook.exception;

/**
 * Image download from external URL failed (network error, timeout, 404, etc.).
 * RETRYABLE: Yes (transient network issues)
 *
 * @author William Callahan
 */
public class CoverDownloadException extends S3CoverUploadException {
    public CoverDownloadException(String bookId, String imageUrl, Throwable cause) {
        super("Failed to download cover image for book " + bookId + " from " + imageUrl,
              bookId, imageUrl, true, cause);
    }
}
