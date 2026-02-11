package net.findmybook.controller;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
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
    private static final int DEFAULT_GENERATION_PRIORITY = 0;
    private static final int MIN_QUEUE_TICKER_THREADS = 4;
    private static final int MAX_QUEUE_TICKER_THREADS = 16;
    private static final String PRODUCTION_ENVIRONMENT_MODE = "production";

    private final BookAiContentService aiContentService;
    private final BookAiContentRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final BookAiContentSseOrchestrator sseOrchestrator;
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
        this.sseOrchestrator = new BookAiContentSseOrchestrator(requestQueue, queueTickerExecutor);
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
            sseOrchestrator.emitTerminalError(emitter, resolution.errorCode(), resolveClientMessage(resolution.errorCode(), resolution.error()));
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
            sseOrchestrator.emitTerminalError(emitter, AiErrorCode.SERVICE_UNAVAILABLE,
                resolveClientMessage(AiErrorCode.SERVICE_UNAVAILABLE, "AI content service is not configured"));
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
        sseOrchestrator.sendEvent(emitter, "queued", sseOrchestrator.toQueuePositionPayload(requestQueue.getPosition(queuedTask.id())));
        ScheduledFuture<?> queueTicker = sseOrchestrator.scheduleQueuePositionTicker(emitter, bookId, queuedTask.id(), state.streamClosed());
        ScheduledFuture<?> keepaliveTicker = sseOrchestrator.scheduleKeepaliveTicker(emitter, bookId, queuedTask.id(), state.streamClosed());
        Runnable cancelPendingIfOpen = () -> {
            if (state.streamClosed().compareAndSet(false, true)) {
                queueTicker.cancel(true);
                keepaliveTicker.cancel(true);
                requestQueue.cancelPending(queuedTask.id());
            }
        };
        sseOrchestrator.wireEmitterLifecycle(emitter, bookId, cancelPendingIfOpen);
        wireStartedHandler(state, queuedTask, enqueuedAtMs);
        wireResultHandler(state, queuedTask, queueTicker, keepaliveTicker);
    }

    private BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> enqueueGenerationTask(
            QueuedStreamState state) {
        AtomicBoolean messageStarted = new AtomicBoolean(false);
        return requestQueue.enqueueForeground(DEFAULT_GENERATION_PRIORITY, () -> {
            sendMessageStartEvent(state.emitter(), messageStarted);
            BookAiContentService.GeneratedContent generated = aiContentService.generateAndPersist(state.bookId(), delta -> {
                sseOrchestrator.sendEvent(state.emitter(), "message_delta", new MessageDeltaPayload(delta));
            });
            sseOrchestrator.sendEvent(state.emitter(), "message_done", new MessageDonePayload(generated.rawMessage()));
            return generated;
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
            sseOrchestrator.sendEvent(state.emitter(), "started", new QueueStartedPayload(
                snapshot.running(), snapshot.pending(), snapshot.maxParallel(), queueWaitMs));
        }).exceptionally(throwable -> {
            if (!state.streamClosed().get()) {
                AiErrorDescriptor descriptor = resolveThrowableError(throwable);
                sseOrchestrator.emitTerminalError(state.emitter(), descriptor.code(), resolveClientMessage(descriptor.code(), descriptor.message()));
            } else {
                log.debug("Stream already closed when started-handler exception occurred: {}", throwable.getMessage());
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
                if (throwable != null) {
                    log.debug("Stream already closed when result-handler exception occurred: {}", throwable.getMessage());
                }
                return;
            }
            if (throwable != null) {
                AiErrorDescriptor descriptor = resolveThrowableError(throwable);
                sseOrchestrator.emitTerminalError(state.emitter(), descriptor.code(), resolveClientMessage(descriptor.code(), descriptor.message()));
                return;
            }
            if (result == null) {
                sseOrchestrator.emitTerminalError(state.emitter(), AiErrorCode.EMPTY_GENERATION,
                    resolveClientMessage(AiErrorCode.EMPTY_GENERATION, "AI generation returned no data"));
                return;
            }
            BookAiContentSnapshotDto snapshotDto = BookAiContentSnapshotDto.fromSnapshot(result.snapshot());
            try {
                sseOrchestrator.sendEvent(state.emitter(), "done", new DonePayload(result.rawMessage(), snapshotDto));
                sseOrchestrator.safelyComplete(state.emitter());
            } catch (IllegalStateException doneDeliveryException) {
                log.warn("AI done event delivery failed for bookId={}", state.bookId(), doneDeliveryException);
                sseOrchestrator.emitTerminalError(state.emitter(), AiErrorCode.GENERATION_FAILED,
                    resolveClientMessage(AiErrorCode.GENERATION_FAILED, "AI content was generated but could not be delivered"));
            }
        });
    }

    private void sendMessageStartEvent(SseEmitter emitter, AtomicBoolean messageStarted) {
        if (messageStarted.compareAndSet(false, true)) {
            sseOrchestrator.sendEvent(emitter, "message_start", new MessageStartPayload(
                UUID.randomUUID().toString(), aiContentService.configuredModel(), aiContentService.apiMode()));
        }
    }

    private void streamDoneFromCache(SseEmitter emitter, BookAiContentSnapshot snapshot) {
        try {
            String cachedMessage = objectMapper.writeValueAsString(snapshot.aiContent());
            sseOrchestrator.sendEvent(emitter, "done", new DonePayload(cachedMessage, BookAiContentSnapshotDto.fromSnapshot(snapshot)));
            sseOrchestrator.safelyComplete(emitter);
        } catch (JacksonException exception) {
            log.error("Failed to serialize cached AI content for bookId={}", snapshot.bookId(), exception);
            sseOrchestrator.emitTerminalError(emitter, AiErrorCode.CACHE_SERIALIZATION_FAILED,
                resolveClientMessage(AiErrorCode.CACHE_SERIALIZATION_FAILED, "Cached AI content could not be serialized"));
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
