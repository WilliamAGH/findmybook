package net.findmybook.model.image;

/**
 * Enumerates reasons an image was rejected during cover validation.
 *
 * <p>All values represent "no usable cover exists for this source" rather than
 * infrastructure errors.  This distinction lets callers skip retry/backoff
 * logic and move to the next cover source immediately.</p>
 */
public enum CoverRejectionReason {

    RAW_BYTES_EMPTY("Raw image bytes null or empty"),
    RESPONSE_TOO_SMALL("Response too small to be a cover image"),
    UNREADABLE_IMAGE("Unsupported or corrupt image format"),
    INVALID_ASPECT_RATIO("Image aspect ratio outside acceptable range for book covers"),
    PLACEHOLDER_TOO_SMALL("Image dimensions too small, likely a placeholder"),
    DOMINANT_WHITE("Image predominantly white, likely not a real cover");

    private final String description;

    CoverRejectionReason(String description) {
        this.description = description;
    }

    /** Human-readable description suitable for log messages. */
    public String description() {
        return description;
    }
}
