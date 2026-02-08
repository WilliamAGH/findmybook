package net.findmybook.exception;

/**
 * Image processing failed (corrupt image, unsupported format, etc.).
 * RETRYABLE: No (same image will fail again)
 *
 * @author William Callahan
 */
public class CoverProcessingException extends S3CoverUploadException {
    public CoverProcessingException(String bookId, String imageUrl, String reason) {
        super("Failed to process cover image for book " + bookId + ": " + reason,
              bookId, imageUrl, false, null);
    }
}
