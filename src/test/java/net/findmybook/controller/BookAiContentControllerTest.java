package net.findmybook.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
        controller = new BookAiContentController(aiContentService, requestQueue, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        controller.shutdownTickerExecutor();
    }

    @Test
    @DisplayName("GET /api/books/ai/content/queue returns queue snapshot")
    void queueStats_returnsQueueSnapshot() throws Exception {
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(1, 3, 2));

        mockMvc.perform(get("/api/books/ai/content/queue"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.running").value(1))
            .andExpect(jsonPath("$.pending").value(3))
            .andExpect(jsonPath("$.maxParallel").value(2));
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
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(0, 0, 1));

        BookAiContentRequestQueue.EnqueuedTask<BookAiContentService.GeneratedContent> task =
            new BookAiContentRequestQueue.EnqueuedTask<>("task-1", new CompletableFuture<>(), new CompletableFuture<>());

        when(requestQueue.<BookAiContentService.GeneratedContent>enqueue(
            anyInt(),
            org.mockito.ArgumentMatchers.<Supplier<BookAiContentService.GeneratedContent>>any()
        )).thenReturn(task);
        when(requestQueue.getPosition("task-1")).thenReturn(
            new BookAiContentRequestQueue.QueuePosition(true, 1, 0, 1, 5)
        );

        mockMvc.perform(post("/api/books/slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"));
            
        verify(requestQueue).enqueue(eq(0), any());
    }

    @Test
    @DisplayName("POST stream returns error event when queue is above threshold")
    void streamAiContent_returnsError_WhenQueueIsBusy() throws Exception {
        UUID bookId = UUID.randomUUID();
        when(aiContentService.resolveBookId("busy-slug")).thenReturn(Optional.of(bookId));
        when(aiContentService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(aiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(1, 6, 1));

        String responseBody = mockMvc.perform(post("/api/books/busy-slug/ai/content/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(responseBody).contains("event:error");
        assertThat(responseBody).contains("AI queue is currently busy");
        verify(requestQueue, never()).enqueue(anyInt(), any());
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

        assertThat(responseBody).contains("event:error");
        assertThat(responseBody).contains("Book not found");
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

        assertThat(responseBody).contains("event:error");
        assertThat(responseBody).contains("not configured");
    }

    @Test
    @DisplayName("queue ticker executor is multi-threaded to avoid cross-stream starvation")
    void queueTickerExecutor_usesMultipleThreads() {
        Object executorField = ReflectionTestUtils.getField(controller, "queueTickerExecutor");
        assertThat(executorField).isInstanceOf(ScheduledThreadPoolExecutor.class);

        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) executorField;
        assertThat(executor.getCorePoolSize()).isEqualTo(BookAiContentController.determineQueueTickerThreadCount());
        assertThat(executor.getCorePoolSize()).isGreaterThan(1);
    }
}
