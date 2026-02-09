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
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Streams and exposes queue state for book AI content generation.
 *
 * <p>This controller implements cache-first behavior:
 * existing Postgres snapshots are returned immediately unless the caller
 * explicitly requests refresh.</p>
 */
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

    private final BookAiContentService aiContentService;
    private final BookAiContentRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService queueTickerExecutor;

    /**
     * Creates a controller with queue/state dependencies.
     */
    public BookAiContentController(BookAiContentService aiContentService,
                                   BookAiContentRequestQueue requestQueue,
                                   ObjectMapper objectMapper) {
        this.aiContentService = aiContentService;
        this.requestQueue = requestQueue;
        this.objectMapper = objectMapper;
        int queueTickerThreads = determineQueueTickerThreadCount();
        ThreadFactory threadFactory = Thread.ofPlatform()
            .name("book-ai-queue-ticker-", 0)
            .daemon(true)
            .factory();
        this.queueTickerExecutor = Executors.newScheduledThreadPool(queueTickerThreads, threadFactory);
    }

    /**
     * Returns global queue depth for AI generation tasks.
     */
    @GetMapping("/ai/content/queue")
    public ResponseEntity<QueueStatsPayload> queueStats() {
        BookAiContentRequestQueue.QueueSnapshot snapshot = requestQueue.snapshot();
        return ResponseEntity.ok(new QueueStatsPayload(
            snapshot.running(), snapshot.pending(), snapshot.maxParallel(), aiContentService.isAvailable()));
    }

    /**
     * Streams AI generation events for a single book.
     *
     * <p>When {@code refresh=false}, cached Postgres content is returned without
     * invoking the upstream model.</p>
     */
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
            emitTerminalError(emitter, resolution.error());
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
            emitTerminalError(emitter, "AI content service is not configured");
            return emitter;
        }

        if (requestQueue.snapshot().pending() > AUTO_TRIGGER_QUEUE_THRESHOLD) {
            emitTerminalError(emitter, "AI queue is currently busy; please try again in a moment");
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
            return new OptionalResolution(null, "Book identifier is required");
        }
        return aiContentService.resolveBookId(identifier)
            .map(bookId -> new OptionalResolution(bookId, null))
            .orElseGet(() -> new OptionalResolution(null, "Book not found"));
    }

    private void beginQueuedStream(SseEmitter emitter, UUID bookId) {
        long enqueuedAtMs = System.currentTimeMillis();
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        AtomicBoolean messageStarted = new AtomicBoolean(false);

        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> queuedTask =
            requestQueue.enqueue(DEFAULT_GENERATION_PRIORITY, () -> {
                sendMessageStartEvent(emitter, messageStarted);
                BookAiContentService.GeneratedContent generated = aiContentService.generateAndPersist(bookId, delta -> {
                    sendEvent(emitter, "message_delta", new MessageDeltaPayload(delta));
                });
                sendEvent(emitter, "message_done", new MessageDonePayload(generated.rawMessage()));
                return generated;
            });

        BookAiContentRequestQueue.QueuePosition initialPosition = requestQueue.getPosition(queuedTask.id());
        sendEvent(emitter, "queued", toQueuePositionPayload(initialPosition));

        ScheduledFuture<?> queueTicker = queueTickerExecutor.scheduleAtFixedRate(() -> {
            if (streamClosed.get()) {
                return;
            }
            BookAiContentRequestQueue.QueuePosition position = requestQueue.getPosition(queuedTask.id());
            if (!position.inQueue()) {
                return;
            }
            try {
                sendEvent(emitter, "queue", toQueuePositionPayload(position));
            } catch (IllegalStateException queueUpdateException) {
                log.warn(
                    "Queue update delivery failed for bookId={} taskId={}",
                    bookId,
                    queuedTask.id(),
                    queueUpdateException
                );
                requestQueue.cancelPending(queuedTask.id());
            }
        }, QUEUE_POSITION_TICK_MILLIS, QUEUE_POSITION_TICK_MILLIS, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> keepaliveTicker = queueTickerExecutor.scheduleAtFixedRate(() -> {
            if (streamClosed.get()) {
                return;
            }
            try {
                sendSseComment(emitter, "keepalive");
            } catch (IllegalStateException keepaliveException) {
                log.debug("Keepalive delivery failed: {}", keepaliveException.getMessage());
            }
        }, KEEPALIVE_INTERVAL_MILLIS, KEEPALIVE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

        Runnable cancelPendingIfOpen = () -> {
            if (streamClosed.compareAndSet(false, true)) {
                queueTicker.cancel(true);
                keepaliveTicker.cancel(true);
                requestQueue.cancelPending(queuedTask.id());
            }
        };

        emitter.onCompletion(cancelPendingIfOpen);
        emitter.onTimeout(() -> {
            cancelPendingIfOpen.run();
            emitTerminalError(emitter, "AI stream timed out");
        });
        emitter.onError(error -> {
            log.warn("AI stream failed for bookId={}", bookId, error);
            cancelPendingIfOpen.run();
        });

        queuedTask.started().thenRun(() -> {
            if (streamClosed.get()) {
                return;
            }
            long queueWaitMs = Math.max(0L, System.currentTimeMillis() - enqueuedAtMs);
            BookAiContentRequestQueue.QueueSnapshot snapshot = requestQueue.snapshot();
            sendEvent(emitter, "started", new QueueStartedPayload(
                snapshot.running(),
                snapshot.pending(),
                snapshot.maxParallel(),
                queueWaitMs
            ));
        }).exceptionally(throwable -> {
            if (!streamClosed.get()) {
                emitTerminalError(emitter, resolveThrowableMessage(throwable));
            }
            return null;
        });

        queuedTask.result().whenComplete((result, throwable) -> {
            queueTicker.cancel(true);
            keepaliveTicker.cancel(true);
            if (!streamClosed.compareAndSet(false, true)) {
                return;
            }

            if (throwable != null) {
                emitTerminalError(emitter, resolveThrowableMessage(throwable));
                return;
            }

            if (result == null) {
                emitTerminalError(emitter, "AI generation returned no data");
                return;
            }

            BookAiContentSnapshotDto snapshotDto = BookAiContentSnapshotDto.fromSnapshot(result.snapshot());
            try {
                sendEvent(emitter, "done", new DonePayload(result.rawMessage(), snapshotDto));
            } catch (IllegalStateException doneEventException) {
                log.warn("AI done event delivery failed for bookId={}", bookId, doneEventException);
            }
            safelyComplete(emitter);
        });
    }

    private void sendMessageStartEvent(SseEmitter emitter, AtomicBoolean messageStarted) {
        if (messageStarted.compareAndSet(false, true)) {
            sendEvent(
                emitter,
                "message_start",
                new MessageStartPayload(
                    UUID.randomUUID().toString(),
                    aiContentService.configuredModel(),
                    aiContentService.apiMode()
                )
            );
        }
    }

    private void streamDoneFromCache(SseEmitter emitter, BookAiContentSnapshot snapshot) {
        try {
            String cachedMessage = objectMapper.writeValueAsString(snapshot.aiContent());
            sendEvent(emitter, "done", new DonePayload(cachedMessage, BookAiContentSnapshotDto.fromSnapshot(snapshot)));
            safelyComplete(emitter);
        } catch (JacksonException exception) {
            log.error("Failed to serialize cached AI content for bookId={}", snapshot.bookId(), exception);
            emitTerminalError(emitter, "Cached AI content could not be serialized");
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

    private void emitTerminalError(SseEmitter emitter, String message) {
        try {
            sendEvent(emitter, "error", new ErrorPayload(message));
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
            log.debug("SSE emitter was already complete: {}", completionException.getMessage());
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

    private String resolveThrowableMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException completionException && completionException.getCause() != null) {
            current = completionException.getCause();
        }

        if (current == null || current.getMessage() == null || current.getMessage().isBlank()) {
            return "AI generation failed";
        }
        return current.getMessage();
    }

    private sealed interface BookAiContentSsePayload
        permits QueuePositionPayload, QueueStartedPayload, MessageStartPayload,
                MessageDeltaPayload, MessageDonePayload, DonePayload, ErrorPayload {
    }

    private record OptionalResolution(UUID bookId, String error) {}
    private record QueueStatsPayload(int running, int pending, int maxParallel, boolean available) {}
    private record QueuePositionPayload(Integer position, int running, int pending, int maxParallel)
        implements BookAiContentSsePayload {}
    private record QueueStartedPayload(int running, int pending, int maxParallel, long queueWaitMs)
        implements BookAiContentSsePayload {}
    private record MessageStartPayload(String id, String model, String apiMode)
        implements BookAiContentSsePayload {}
    private record MessageDeltaPayload(String delta) implements BookAiContentSsePayload {}
    private record MessageDonePayload(String message) implements BookAiContentSsePayload {}
    private record DonePayload(String message, BookAiContentSnapshotDto aiContent) implements BookAiContentSsePayload {}
    private record ErrorPayload(String error) implements BookAiContentSsePayload {}
}
