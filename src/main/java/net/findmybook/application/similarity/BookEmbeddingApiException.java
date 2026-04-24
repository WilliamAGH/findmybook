package net.findmybook.application.similarity;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;

/**
 * Thrown when an OpenAI-compatible embedding API call fails with a typed
 * HTTP response or network error.
 *
 * <p>Carries the SDK exception as cause and exposes the HTTP status code
 * (when available) so callers can distinguish recoverable transport failures
 * (5xx, network) from permanent client errors (400, 422) without re-parsing
 * the underlying SDK exception hierarchy.</p>
 */
public class BookEmbeddingApiException extends RuntimeException {

    private final int statusCode;

    /**
     * Wraps an OpenAI SDK exception with model-scoped context.
     *
     * @param message operator-facing description including model and detail
     * @param cause   original SDK exception
     */
    public BookEmbeddingApiException(String message, OpenAIException cause) {
        super(message, cause);
        this.statusCode = cause instanceof OpenAIServiceException serviceException
            ? serviceException.statusCode()
            : 0;
    }

    /**
     * Returns the HTTP status code from the SDK response, or 0 when the failure
     * was a network/transport error without an HTTP response.
     */
    public int statusCode() {
        return statusCode;
    }
}
