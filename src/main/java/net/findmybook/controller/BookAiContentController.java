package net.findmybook.controller;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.llm.LlmGatewayTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final Duration LIVE_RENDER_ATTEMPT_TIMEOUT =
        Duration.ofSeconds(LlmGatewayTier.LIVE_RENDER.callTimeoutSeconds());
    private static final Duration GENERATION_DELIVERY_HEADROOM = Duration.ofMinutes(1);
    private static final Duration GENERATION_DEADLINE = LIVE_RENDER_ATTEMPT_TIMEOUT
        .multipliedBy(LlmGatewayTier.LIVE_RENDER.maxGenerationAttempts())
        .plus(GENERATION_DELIVERY_HEADROOM);
    private static final Duration QUEUE_WAIT_DEADLINE = Duration.ofMinutes(10);
    private static final Duration EMITTER_TERMINAL_EVENT_HEADROOM = Duration.ofSeconds(5);
    private static final long GENERATION_DEADLINE_MILLIS = GENERATION_DEADLINE.toMillis();
    private static final long QUEUE_WAIT_DEADLINE_MILLIS = QUEUE_WAIT_DEADLINE.toMillis();
    private static final long EMITTER_TIMEOUT_MILLIS = QUEUE_WAIT_DEADLINE
        .plus(GENERATION_DEADLINE)
        .plus(EMITTER_TERMINAL_EVENT_HEADROOM)
        .toMillis();
    private static final long MIN_STREAM_TIMEOUT_MILLIS = 1L;
    private static final int DEFAULT_GENERATION_PRIORITY = 0;
    private static final int MIN_QUEUE_TICKER_THREADS = 4;
    private static final int MAX_QUEUE_TICKER_THREADS = 16;
    private static final String PRODUCTION_ENVIRONMENT_MODE = "production";

    private final BookAiContentService aiContentService;
    private final BookAiContentRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final BookAiContentSseOrchestrator sseOrchestrator;
    private final ScheduledExecutorService queueTickerExecutor;
    private final long queueWaitDeadlineMillis;
    private final long generationDeadlineMillis;
    private final long emitterTimeoutMillis;
    private final String environmentMode;
    private final boolean exposeDetailedErrors;

    /** Creates a controller with queue/state dependencies. */
    @Autowired
    public BookAiContentController(BookAiContentService aiContentService,
                                   BookAiContentRequestQueue requestQueue,
                                   ObjectMapper objectMapper,
                                   @Value("${app.environment.mode:production}") String environmentMode) {
        this(
            aiContentService,
            requestQueue,
            objectMapper,
            environmentMode,
            createQueueTickerExecutor(),
            QUEUE_WAIT_DEADLINE_MILLIS,
            GENERATION_DEADLINE_MILLIS,
            EMITTER_TIMEOUT_MILLIS
        );
    }

    BookAiContentController(BookAiContentService aiContentService,
                            BookAiContentRequestQueue requestQueue,
                            ObjectMapper objectMapper,
                            String environmentMode,
                            ScheduledExecutorService queueTickerExecutor,
                            long queueWaitDeadlineMillis,
                            long generationDeadlineMillis,
                            long emitterTimeoutMillis) {
        if (queueWaitDeadlineMillis < MIN_STREAM_TIMEOUT_MILLIS
                || generationDeadlineMillis < MIN_STREAM_TIMEOUT_MILLIS
                || emitterTimeoutMillis <= queueWaitDeadlineMillis + generationDeadlineMillis) {
            throw new IllegalArgumentException("Emitter timeout must exceed queue and generation deadlines");
        }
        this.aiContentService = aiContentService;
        this.requestQueue = requestQueue;
        this.objectMapper = objectMapper;
        this.environmentMode = normalizeEnvironmentMode(environmentMode);
        this.exposeDetailedErrors = !PRODUCTION_ENVIRONMENT_MODE.equals(this.environmentMode);
        this.queueTickerExecutor = queueTickerExecutor;
        this.queueWaitDeadlineMillis = queueWaitDeadlineMillis;
        this.generationDeadlineMillis = generationDeadlineMillis;
        this.emitterTimeoutMillis = emitterTimeoutMillis;
        this.sseOrchestrator = new BookAiContentSseOrchestrator(requestQueue, queueTickerExecutor);
    }

    private static ScheduledExecutorService createQueueTickerExecutor() {
        int queueTickerThreads = determineQueueTickerThreadCount();
        ThreadFactory threadFactory = Thread.ofPlatform()
            .name("book-ai-queue-ticker-", 0)
            .daemon(true)
            .factory();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(queueTickerThreads, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
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

        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
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
    private enum QueuedStreamPhase {
        WAITING,
        GENERATING,
        CLOSED
    }

    private record QueuedStreamState(
        SseEmitter emitter,
        UUID bookId,
        AtomicBoolean streamClosed,
        AtomicReference<QueuedStreamPhase> phase,
        BookAiContentService.GenerationControl generationControl
    ) {}

    private void beginQueuedStream(SseEmitter emitter, UUID bookId) {
        long enqueuedAtMs = System.currentTimeMillis();
        QueuedStreamState state = new QueuedStreamState(
            emitter,
            bookId,
            new AtomicBoolean(false),
            new AtomicReference<>(QueuedStreamPhase.WAITING),
            new BookAiContentService.GenerationControl()
        );
        enqueueGenerationTask(state, enqueuedAtMs);
    }

    private void wireQueuedTaskLifecycle(
        QueuedStreamState state,
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> queuedTask,
        long enqueuedAtMs
    ) {
        SseEmitter emitter = state.emitter();
        UUID bookId = state.bookId();
        sseOrchestrator.sendEvent(emitter, "queued", sseOrchestrator.toQueuePositionPayload(requestQueue.getPosition(queuedTask.id())));
        AtomicReference<Optional<BookAiContentSseOrchestrator.Timers>> timersReference =
            new AtomicReference<>(Optional.empty());
        Runnable cancelWorkIfOpen = () -> timersReference.get().ifPresent(
            timers -> claimTerminalOwnership(state, timers, queuedTask.id(), true)
        );
        ScheduledFuture<?> queueTicker = sseOrchestrator.scheduleQueuePositionTicker(
            emitter,
            bookId,
            queuedTask.id(),
            state.streamClosed(),
            cancelWorkIfOpen
        );
        ScheduledFuture<?> keepaliveTicker = sseOrchestrator.scheduleKeepaliveTicker(
            emitter,
            bookId,
            state.streamClosed(),
            cancelWorkIfOpen
        );
        BookAiContentSseOrchestrator.Timers timers = new BookAiContentSseOrchestrator.Timers(queueTicker, keepaliveTicker);
        timersReference.set(Optional.of(timers));
        ScheduledFuture<?> queueWaitDeadline = sseOrchestrator.scheduleApplicationDeadline(() -> {
            if (state.phase().compareAndSet(QueuedStreamPhase.WAITING, QueuedStreamPhase.CLOSED)
                    && claimTerminalOwnership(state, timers, queuedTask.id(), true)) {
                sseOrchestrator.emitTerminalError(
                    emitter,
                    AiErrorCode.QUEUE_BUSY,
                    AiErrorCode.QUEUE_BUSY.defaultMessage()
                );
            }
        }, queueWaitDeadlineMillis);
        timers.replaceDeadline(queueWaitDeadline);
        sseOrchestrator.wireEmitterLifecycle(emitter, bookId, cancelWorkIfOpen);
        wireStartedHandler(state, queuedTask, enqueuedAtMs, timers);
        wireResultHandler(state, queuedTask, timers);
    }

    private boolean claimTerminalOwnership(
        QueuedStreamState state,
        BookAiContentSseOrchestrator.Timers timers,
        String taskId,
        boolean cancelWork
    ) {
        if (!state.streamClosed().compareAndSet(false, true)) {
            return false;
        }
        state.phase().set(QueuedStreamPhase.CLOSED);
        timers.cancelAll();
        if (cancelWork) {
            if (state.generationControl().cancel()) {
                requestQueue.cancel(taskId);
            }
        }
        return true;
    }

    private BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> enqueueGenerationTask(
            QueuedStreamState state,
            long enqueuedAtMs) {
        AtomicBoolean messageStarted = new AtomicBoolean(false);
        return requestQueue.enqueueForeground(DEFAULT_GENERATION_PRIORITY, () -> {
            if (state.streamClosed().get() || state.phase().get() != QueuedStreamPhase.GENERATING) {
                throw new CancellationException("AI stream closed before generation started");
            }
            sendMessageStartEvent(state, messageStarted);
            BookAiContentService.GeneratedContent generated = aiContentService.generateAndPersist(
                state.bookId(),
                delta -> sendEventIfOpen(state, "message_delta", new MessageDeltaPayload(delta)),
                LlmGatewayTier.LIVE_RENDER,
                state.generationControl()
            );
            sendEventIfOpen(state, "message_done", new MessageDonePayload(generated.rawMessage()));
            return generated;
        }, queuedTask -> wireQueuedTaskLifecycle(state, queuedTask, enqueuedAtMs));
    }

    private void wireStartedHandler(QueuedStreamState state,
                                     BookAiContentRequestQueue.EnqueuedTask<?> queuedTask,
                                     long enqueuedAtMs,
                                     BookAiContentSseOrchestrator.Timers timers) {
        queuedTask.started().thenRun(() -> {
            if (!state.phase().compareAndSet(QueuedStreamPhase.WAITING, QueuedStreamPhase.GENERATING)
                    || state.streamClosed().get()) {
                return;
            }
            timers.cancelQueueTicker();
            ScheduledFuture<?> generationDeadline = sseOrchestrator.scheduleApplicationDeadline(() -> {
                if (claimTerminalOwnership(state, timers, queuedTask.id(), true)) {
                    sseOrchestrator.emitTerminalError(
                        state.emitter(),
                        AiErrorCode.STREAM_TIMEOUT,
                        AiErrorCode.STREAM_TIMEOUT.defaultMessage()
                    );
                }
            }, generationDeadlineMillis);
            timers.replaceDeadline(generationDeadline);
            if (state.streamClosed().get()) {
                timers.cancelAll();
                return;
            }
            long queueWaitMs = Math.max(0L, System.currentTimeMillis() - enqueuedAtMs);
            BookAiContentRequestQueue.QueueSnapshot snapshot = requestQueue.snapshot();
            sendEventIfOpen(state, "started", new QueueStartedPayload(
                snapshot.running(), snapshot.pending(), snapshot.maxParallel(), queueWaitMs));
        }).exceptionally(throwable -> {
            if (claimTerminalOwnership(state, timers, queuedTask.id(), true)) {
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
                                    BookAiContentSseOrchestrator.Timers timers) {
        queuedTask.result().whenComplete((result, throwable) -> {
            if (!claimTerminalOwnership(state, timers, queuedTask.id(), false)) {
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

    private void sendMessageStartEvent(QueuedStreamState state, AtomicBoolean messageStarted) {
        if (messageStarted.compareAndSet(false, true)) {
            sendEventIfOpen(state, "message_start", new MessageStartPayload(
                UUID.randomUUID().toString(), aiContentService.configuredModel(), aiContentService.apiMode()));
        }
    }

    private void sendEventIfOpen(QueuedStreamState state, String eventName, BookAiContentSsePayload payload) {
        try {
            if (state.streamClosed().get()) {
                return;
            }
            sseOrchestrator.sendEvent(state.emitter(), eventName, payload);
        } catch (IllegalStateException deliveryFailure) {
            if (!state.streamClosed().get()) {
                throw deliveryFailure;
            }
            log.debug("Skipped {} event after stream closed for bookId={}", eventName, state.bookId());
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
                case DEGENERATE_CONTENT -> AiErrorCode.DEGENERATE_CONTENT;
                case GENERATION_FAILED, INCOMPLETE_RESPONSE, INVALID_RESPONSE -> AiErrorCode.GENERATION_FAILED;
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
