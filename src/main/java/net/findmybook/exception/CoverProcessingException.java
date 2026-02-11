package net.findmybook.exception;

import jakarta.annotation.Nullable;
import net.findmybook.model.image.CoverRejectionReason;

/**
 * Image processing failed (corrupt image, unsupported format, etc.).
 * RETRYABLE: No (same image will fail again)
 *
 * <p>When {@link #rejectionReason} is non-null the failure means "no usable cover
 * exists for this source" — callers should skip retry/backoff and move to the
 * next source.  A null reason indicates an infrastructure error (IOException,
 * codec failure) that may or may not be source-specific.</p>
 *
 * @author William Callahan
 */
public class CoverProcessingException extends S3CoverUploadException {

    @Nullable
    private final CoverRejectionReason rejectionReason;

    /** Creates a processing exception with a typed cover-rejection reason. */
    public CoverProcessingException(String bookId, String imageUrl, CoverRejectionReason reason, String detail) {
        super("Failed to process cover image for book " + bookId + ": " + detail,
              bookId, imageUrl, false, null);
        this.rejectionReason = reason;
    }

    /** Creates a processing exception for infrastructure errors with no typed rejection. */
    public CoverProcessingException(String bookId, String imageUrl, String reason) {
        super("Failed to process cover image for book " + bookId + ": " + reason,
              bookId, imageUrl, false, null);
        this.rejectionReason = null;
    }

    /** Returns the typed rejection reason, or null for infrastructure errors. */
    @Nullable
    public CoverRejectionReason getRejectionReason() {
        return rejectionReason;
    }

    /** Whether this failure indicates no usable cover exists (vs. an infrastructure error). */
    public boolean isNoCoverAvailable() {
        return rejectionReason != null;
    }
}
