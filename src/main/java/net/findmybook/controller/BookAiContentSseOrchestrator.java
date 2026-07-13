package net.findmybook.controller;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages SSE stream lifecycle for book AI content generation requests.
 *
 * <p>Encapsulates queue-position ticking, keepalive emission, emitter wiring,
 * and terminal event delivery. The owning controller delegates SSE transport
 * operations here after resolving book identifiers and cache state.
 */
class BookAiContentSseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BookAiContentSseOrchestrator.class);
    private static final long QUEUE_POSITION_TICK_MILLIS = 350L;
    private static final long KEEPALIVE_INTERVAL_MILLIS = 15_000L;

    /** Owns cancellation of every scheduled task associated with one SSE stream. */
    static final class Timers {
        private final ScheduledFuture<?> queueTicker;
        private final ScheduledFuture<?> keepaliveTicker;
        private final AtomicReference<ScheduledFuture<?>> applicationDeadline = new AtomicReference<>();

        Timers(ScheduledFuture<?> queueTicker, ScheduledFuture<?> keepaliveTicker) {
            this.queueTicker = queueTicker;
            this.keepaliveTicker = keepaliveTicker;
        }

        void replaceDeadline(ScheduledFuture<?> scheduledDeadline) {
            ScheduledFuture<?> previousDeadline = applicationDeadline.getAndSet(scheduledDeadline);
            if (previousDeadline != null) {
                previousDeadline.cancel(false);
            }
        }

        void cancelQueueTicker() {
            queueTicker.cancel(false);
        }

        void cancelAll() {
            queueTicker.cancel(false);
            keepaliveTicker.cancel(false);
            ScheduledFuture<?> deadline = applicationDeadline.get();
            if (deadline != null) {
                deadline.cancel(false);
            }
        }
    }

    private final BookAiContentRequestQueue requestQueue;
    private final ScheduledExecutorService queueTickerExecutor;

    BookAiContentSseOrchestrator(BookAiContentRequestQueue requestQueue,
                                  ScheduledExecutorService queueTickerExecutor) {
        this.requestQueue = requestQueue;
        this.queueTickerExecutor = queueTickerExecutor;
    }

    /**
     * Schedules periodic queue-position SSE events until the stream is closed or the task leaves the queue.
     */
    ScheduledFuture<?> scheduleQueuePositionTicker(SseEmitter emitter, UUID bookId,
                                                    String taskId, AtomicBoolean streamClosed,
                                                    Runnable claimTerminalOwnership) {
        return queueTickerExecutor.scheduleAtFixedRate(() -> {
            if (streamClosed.get()) {
                return;
            }
            BookAiContentRequestQueue.QueuePosition position = requestQueue.getPosition(taskId);
            if (!position.inQueue()) {
                return;
            }
            try {
                sendEvent(emitter, "queue", toQueuePositionPayload(position));
            } catch (IllegalStateException queueDeliveryException) {
                log.warn("Queue position delivery failed for bookId={} taskId={}", bookId, taskId, queueDeliveryException);
                claimTerminalOwnership.run();
            }
        }, QUEUE_POSITION_TICK_MILLIS, QUEUE_POSITION_TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules periodic SSE comment keepalives to prevent proxy/client timeouts.
     */
    ScheduledFuture<?> scheduleKeepaliveTicker(SseEmitter emitter, UUID bookId,
                                                AtomicBoolean streamClosed,
                                                Runnable claimTerminalOwnership) {
        return queueTickerExecutor.scheduleAtFixedRate(() -> {
            if (streamClosed.get()) {
                return;
            }
            try {
                sendSseComment(emitter, "keepalive");
            } catch (IllegalStateException keepaliveException) {
                log.warn("Keepalive delivery failed for bookId={}", bookId, keepaliveException);
                claimTerminalOwnership.run();
            }
        }, KEEPALIVE_INTERVAL_MILLIS, KEEPALIVE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Schedules application-owned timeout handling before the emitter timeout closes the response. */
    ScheduledFuture<?> scheduleApplicationDeadline(Runnable onDeadline, long delayMillis) {
        return queueTickerExecutor.schedule(onDeadline, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Wires completion, timeout, and error callbacks onto the SSE emitter.
     */
    void wireEmitterLifecycle(SseEmitter emitter, UUID bookId, Runnable claimTerminalOwnership) {
        emitter.onCompletion(claimTerminalOwnership);
        emitter.onTimeout(claimTerminalOwnership);
        emitter.onError(error -> {
            log.warn("AI stream failed for bookId={}", bookId, error);
            claimTerminalOwnership.run();
        });
    }

    /** Sends a named SSE event payload through Spring's thread-safe emitter write path. */
    void sendEvent(SseEmitter emitter, String eventName, BookAiContentSsePayload payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException ioException) {
            throw new IllegalStateException("SSE send failed for event: " + eventName, ioException);
        }
    }

    /**
     * Emits a terminal error event and completes the emitter.
     */
    void emitTerminalError(SseEmitter emitter, AiErrorCode code, String safeMessage) {
        try {
            sendEvent(emitter, "error", new ErrorPayload(safeMessage, code.wireValue(), code.retryable()));
        } catch (IllegalStateException errorEventException) {
            log.warn("AI error event delivery failed", errorEventException);
        } finally {
            safelyComplete(emitter);
        }
    }

    /**
     * Completes the emitter, absorbing the expected race when the response is already committed.
     */
    void safelyComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException completionException) {
            log.warn("SSE emitter completion failed (response likely already committed): {}", completionException.getMessage());
        }
    }

    QueuePositionPayload toQueuePositionPayload(BookAiContentRequestQueue.QueuePosition position) {
        return new QueuePositionPayload(
            position.position(),
            position.running(),
            position.pending(),
            position.maxParallel()
        );
    }

    private void sendSseComment(SseEmitter emitter, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (IOException ioException) {
            throw new IllegalStateException("SSE comment send failed", ioException);
        }
    }
}
