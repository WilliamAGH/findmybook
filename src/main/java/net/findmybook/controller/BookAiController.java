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
import net.findmybook.application.ai.BookAiAnalysisService;
import net.findmybook.controller.dto.BookAiSnapshotDto;
import net.findmybook.domain.ai.BookAiSnapshot;
import net.findmybook.support.ai.BookAiRequestQueue;
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
 * Streams and exposes queue state for book AI analysis generation.
 *
 * <p>This controller implements cache-first behavior:
 * existing Postgres snapshots are returned immediately unless the caller
 * explicitly requests refresh.</p>
 */
@RestController
@RequestMapping("/api/books")
public class BookAiController {

    private static final Logger log = LoggerFactory.getLogger(BookAiController.class);

    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(4).toMillis();
    private static final long QUEUE_POSITION_TICK_MILLIS = 350L;
    private static final int AUTO_TRIGGER_QUEUE_THRESHOLD = 5;
    private static final int DEFAULT_GENERATION_PRIORITY = 0;

    private final BookAiAnalysisService analysisService;
    private final BookAiRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService queueTickerExecutor;

    /**
     * Creates a controller with queue/state dependencies.
     */
    public BookAiController(BookAiAnalysisService analysisService,
                            BookAiRequestQueue requestQueue,
                            ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.requestQueue = requestQueue;
        this.objectMapper = objectMapper;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("book-ai-queue-ticker");
            thread.setDaemon(true);
            return thread;
        };
        this.queueTickerExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    /**
     * Returns global queue depth for AI generation tasks.
     */
    @GetMapping("/ai/queue")
    public ResponseEntity<QueueStatsPayload> queueStats() {
        BookAiRequestQueue.QueueSnapshot snapshot = requestQueue.snapshot();
        return ResponseEntity.ok(new QueueStatsPayload(snapshot.running(), snapshot.pending(), snapshot.maxParallel()));
    }

    /**
     * Streams AI generation events for a single book.
     *
     * <p>When {@code refresh=false}, cached Postgres content is returned without
     * invoking the upstream model.</p>
     */
    @PostMapping(path = "/{identifier}/ai/analysis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimiter(name = "bookAiAnalysisRateLimiter")
    public SseEmitter streamAnalysis(@PathVariable String identifier,
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
            Optional<BookAiSnapshot> cachedSnapshot = analysisService.findCurrent(bookId);
            if (cachedSnapshot.isPresent()) {
                streamDoneFromCache(emitter, cachedSnapshot.get());
                return emitter;
            }
        }

        if (!analysisService.isAvailable()) {
            emitTerminalError(emitter, "AI analysis service is not configured");
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

    private OptionalResolution resolveBookIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return new OptionalResolution(null, "Book identifier is required");
        }
        return analysisService.resolveBookId(identifier)
            .map(bookId -> new OptionalResolution(bookId, null))
            .orElseGet(() -> new OptionalResolution(null, "Book not found"));
    }

    private void beginQueuedStream(SseEmitter emitter, UUID bookId) {
        long enqueuedAtMs = System.currentTimeMillis();
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        AtomicBoolean messageStarted = new AtomicBoolean(false);

        BookAiRequestQueue.EnqueuedTask<BookAiAnalysisService.GeneratedAnalysis> queuedTask =
            requestQueue.enqueue(DEFAULT_GENERATION_PRIORITY, () -> {
                sendMessageStartEvent(emitter, messageStarted);
                BookAiAnalysisService.GeneratedAnalysis generated = analysisService.generateAndPersist(bookId, delta -> {
                    sendEvent(emitter, "message_delta", new MessageDeltaPayload(delta));
                });
                sendEvent(emitter, "message_done", new MessageDonePayload(generated.rawMessage()));
                return generated;
            });

        BookAiRequestQueue.QueuePosition initialPosition = requestQueue.getPosition(queuedTask.id());
        sendEvent(emitter, "queued", toQueuePositionPayload(initialPosition));

        ScheduledFuture<?> queueTicker = queueTickerExecutor.scheduleAtFixedRate(() -> {
            if (streamClosed.get()) {
                return;
            }
            BookAiRequestQueue.QueuePosition position = requestQueue.getPosition(queuedTask.id());
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

        Runnable cancelPendingIfOpen = () -> {
            if (streamClosed.compareAndSet(false, true)) {
                queueTicker.cancel(true);
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
            BookAiRequestQueue.QueueSnapshot snapshot = requestQueue.snapshot();
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

            BookAiSnapshotDto snapshotDto = BookAiAnalysisService.toDto(result.snapshot());
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
                    analysisService.configuredModel(),
                    analysisService.apiMode()
                )
            );
        }
    }

    private void streamDoneFromCache(SseEmitter emitter, BookAiSnapshot snapshot) {
        try {
            String cachedMessage = objectMapper.writeValueAsString(snapshot.analysis());
            sendEvent(emitter, "done", new DonePayload(cachedMessage, BookAiAnalysisService.toDto(snapshot)));
            safelyComplete(emitter);
        } catch (JacksonException exception) {
            log.error("Failed to serialize cached AI analysis for bookId={}", snapshot.bookId(), exception);
            emitTerminalError(emitter, "Cached AI analysis could not be serialized");
        }
    }

    private QueuePositionPayload toQueuePositionPayload(BookAiRequestQueue.QueuePosition position) {
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

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException ioException) {
                throw new IllegalStateException("SSE send failed for event: " + eventName, ioException);
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

    private record OptionalResolution(UUID bookId, String error) {}
    private record QueueStatsPayload(int running, int pending, int maxParallel) {}
    private record QueuePositionPayload(Integer position, int running, int pending, int maxParallel) {}
    private record QueueStartedPayload(int running, int pending, int maxParallel, long queueWaitMs) {}
    private record MessageStartPayload(String id, String model, String apiMode) {}
    private record MessageDeltaPayload(String delta) {}
    private record MessageDonePayload(String message) {}
    private record DonePayload(String message, BookAiSnapshotDto analysis) {}
    private record ErrorPayload(String error) {}
}
