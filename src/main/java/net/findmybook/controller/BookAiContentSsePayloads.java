package net.findmybook.controller;

import java.util.UUID;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;

sealed interface BookAiContentSsePayload
    permits QueuePositionPayload, QueueStartedPayload, MessageStartPayload,
            MessageDeltaPayload, MessageDonePayload, DonePayload, ErrorPayload {
}

record AiErrorDescriptor(AiErrorCode code, String message) {
}

enum AiErrorCode {
    IDENTIFIER_REQUIRED("identifier_required", "Book identifier is required", false),
    BOOK_NOT_FOUND("book_not_found", "Book not found", false),
    SERVICE_UNAVAILABLE("service_unavailable", "AI content service is unavailable", false),
    QUEUE_BUSY("queue_busy", "AI queue is currently busy; please try again in a moment", true),
    STREAM_TIMEOUT("stream_timeout", "AI generation timed out", true),
    EMPTY_GENERATION("empty_generation", "AI generation returned no data", true),
    CACHE_SERIALIZATION_FAILED("cache_serialization_failed", "Cached AI content is unavailable", true),
    DESCRIPTION_TOO_SHORT("description_too_short", "AI content is unavailable for this book", false),
    GENERATION_FAILED("generation_failed", "AI generation failed", true);

    private final String wireValue;
    private final String defaultMessage;
    private final boolean retryable;

    AiErrorCode(String wireValue, String defaultMessage, boolean retryable) {
        this.wireValue = wireValue;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    String wireValue() {
        return wireValue;
    }

    String defaultMessage() {
        return defaultMessage;
    }

    boolean retryable() {
        return retryable;
    }
}

record OptionalResolution(UUID bookId, AiErrorCode errorCode, String error) {
}

record QueueStatsPayload(int running, int pending, int maxParallel, boolean available, String environmentMode) {
}

record QueuePositionPayload(Integer position, int running, int pending, int maxParallel)
    implements BookAiContentSsePayload {
}

record QueueStartedPayload(int running, int pending, int maxParallel, long queueWaitMs)
    implements BookAiContentSsePayload {
}

record MessageStartPayload(String id, String model, String apiMode)
    implements BookAiContentSsePayload {
}

record MessageDeltaPayload(String delta) implements BookAiContentSsePayload {
}

record MessageDonePayload(String message) implements BookAiContentSsePayload {
}

record DonePayload(String message, BookAiContentSnapshotDto aiContent) implements BookAiContentSsePayload {
}

record ErrorPayload(String error, String code, boolean retryable) implements BookAiContentSsePayload {
}
