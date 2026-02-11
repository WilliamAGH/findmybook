package net.findmybook.controller;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Streams and exposes queue state for book AI content generation. */
@RestController
@RequestMapping("/api/books")
public class BookAiContentController {
    private static final Logger log = LoggerFactory.getLogger(BookAiContentController.class);
    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(4).toMillis();
    private static final long QUEUE_POSITION_TICK_MILLIS = 350L;
    private static final long KEEPALIVE_INTERVAL_MILLIS = 15_000L;
    private static final int AUTO_TRIGGER_QUEUE_THRESHOLD = 5;
    private static final int DEFAULT_GENERATION_PRIORITY = 0;
    private static final int MIN_QUEUE_TICKER_THREADS = 4;
    private static final int MAX_QUEUE_TICKER_THREADS = 16;
    private static final String PRODUCTION_ENVIRONMENT_MODE = "production";

    private final BookAiContentService aiContentService;
    private final BookAiContentRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService queueTickerExecutor;
    private final String environmentMode;
    private final boolean exposeDetailedErrors;
    /** Creates a controller with queue/state dependencies. */
    public BookAiContentController(BookAiContentService aiContentService,
                                   BookAiContentRequestQueue requestQueue,
                                   ObjectMapper objectMapper,
                                   @Value("${app.environment.mode:production}") String environmentMode) {
        this.aiContentService = aiContentService;
        this.requestQueue = requestQueue;
        this.objectMapper = objectMapper;
        this.environmentMode = normalizeEnvironmentMode(environmentMode);
        this.exposeDetailedErrors = !PRODUCTION_ENVIRONMENT_MODE.equals(this.environmentMode);
        int queueTickerThreads = determineQueueTickerThreadCount();
        ThreadFactory threadFactory = Thread.ofPlatform()
            .name("book-ai-queue-ticker-", 0)
            .daemon(true)
            .factory();
        this.queueTickerExecutor = Executors.newScheduledThreadPool(queueTickerThreads, threadFactory);
    }
    /** Returns global queue depth for AI generation tasks. */
    @GetMapping("/ai/content/queue")
    public ResponseEntity<QueueStatsPayload> queueStats() {
        BookAiContentRequestQueue.QueueSnapshot snapshot = requestQueue.snapshot();
        return ResponseEntity.ok(new QueueStatsPayload(
            snapshot.running(), snapshot.pending(), snapshot.maxParallel(), aiContentService.isAvailable(), environmentMode));
    }
    /** Streams AI generation events for a single book. */
    @PostMapping(path = "/{identifier}/ai/content/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimiter(name = "bookAiContentRateLimiter")
    public SseEmitter streamAiContent(@PathVariable String identifier,
                                      @RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
                                      HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        OptionalResolution resolution = resolveBookIdentifier(identifier);
        if (resolution.bookId() == null) {
            emitTerminalError(emitter, resolution.errorCode(), resolution.error());
            return emitter;
        }

        UUID bookId = resolution.bookId();
        if (!refresh) {
            Optional<BookAiContentSnapshot> cachedSnapshot = aiContentService.findCurrent(bookId);
            if (cachedSnapshot.isPresent()) {
                streamDoneFromCache(emitter, cachedSnapshot.get());
                return emitter;
            }
        }
        if (!aiContentService.isAvailable()) {
            emitTerminalError(emitter, AiErrorCode.SERVICE_UNAVAILABLE, "AI content service is not configured");
            return emitter;
        }
        if (requestQueue.snapshot().pending() > AUTO_TRIGGER_QUEUE_THRESHOLD) {
            emitTerminalError(emitter, AiErrorCode.QUEUE_BUSY, "AI queue is currently busy; please try again in a moment");
            return emitter;
        }
        beginQueuedStream(emitter, bookId);
        return emitter;
    }

    @PreDestroy
    void shutdownTickerExecutor() {
        queueTickerExecutor.shutdownNow();
    }

    static int determineQueueTickerThreadCount() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int boundedByMax = Math.min(MAX_QUEUE_TICKER_THREADS, availableProcessors);
        return Math.max(MIN_QUEUE_TICKER_THREADS, boundedByMax);
    }

