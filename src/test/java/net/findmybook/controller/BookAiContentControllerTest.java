package net.findmybook.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.llm.LlmGatewayTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BookAiContentControllerTest {

    @Mock
    private BookAiContentService aiContentService;

    @Mock
    private BookAiContentRequestQueue requestQueue;

    private BookAiContentController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        configureController("development");
    }

    @AfterEach
    void tearDown() {
        controller.shutdownTickerExecutor();
    }

    @Test
    @DisplayName("GET /api/books/ai/content/queue returns queue snapshot with availability")
    void queueStats_returnsQueueSnapshot() throws Exception {
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(1, 3, 2));
        when(aiContentService.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/books/ai/content/queue"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.running").value(1))
            .andExpect(jsonPath("$.pending").value(3))
            .andExpect(jsonPath("$.maxParallel").value(2))
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.environmentMode").value("development"));
    }

    @Test
    @DisplayName("GET /api/books/ai/content/queue defaults environment mode to production when blank")
    void queueStats_defaultsEnvironmentModeToProduction_WhenBlankModeProvided() throws Exception {
        controller.shutdownTickerExecutor();
        configureController("   ");
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(0, 0, 1));
        when(aiContentService.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/books/ai/content/queue"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.environmentMode").value("production"));
    }

    @Test
    @DisplayName("POST stream returns cached done event when refresh=false and snapshot exists")
    void streamAiContent_returnsCachedDoneEvent() throws Exception {
        UUID bookId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(aiContentService.resolveBookId("fixture-slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.of(
            new BookAiContentSnapshot(
                bookId,
                1,
                Instant.parse("2026-02-08T12:00:00Z"),
                "gpt-5-mini",
                "openai",
                new BookAiContent(
                    "Short summary",
                    "Great for pragmatic readers",
                    List.of("Theme one", "Theme two"),
                    List.of("Insight one", "Insight two"),
                    "Placed in the self-help genre."
                )
            )
        ));

        mockMvc.perform(post("/api/books/fixture-slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"));
    }

    @Test
    @DisplayName("POST stream queues task when cache miss")
    void streamAiContent_queuesTask_WhenCacheMiss() throws Exception {
        UUID bookId = UUID.randomUUID();
        when(aiContentService.resolveBookId("slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(aiContentService.isAvailable()).thenReturn(true);

        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("task-1", new CompletableFuture<>(), new CompletableFuture<>());

        stubForegroundEnqueue(task);
        when(requestQueue.getPosition("task-1")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 5)
        );

        String responseBody = mockMvc.perform(post("/api/books/slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"))
            .andExpect(header().string("X-Book-AI-Request-Id", "task-1"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(responseBody)
            .contains("event:queued")
            .contains("\"requestId\":\"task-1\"");
        verify(requestQueue).enqueueForeground(eq(0), any(), any());
    }

    @Test
    @DisplayName("POST cancel is idempotent for pending and unknown request IDs")
    void should_CancelPendingTaskOnce_When_CancelRequestIsRepeated() throws Exception {
        UUID bookId = UUID.randomUUID();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<BookAiContentService.GeneratedContent> result = new CompletableFuture<>();
        ArgumentCaptor<Supplier<BookAiContentService.GeneratedContent>> supplierCaptor = ArgumentCaptor.captor();
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("pending-request", started, result);
        when(aiContentService.resolveBookId("pending-book")).thenReturn(Optional.of(bookId));
        when(aiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.<BookAiContentService.GeneratedContent>enqueueForeground(
            anyInt(), supplierCaptor.capture(), any()
        )).thenAnswer(invocation -> {
            Consumer<BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent>> lifecycleSetup =
                invocation.getArgument(2);
            lifecycleSetup.accept(task);
            return task;
        });
        when(requestQueue.getPosition("pending-request")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 1));

        mockMvc.perform(post("/api/books/pending-book/ai/content/stream?refresh=true"))
            .andExpect(status().isOk());
        assertThat(activeRequestCount()).isEqualTo(1);

        mockMvc.perform(post("/api/books/ai/content/requests/unknown-request/cancel"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mockMvc.perform(post("/api/books/ai/content/requests/pending-request/cancel"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mockMvc.perform(post("/api/books/ai/content/requests/pending-request/cancel"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(requestQueue, times(1)).cancel("pending-request");
        assertThat(activeRequestCount()).isZero();
        assertThatThrownBy(() -> supplierCaptor.getValue().get())
            .isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("POST cancel claims GenerationControl for a running request")
    void should_CancelGenerationControl_When_RunningRequestIsCanceled() throws Exception {
        UUID bookId = UUID.randomUUID();
        BookAiContentSnapshot snapshot = new BookAiContentSnapshot(
            bookId, 1, Instant.EPOCH, "test-model", "openai",
            new BookAiContent("Summary", "Audience", List.of("Theme"), List.of("Insight"), "Context")
        );
        BookAiContentService.GeneratedContent generated =
            new BookAiContentService.GeneratedContent("{\"summary\":\"Summary\"}", snapshot);
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<BookAiContentService.GeneratedContent> result = new CompletableFuture<>();
        ArgumentCaptor<Supplier<BookAiContentService.GeneratedContent>> supplierCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<BookAiContentService.GenerationControl> controlCaptor = ArgumentCaptor.captor();
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("running-request", started, result);
        when(aiContentService.resolveBookId("running-book")).thenReturn(Optional.of(bookId));
        when(aiContentService.isAvailable()).thenReturn(true);
        when(aiContentService.configuredModel()).thenReturn("test-model");
        when(aiContentService.apiMode()).thenReturn("openai");
        when(aiContentService.generateAndPersist(eq(bookId), any(), eq(LlmGatewayTier.LIVE_RENDER), controlCaptor.capture()))
            .thenReturn(generated);
        when(requestQueue.<BookAiContentService.GeneratedContent>enqueueForeground(
            anyInt(), supplierCaptor.capture(), any()
        )).thenAnswer(invocation -> {
            Consumer<BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent>> lifecycleSetup =
                invocation.getArgument(2);
            lifecycleSetup.accept(task);
            return task;
        });
        when(requestQueue.getPosition("running-request")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 1));
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(1, 0, 1));

        mockMvc.perform(post("/api/books/running-book/ai/content/stream?refresh=true"))
            .andExpect(status().isOk());
        started.complete(null);
        supplierCaptor.getValue().get();
        mockMvc.perform(post("/api/books/ai/content/requests/running-request/cancel"))
            .andExpect(status().isNoContent());

        verify(requestQueue).cancel("running-request");
        assertThat(controlCaptor.getValue().cancel()).isFalse();
        assertThat(activeRequestCount()).isZero();
    }

    @Test
    @DisplayName("POST stream cannot invoke an immediately started supplier before controller lifecycle wiring")
    void should_GenerateSuccessfully_When_QueueStartsSupplierBeforeEnqueueReturns() throws Exception {
        UUID bookId = UUID.randomUUID();
        BookAiContentSnapshot snapshot = new BookAiContentSnapshot(
            bookId,
            1,
            Instant.EPOCH,
            "gemma-4-26b-a4b",
            "openai",
            new BookAiContent("Summary", "Audience", List.of("Theme"), List.of("Insight"), "Context")
        );
        BookAiContentService.GeneratedContent generated =
            new BookAiContentService.GeneratedContent("{\"summary\":\"Summary\"}", snapshot);
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<BookAiContentService.GeneratedContent> result = new CompletableFuture<>();
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("task-immediate-1", started, result);

        when(aiContentService.resolveBookId("immediate-slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.isAvailable()).thenReturn(true);
        when(aiContentService.configuredModel()).thenReturn("gemma-4-26b-a4b");
        when(aiContentService.apiMode()).thenReturn("openai");
        when(aiContentService.generateAndPersist(eq(bookId), any(), eq(LlmGatewayTier.LIVE_RENDER), any()))
            .thenReturn(generated);
        when(requestQueue.<BookAiContentService.GeneratedContent>enqueueForeground(anyInt(), any(), any()))
            .thenAnswer(invocation -> {
                Supplier<BookAiContentService.GeneratedContent> supplier = invocation.getArgument(1);
                Consumer<BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent>> lifecycleSetup =
                    invocation.getArgument(2);
                lifecycleSetup.accept(task);
                started.complete(null);
                result.complete(supplier.get());
                return task;
            });
        when(requestQueue.getPosition("task-immediate-1")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(false, null, 1, 0, 1));
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(1, 0, 1));

        var response = mockMvc.perform(post("/api/books/immediate-slug/ai/content/stream?refresh=true"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(response.getAsyncResult(1_000L)).isNull();
        assertThat(response.getResponse().getContentAsString())
            .contains("event:message_start")
            .contains("event:done")
            .doesNotContain("event:error");
        assertThat(activeRequestCount()).isZero();
        verify(aiContentService).generateAndPersist(eq(bookId), any(), eq(LlmGatewayTier.LIVE_RENDER), any());
    }

    @Test
    @DisplayName("POST stream removes cancellation registration when lifecycle setup fails")
    void should_RemoveCancellationRegistration_When_LifecycleSetupFails() {
        controller.shutdownTickerExecutor();
        ScheduledThreadPoolExecutor rejectedExecutor = new ScheduledThreadPoolExecutor(1);
        rejectedExecutor.shutdownNow();
        configureController("development", rejectedExecutor, 200L, 200L, 500L);
        UUID bookId = UUID.randomUUID();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<BookAiContentService.GeneratedContent> result = new CompletableFuture<>();
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("setup-failure-request", started, result);
        when(aiContentService.resolveBookId("setup-failure-book")).thenReturn(Optional.of(bookId));
        when(aiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.getPosition("setup-failure-request")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 1));
        when(requestQueue.<BookAiContentService.GeneratedContent>enqueueForeground(anyInt(), any(), any()))
            .thenAnswer(invocation -> {
                Consumer<BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent>> lifecycleSetup =
                    invocation.getArgument(2);
                lifecycleSetup.accept(task);
                return task;
            });

        assertThatThrownBy(() -> mockMvc.perform(
            post("/api/books/setup-failure-book/ai/content/stream?refresh=true")
        )).hasCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        verify(requestQueue).cancel("setup-failure-request");
        assertThat(activeRequestCount()).isZero();
    }

    @Test
    @DisplayName("shutdown rejects and cancels request registrations that arrive after registry closure")
    void should_CancelLateRegistration_When_ShutdownHasStarted() {
        BookAiContentSseOrchestrator orchestrator = sseOrchestrator();
        AtomicBoolean cancellationInvoked = new AtomicBoolean(false);
        orchestrator.cancelAllRequests();

        assertThatThrownBy(() -> orchestrator.registerCancellation(
            "late-request", () -> cancellationInvoked.set(true)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("AI cancellation registry is shutting down");

        assertThat(cancellationInvoked).isTrue();
        assertThat(orchestrator.activeRequestCount()).isZero();
    }

    @Test
    @DisplayName("POST stream continues to enqueue foreground task even when pending queue is high")
    void should_EnqueueForegroundTask_When_QueueBusyWithBackgroundWork() throws Exception {
        UUID bookId = UUID.randomUUID();
        when(aiContentService.resolveBookId("busy-slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(aiContentService.isAvailable()).thenReturn(true);

        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("task-busy-1", new CompletableFuture<>(), new CompletableFuture<>());
        stubForegroundEnqueue(task);
        when(requestQueue.getPosition("task-busy-1")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 1, 6000, 1)
        );

        mockMvc.perform(post("/api/books/busy-slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"));

        verify(requestQueue).enqueueForeground(eq(0), any(), any());
    }

    @Test
    @DisplayName("POST stream returns error event when book identifier not found")
    void streamAiContent_returnsError_WhenBookNotFound() throws Exception {
        when(aiContentService.resolveBookId("unknown-slug")).thenReturn(Optional.empty());

        String responseBody = mockMvc.perform(post("/api/books/unknown-slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(responseBody)
            .contains("event:error")
            .contains("Book not found")
            .contains("\"code\":\"book_not_found\"");
    }

    @Test
    @DisplayName("POST stream returns error event when AI service not available")
    void streamAiContent_returnsError_WhenServiceNotAvailable() throws Exception {
        UUID bookId = UUID.randomUUID();
        when(aiContentService.resolveBookId("slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(aiContentService.isAvailable()).thenReturn(false);

        String responseBody = mockMvc.perform(post("/api/books/slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(responseBody)
            .contains("event:error")
            .contains("not configured")
            .contains("\"code\":\"service_unavailable\"");
    }

    @Test
    @DisplayName("POST stream redacts detailed AI validation errors in production mode")
    void streamAiContent_redactsDetailedGenerationError_WhenProductionMode() throws Exception {
        String responseBody = streamResponseForFailedGeneration("production", new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT,
            "Book description is missing or too short for faithful AI generation (bookId=fixed, length=0, minimum=50)"
        ));

        assertThat(responseBody)
            .contains("event:error")
            .contains("\"code\":\"description_too_short\"")
            .contains("AI content is unavailable for this book")
            .doesNotContain("length=0");
        assertThat(activeRequestCount()).isZero();
    }

    @Test
    @DisplayName("POST stream preserves detailed AI validation errors in development mode")
    void streamAiContent_preservesDetailedGenerationError_WhenDevelopmentMode() throws Exception {
        String responseBody = streamResponseForFailedGeneration("development", new BookAiGenerationException(
            BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT,
            "Book description is missing or too short for faithful AI generation (bookId=fixed, length=0, minimum=50)"
        ));

        assertThat(responseBody)
            .contains("event:error")
            .contains("\"code\":\"description_too_short\"")
            .contains("missing or too short")
            .contains("length=0");
    }

    @Test
    @DisplayName("queue ticker executor is multi-threaded to avoid cross-stream starvation")
    void queueTickerExecutor_usesMultipleThreads() {
        Object executorField = ReflectionTestUtils.getField(controller, "queueTickerExecutor");
        assertThat(executorField).isInstanceOf(ScheduledThreadPoolExecutor.class);

        try (ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) executorField) {
            assertThat(executor.getCorePoolSize()).isEqualTo(BookAiContentController.determineQueueTickerThreadCount());
            assertThat(executor.getCorePoolSize()).isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("default generation deadline reserves delivery time after all live render attempts")
    void should_ReserveDeliveryHeadroom_When_DefaultGenerationDeadlineIsCalculated() {
        long queueWaitDeadlineMillis = (Long) ReflectionTestUtils.getField(controller, "queueWaitDeadlineMillis");
        long generationDeadlineMillis = (Long) ReflectionTestUtils.getField(controller, "generationDeadlineMillis");
        long emitterTimeoutMillis = (Long) ReflectionTestUtils.getField(controller, "emitterTimeoutMillis");
        long maximumLiveRenderAttemptsMillis = Duration.ofSeconds(LlmGatewayTier.LIVE_RENDER.callTimeoutSeconds())
            .multipliedBy(LlmGatewayTier.LIVE_RENDER.maxGenerationAttempts())
            .toMillis();

        assertThat(generationDeadlineMillis - maximumLiveRenderAttemptsMillis)
            .isGreaterThanOrEqualTo(Duration.ofMinutes(1).toMillis());
        assertThat(emitterTimeoutMillis).isGreaterThan(queueWaitDeadlineMillis + generationDeadlineMillis);
    }

    @Test
    @DisplayName("POST stream starts the generation deadline only after leaving the queue")
    void should_StartGenerationDeadline_When_QueuedTaskStarts() throws Exception {
        controller.shutdownTickerExecutor();
        ScheduledThreadPoolExecutor deadlineExecutor = new ScheduledThreadPoolExecutor(1);
        configureController("development", deadlineExecutor, 200L, 20L, 500L);
        UUID bookId = UUID.randomUUID();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<BookAiContentService.GeneratedContent> result = new CompletableFuture<>();
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("task-timeout-1", started, result);
        when(aiContentService.resolveBookId("slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(aiContentService.isAvailable()).thenReturn(true);
        stubForegroundEnqueue(task);
        when(requestQueue.getPosition("task-timeout-1")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 1));
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(1, 0, 1));

        var response = mockMvc.perform(post("/api/books/slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andReturn();

        Thread.sleep(60L);
        assertThat(response.getResponse().getContentAsString()).doesNotContain("stream_timeout");
        started.complete(null);
        assertThat(response.getAsyncResult(1_000L)).isNull();
        assertThat(response.getResponse().getContentAsString()).contains("\"code\":\"stream_timeout\"");
        verify(requestQueue).cancel("task-timeout-1");
    }

    @Test
    @DisplayName("POST stream cancels pending work when the bounded queue wait expires")
    void should_CancelPendingWork_When_QueueWaitDeadlineExpires() throws Exception {
        controller.shutdownTickerExecutor();
        ScheduledThreadPoolExecutor deadlineExecutor = new ScheduledThreadPoolExecutor(1);
        configureController("development", deadlineExecutor, 20L, 200L, 500L);
        UUID bookId = UUID.randomUUID();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<BookAiContentService.GeneratedContent> result = new CompletableFuture<>();
        ArgumentCaptor<Supplier<BookAiContentService.GeneratedContent>> supplierCaptor = ArgumentCaptor.captor();
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("task-queue-timeout-1", started, result);
        when(aiContentService.resolveBookId("slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(aiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.<BookAiContentService.GeneratedContent>enqueueForeground(
            anyInt(), supplierCaptor.capture(), any()
        )).thenAnswer(invocation -> {
            Consumer<BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent>> lifecycleSetup =
                invocation.getArgument(2);
            lifecycleSetup.accept(task);
            return task;
        });
        when(requestQueue.getPosition("task-queue-timeout-1")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 1));

        var response = mockMvc.perform(post("/api/books/slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(response.getAsyncResult(1_000L)).isNull();
        assertThat(response.getResponse().getContentAsString()).contains("\"code\":\"queue_busy\"");
        verify(requestQueue).cancel("task-queue-timeout-1");
        assertThatThrownBy(() -> supplierCaptor.getValue().get())
            .isInstanceOf(CancellationException.class)
            .hasMessage("AI stream closed before generation started");
        verify(aiContentService, never()).generateAndPersist(any(), any(), any(), any());
    }

    /**
     * Builds a deterministic failed queue task so tests can assert environment-specific
     * error payload handling without duplicating queue orchestration setup.
     */
    private String streamResponseForFailedGeneration(String environmentMode, RuntimeException failure) throws Exception {
        controller.shutdownTickerExecutor();
        configureController(environmentMode);

        UUID bookId = UUID.randomUUID();
        when(aiContentService.resolveBookId("slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(aiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(0, 0, 1));

        CompletableFuture<Void> started = CompletableFuture.completedFuture(null);
        CompletableFuture<BookAiContentService.GeneratedContent> failedResult = new CompletableFuture<>();
        failedResult.completeExceptionally(failure);

        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("task-failed-1", started, failedResult);
        stubForegroundEnqueue(task);
        when(requestQueue.getPosition("task-failed-1")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 0)
        );

        return mockMvc.perform(post("/api/books/slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    private void configureController(String environmentMode) {
        controller = new BookAiContentController(aiContentService, requestQueue, new ObjectMapper(), environmentMode);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private int activeRequestCount() {
        return sseOrchestrator().activeRequestCount();
    }

    private BookAiContentSseOrchestrator sseOrchestrator() {
        return (BookAiContentSseOrchestrator) ReflectionTestUtils.getField(controller, "sseOrchestrator");
    }

    private void stubForegroundEnqueue(
        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task
    ) {
        when(requestQueue.<BookAiContentService.GeneratedContent>enqueueForeground(anyInt(), any(), any()))
            .thenAnswer(invocation -> {
                Consumer<BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent>> lifecycleSetup =
                    invocation.getArgument(2);
                lifecycleSetup.accept(task);
                return task;
            });
    }

    private void configureController(String environmentMode, ScheduledThreadPoolExecutor executor,
                                     long queueWaitDeadlineMillis,
                                     long generationDeadlineMillis,
                                     long emitterTimeoutMillis) {
        controller = new BookAiContentController(aiContentService, requestQueue, new ObjectMapper(), environmentMode,
            executor, queueWaitDeadlineMillis, generationDeadlineMillis, emitterTimeoutMillis);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
}
