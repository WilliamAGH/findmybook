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
import java.util.function.Supplier;
import net.findmybook.application.ai.BookAiAnalysisService;
import net.findmybook.domain.ai.BookAiAnalysis;
import net.findmybook.domain.ai.BookAiSnapshot;
import net.findmybook.support.ai.BookAiRequestQueue;
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
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BookAiControllerTest {

    @Mock
    private BookAiAnalysisService analysisService;

    @Mock
    private BookAiRequestQueue requestQueue;

    private BookAiController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new BookAiController(analysisService, requestQueue, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        controller.shutdownTickerExecutor();
    }

    @Test
    @DisplayName("GET /api/books/ai/queue returns queue snapshot")
    void queueStats_returnsQueueSnapshot() throws Exception {
        when(requestQueue.snapshot()).thenReturn(new BookAiRequestQueue.QueueSnapshot(1, 3, 2));

        mockMvc.perform(get("/api/books/ai/queue"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.running").value(1))
            .andExpect(jsonPath("$.pending").value(3))
            .andExpect(jsonPath("$.maxParallel").value(2));
    }

    @Test
    @DisplayName("POST stream returns cached done event when refresh=false and snapshot exists")
    void streamAnalysis_returnsCachedDoneEvent() throws Exception {
        UUID bookId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(analysisService.resolveBookId("fixture-slug")).thenReturn(Optional.of(bookId));
        when(analysisService.findCurrent(bookId)).thenReturn(Optional.of(
            new BookAiSnapshot(
                bookId,
                1,
                Instant.parse("2026-02-08T12:00:00Z"),
                "gpt-5-mini",
                "openai",
                new BookAiAnalysis(
                    "Short summary",
                    "Great for pragmatic readers",
                    List.of("Theme one", "Theme two")
                )
            )
        ));

        mockMvc.perform(post("/api/books/fixture-slug/ai/analysis/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"));
    }

    @Test
    @DisplayName("POST stream queues task when cache miss")
    void streamAnalysis_queuesTask_WhenCacheMiss() throws Exception {
        UUID bookId = UUID.randomUUID();
        when(analysisService.resolveBookId("slug")).thenReturn(Optional.of(bookId));
        when(analysisService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(analysisService.isAvailable()).thenReturn(true);
        when(requestQueue.snapshot()).thenReturn(new BookAiRequestQueue.QueueSnapshot(0, 0, 1));

        BookAiRequestQueue.EnqueuedTask<BookAiAnalysisService.GeneratedAnalysis> task =
            new BookAiRequestQueue.EnqueuedTask<>("task-1", new CompletableFuture<>(), new CompletableFuture<>());

        when(requestQueue.<BookAiAnalysisService.GeneratedAnalysis>enqueue(
            anyInt(),
            org.mockito.ArgumentMatchers.<Supplier<BookAiAnalysisService.GeneratedAnalysis>>any()
        )).thenReturn(task);
        when(requestQueue.getPosition("task-1")).thenReturn(
            new BookAiRequestQueue.QueuePosition(true, 1, 0, 1, 5)
        );

        mockMvc.perform(post("/api/books/slug/ai/analysis/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"));
            
        verify(requestQueue).enqueue(eq(0), any());
    }

    @Test
    @DisplayName("POST stream returns error event when queue is above threshold")
    void streamAnalysis_returnsError_WhenQueueIsBusy() throws Exception {
        UUID bookId = UUID.randomUUID();
        when(analysisService.resolveBookId("busy-slug")).thenReturn(Optional.of(bookId));
        when(analysisService.findCurrent(bookId)).thenReturn(Optional.empty());
        when(analysisService.isAvailable()).thenReturn(true);
        when(requestQueue.snapshot()).thenReturn(new BookAiRequestQueue.QueueSnapshot(1, 6, 1));

        String responseBody = mockMvc.perform(post("/api/books/busy-slug/ai/analysis/stream"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/event-stream"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(responseBody).contains("event:error");
        assertThat(responseBody).contains("AI queue is currently busy");
        verify(requestQueue, never()).enqueue(anyInt(), any());
    }
}
