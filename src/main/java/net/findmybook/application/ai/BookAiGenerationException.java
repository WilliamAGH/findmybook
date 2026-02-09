package net.findmybook.application.ai;

/**
 * Thrown when AI content generation fails during streaming, parsing,
 * or pre-generation validation (e.g. insufficient input data).
 *
 * <p>Wraps the underlying SDK or parsing exception with book-specific
 * context so callers receive a typed contract instead of raw exceptions.</p>
 */
public class BookAiGenerationException extends RuntimeException {

    public BookAiGenerationException(String message) {
        super(message);
    }

    public BookAiGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
