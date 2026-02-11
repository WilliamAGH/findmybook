package net.findmybook.application.seo;

import java.util.Objects;

/**
 * Thrown when SEO metadata generation fails during API calls, parsing,
 * or pre-generation validation.
 *
 * <p>Replaces raw {@link IllegalStateException} usage in SEO generation paths
 * so callers receive a typed contract for catch-block narrowing.</p>
 */
public class BookSeoGenerationException extends RuntimeException {

    /**
     * Canonical failure categories emitted by SEO metadata generation.
     */
    public enum ErrorCode {
        GENERATION_FAILED,
        DESCRIPTION_TOO_SHORT
    }

    private final ErrorCode errorCode;

    public BookSeoGenerationException(String message) {
        this(ErrorCode.GENERATION_FAILED, message, null);
    }

    public BookSeoGenerationException(String message, Throwable cause) {
        this(ErrorCode.GENERATION_FAILED, message, cause);
    }

    public BookSeoGenerationException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BookSeoGenerationException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * Returns the canonical classification for this failure.
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
