package net.findmybook.application.ai;

/**
 * Thrown when AI content generation fails during streaming, parsing,
 * or pre-generation validation (e.g. insufficient input data).
 *
 * <p>Wraps the underlying SDK or parsing exception with book-specific
 * context so callers receive a typed contract instead of raw exceptions.</p>
 */
public class BookAiGenerationException extends RuntimeException {

    /**
     * Canonical failure categories emitted by AI generation.
     */
    public enum ErrorCode {
        GENERATION_FAILED,
        DESCRIPTION_TOO_SHORT
    }

    private final ErrorCode errorCode;

    public BookAiGenerationException(String message) {
        this(ErrorCode.GENERATION_FAILED, message, null);
    }

    public BookAiGenerationException(String message, Throwable cause) {
        this(ErrorCode.GENERATION_FAILED, message, cause);
    }

    public BookAiGenerationException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BookAiGenerationException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? ErrorCode.GENERATION_FAILED : errorCode;
    }

    /**
     * Returns the canonical classification for this failure.
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
