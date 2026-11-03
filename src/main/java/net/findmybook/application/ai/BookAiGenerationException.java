package net.findmybook.application.ai;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import net.findmybook.domain.book.BookDescriptionEnrichmentException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.reactive.function.client.WebClientException;

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
        INCOMPLETE_RESPONSE,
        INVALID_RESPONSE,
        DEGENERATE_CONTENT,
        DESCRIPTION_TOO_SHORT,
        ENRICHMENT_FAILED,
        LOCAL_RATE_LIMITED,
        LOCAL_CIRCUIT_OPEN
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
     * Classifies description-enrichment failures consistently for AI and SEO generation,
     * retaining the original failure and its diagnostic context.
     *
     * @param bookId canonical book UUID used in the failure message
     * @param failure failure from the description-enrichment operation
     * @return typed failure with the canonical enrichment error code
     * @throws RuntimeException the original failure when no recognized enrichment failure is present
     */
    public static BookAiGenerationException forDescriptionEnrichmentFailure(UUID bookId, RuntimeException failure) {
        ErrorCode errorCode = classifyDescriptionEnrichmentFailure(failure);
        return new BookAiGenerationException(errorCode,
            "Description enrichment failed for book: " + bookId + " (errorCode=" + errorCode + ")",
            failure);
    }

    private static ErrorCode classifyDescriptionEnrichmentFailure(RuntimeException failure) {
        ArrayDeque<Throwable> remaining = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        remaining.add(Objects.requireNonNull(failure, "failure must not be null"));
        boolean localRateLimited = false;
        boolean localCircuitOpen = false;
        boolean providerFailure = false;
        while (!remaining.isEmpty()) {
            Throwable current = remaining.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof DataAccessException) {
                throw failure;
            }
            if (current instanceof RequestNotPermitted) {
                localRateLimited = true;
            }
            if (current instanceof CallNotPermittedException) {
                localCircuitOpen = true;
            }
            if (current instanceof BookDescriptionEnrichmentException
                || current instanceof TimeoutException
                || current instanceof IOException
                || current instanceof WebClientException) {
                providerFailure = true;
            }
            if (current.getCause() != null) {
                remaining.addLast(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                remaining.addLast(suppressed);
            }
        }
        if (localRateLimited) {
            return ErrorCode.LOCAL_RATE_LIMITED;
        }
        if (localCircuitOpen) {
            return ErrorCode.LOCAL_CIRCUIT_OPEN;
        }
        if (providerFailure) {
            return ErrorCode.ENRICHMENT_FAILED;
        }
        throw failure;
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
