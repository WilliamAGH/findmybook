package net.findmybook.application.ai;

/**
 * Thrown when AI content generation fails during streaming or parsing.
 *
 * <p>Wraps the underlying SDK or parsing exception with book-specific
 * context so callers receive a typed contract instead of raw exceptions.</p>
 */
public class BookAiGenerationException extends RuntimeException {

    public BookAiGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