    private OptionalResolution resolveBookIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return new OptionalResolution(null, AiErrorCode.IDENTIFIER_REQUIRED, "Book identifier is required");
        }
        return aiContentService.resolveBookId(identifier)
            .map(bookId -> new OptionalResolution(bookId, null, null))
            .orElseGet(() -> new OptionalResolution(null, AiErrorCode.BOOK_NOT_FOUND, "Book not found"));
    }

    /** Immutable context shared across all phases of a single queued SSE stream. */
    private record QueuedStreamState(SseEmitter emitter, UUID bookId, AtomicBoolean streamClosed) {}

    private void beginQueuedStream(SseEmitter emitter, UUID bookId) {
        long enqueuedAtMs = System.currentTimeMillis();
        QueuedStreamState state = new QueuedStreamState(emitter, bookId, new AtomicBoolean(false));
        var queuedTask = enqueueGenerationTask(state);
        sendEvent(emitter, "queued", toQueuePositionPayload(requestQueue.getPosition(queuedTask.id())));
        ScheduledFuture<?> queueTicker = scheduleQueuePositionTicker(state, queuedTask.id());
        ScheduledFuture<?> keepaliveTicker = scheduleKeepaliveTicker(state, queuedTask.id());
        Runnable cancelPendingIfOpen = () -> {
            if (state.streamClosed().compareAndSet(false, true)) {
                queueTicker.cancel(true);
                keepaliveTicker.cancel(true);
                requestQueue.cancelPending(queuedTask.id());
            }
        };
        wireEmitterLifecycle(state, cancelPendingIfOpen);
        wireStartedHandler(state, queuedTask, enqueuedAtMs);
        wireResultHandler(state, queuedTask, queueTicker, keepaliveTicker);
    }

    private BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> enqueueGenerationTask(
            QueuedStreamState state) {
        AtomicBoolean messageStarted = new AtomicBoolean(false);
        return requestQueue.enqueue(DEFAULT_GENERATION_PRIORITY, () -> {
            sendMessageStartEvent(state.emitter(), messageStarted);
            BookAiContentService.GeneratedContent generated = aiContentService.generateAndPersist(state.bookId(), delta -> {
                sendEvent(state.emitter(), "message_delta", new MessageDeltaPayload(delta));
            });
            sendEvent(state.emitter(), "message_done", new MessageDonePayload(generated.rawMessage()));
            return generated;
        });
    }

    private ScheduledFuture<?> scheduleQueuePositionTicker(QueuedStreamState state, String taskId) {
        return queueTickerExecutor.scheduleAtFixedRate(() -> {
            if (state.streamClosed().get()) {
                return;
            }
            BookAiContentRequestQueue.QueuePosition position = requestQueue.getPosition(taskId);
            if (!position.inQueue()) {
                return;
            }
            try {
                sendEvent(state.emitter(), "queue", toQueuePositionPayload(position));
            } catch (IllegalStateException queueDeliveryException) {
                log.warn("Queue position delivery failed for bookId={} taskId={}", state.bookId(), taskId, queueDeliveryException);
                state.streamClosed().set(true);
                requestQueue.cancelPending(taskId);
            }
        }, QUEUE_POSITION_TICK_MILLIS, QUEUE_POSITION_TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private ScheduledFuture<?> scheduleKeepaliveTicker(QueuedStreamState state, String taskId) {
        return queueTickerExecutor.scheduleAtFixedRate(() -> {
            if (state.streamClosed().get()) {
                return;
            }
            try {
                sendSseComment(state.emitter(), "keepalive");
            } catch (IllegalStateException keepaliveException) {
                log.warn("Keepalive delivery failed for bookId={}", state.bookId(), keepaliveException);
                state.streamClosed().set(true);
                requestQueue.cancelPending(taskId);
            }
        }, KEEPALIVE_INTERVAL_MILLIS, KEEPALIVE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void wireEmitterLifecycle(QueuedStreamState state, Runnable cancelPendingIfOpen) {
        state.emitter().onCompletion(cancelPendingIfOpen);
        state.emitter().onTimeout(() -> {
            cancelPendingIfOpen.run();
            emitTerminalError(state.emitter(), AiErrorCode.STREAM_TIMEOUT, "AI stream timed out");
        });
        state.emitter().onError(error -> {
            log.warn("AI stream failed for bookId={}", state.bookId(), error);
            cancelPendingIfOpen.run();
        });
    }

    private void wireStartedHandler(QueuedStreamState state,
                                     BookAiContentRequestQueue.EnqueuedTask<?> queuedTask, long enqueuedAtMs) {
        queuedTask.started().thenRun(() -> {
            if (state.streamClosed().get()) {
                return;
            }
            long queueWaitMs = Math.max(0L, System.currentTimeMillis() - enqueuedAtMs);
            BookAiContentRequestQueue.QueueSnapshot snapshot = requestQueue.snapshot();
            sendEvent(state.emitter(), "started", new QueueStartedPayload(
                snapshot.running(), snapshot.pending(), snapshot.maxParallel(), queueWaitMs));
        }).exceptionally(throwable -> {
            if (!state.streamClosed().get()) {
                emitTerminalError(state.emitter(), resolveThrowableError(throwable));
            }
            return null;
        });
    }

    private void wireResultHandler(QueuedStreamState state,
                                    BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> queuedTask,
                                    ScheduledFuture<?> queueTicker, ScheduledFuture<?> keepaliveTicker) {
        queuedTask.result().whenComplete((result, throwable) -> {
            queueTicker.cancel(true);
            keepaliveTicker.cancel(true);
            if (!state.streamClosed().compareAndSet(false, true)) {
                return;
            }
            if (throwable != null) {
                emitTerminalError(state.emitter(), resolveThrowableError(throwable));
                return;
            }
            if (result == null) {
                emitTerminalError(state.emitter(), AiErrorCode.EMPTY_GENERATION, "AI generation returned no data");
                return;
            }
            BookAiContentSnapshotDto snapshotDto = BookAiContentSnapshotDto.fromSnapshot(result.snapshot());
            try {
                sendEvent(state.emitter(), "done", new DonePayload(result.rawMessage(), snapshotDto));
                safelyComplete(state.emitter());
            } catch (IllegalStateException doneDeliveryException) {
                log.warn("AI done event delivery failed for bookId={}", state.bookId(), doneDeliveryException);
                emitTerminalError(state.emitter(), AiErrorCode.GENERATION_FAILED,
                    "AI content was generated but could not be delivered");
            }
        });
    }

    private void sendMessageStartEvent(SseEmitter emitter, AtomicBoolean messageStarted) {
        if (messageStarted.compareAndSet(false, true)) {
            sendEvent(emitter, "message_start", new MessageStartPayload(
                UUID.randomUUID().toString(), aiContentService.configuredModel(), aiContentService.apiMode()));
        }
    }

    private void streamDoneFromCache(SseEmitter emitter, BookAiContentSnapshot snapshot) {
        try {
            String cachedMessage = objectMapper.writeValueAsString(snapshot.aiContent());
            sendEvent(emitter, "done", new DonePayload(cachedMessage, BookAiContentSnapshotDto.fromSnapshot(snapshot)));
            safelyComplete(emitter);
        } catch (JacksonException exception) {
            log.error("Failed to serialize cached AI content for bookId={}", snapshot.bookId(), exception);
            emitTerminalError(emitter, AiErrorCode.CACHE_SERIALIZATION_FAILED, "Cached AI content could not be serialized");
        }
    }

    private QueuePositionPayload toQueuePositionPayload(BookAiContentRequestQueue.QueuePosition position) {
        return new QueuePositionPayload(
            position.position(),
            position.running(),
            position.pending(),
            position.maxParallel()
        );
    }

    private void emitTerminalError(SseEmitter emitter, AiErrorDescriptor descriptor) {
        emitTerminalError(emitter, descriptor.code(), descriptor.message());
    }

    private void emitTerminalError(SseEmitter emitter, AiErrorCode code, String message) {
        String safeMessage = resolveClientMessage(code, message);
        try {
            sendEvent(emitter, "error", new ErrorPayload(safeMessage, code.wireValue(), code.retryable()));
        } catch (IllegalStateException errorEventException) {
            log.warn("AI error event delivery failed", errorEventException);
        } finally {
            safelyComplete(emitter);
        }
    }

    private void safelyComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException completionException) {
            // SseEmitter.complete() throws when the servlet response is already
            // committed (client disconnect detected by the container). This is a
            // framework-level race condition, not a domain error we can prevent.
            log.warn("SSE emitter completion failed (response likely already committed): {}", completionException.getMessage());
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, BookAiContentSsePayload payload) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException ioException) {
                throw new IllegalStateException("SSE send failed for event: " + eventName, ioException);
            }
        }
    }

    private void sendSseComment(SseEmitter emitter, String comment) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().comment(comment));
            } catch (IOException ioException) {
                throw new IllegalStateException("SSE comment send failed", ioException);
            }
        }
    }

    private String resolveClientMessage(AiErrorCode code, String message) {
        if (exposeDetailedErrors && message != null && !message.isBlank()) {
            return message;
        }
        return code.defaultMessage();
    }

    private AiErrorDescriptor resolveThrowableError(Throwable throwable) {
        Throwable current = unwrapCompletionException(throwable);
        if (current instanceof BookAiGenerationException generationException) {
            AiErrorCode code = switch (generationException.errorCode()) {
                case DESCRIPTION_TOO_SHORT -> AiErrorCode.DESCRIPTION_TOO_SHORT;
                case ENRICHMENT_FAILED -> AiErrorCode.ENRICHMENT_FAILED;
                case GENERATION_FAILED -> AiErrorCode.GENERATION_FAILED;
            };
            return new AiErrorDescriptor(code, safeThrowableMessage(current));
        }
        return new AiErrorDescriptor(AiErrorCode.GENERATION_FAILED, safeThrowableMessage(current));
    }

    private Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException completionException && completionException.getCause() != null) {
            current = completionException.getCause();
        }
        return current;
    }

    private String safeThrowableMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return AiErrorCode.GENERATION_FAILED.defaultMessage();
        }
        return throwable.getMessage();
    }

    private String normalizeEnvironmentMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return PRODUCTION_ENVIRONMENT_MODE;
        }
        return rawMode.trim().toLowerCase();
    }

}
