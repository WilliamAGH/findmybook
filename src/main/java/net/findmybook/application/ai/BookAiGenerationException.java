package net.findmybook.application.ai;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.util.Objects;

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
        DEGENERATE_CONTENT,
        DESCRIPTION_TOO_SHORT,
        ENRICHMENT_FAILED
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
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * Returns the canonical classification for this failure.
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Formats an OpenAI SDK exception into a concise description with HTTP status
     * code and human-readable explanation when available.
     */
    public static String describeApiError(OpenAIException ex) {
        if (ex instanceof OpenAIServiceException serviceException) {
            int status = serviceException.statusCode();
            String explanation = switch (status) {
                case 400 -> "bad request";
                case 401 -> "unauthorized — check API key";
                case 403 -> "access denied";
                case 404 -> "not found — check base URL and model name";
                case 422 -> "unprocessable request";
                case 429 -> "rate limited — too many requests";
                case 500, 502, 503 -> "server error";
                default -> "unexpected status";
            };
            return "HTTP %d %s".formatted(status, explanation);
        }
        if (ex instanceof OpenAIIoException) {
            return "network error: " + ex.getMessage();
        }
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
