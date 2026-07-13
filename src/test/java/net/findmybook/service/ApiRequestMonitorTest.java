/**
 * Tests for the ApiRequestMonitor service
 * - Verifies counter tracking functionality
 * - Tests success/failure recording
 * - Ensures thread-safe operation
 *
 * @author William Callahan
 */
package net.findmybook.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

public class ApiRequestMonitorTest {

    private ApiRequestMonitor apiRequestMonitor;

    @BeforeEach
    public void setUp() {
        apiRequestMonitor = new ApiRequestMonitor();
    }

    @Test
    public void testRecordSuccessfulCall() {
        // Starting counts should be zero
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_requests")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_successful")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_failed")).intValue());

        // Record a successful call
        apiRequestMonitor.recordSuccessfulRequest("test/endpoint");

        // Verify counts were updated correctly
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_requests")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_successful")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_failed")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("daily_requests")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("daily_successful")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("daily_failed")).intValue());
        assertEquals(1L, (Long) apiRequestMonitor.getMetricsMap().get("total_requests"));
        assertEquals(1L, (Long) apiRequestMonitor.getMetricsMap().get("total_successful"));
        assertEquals(0L, (Long) apiRequestMonitor.getMetricsMap().get("total_failed"));

        // Verify endpoint tracking without unchecked casts
        Object endpointsObj = apiRequestMonitor.getMetricsMap().get("endpoints");
        assertNotNull(endpointsObj);
        assertTrue(endpointsObj instanceof Map);
        Map<?, ?> endpointCalls = (Map<?, ?>) endpointsObj;
        assertTrue(endpointCalls.containsKey("test/endpoint"));
        assertEquals(1, ((Number) endpointCalls.get("test/endpoint")).intValue());
    }

    @Test
    public void testRecordFailedCall() {
        // Record a failed call
        apiRequestMonitor.recordFailedRequest("test/endpoint", "Test error message");

        // Verify counts were updated correctly
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_requests")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_successful")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_failed")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("daily_requests")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("daily_successful")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("daily_failed")).intValue());
        assertEquals(1L, (Long) apiRequestMonitor.getMetricsMap().get("total_requests"));
        assertEquals(0L, (Long) apiRequestMonitor.getMetricsMap().get("total_successful"));
        assertEquals(1L, (Long) apiRequestMonitor.getMetricsMap().get("total_failed"));
    }

    @Test
    void should_LogOneBoundedWarning_When_GoogleSearchPageFails() {
        GoogleApiFetcher fetcher = new GoogleApiFetcher(
                WebClient.builder().exchangeFunction(request ->
                        reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                                .body("provider unavailable")
                                .build())),
                apiRequestMonitor,
                new ApiCircuitBreakerService());
        ReflectionTestUtils.setField(fetcher, "googleBooksApiUrl", "https://books.googleapis.test");
        ReflectionTestUtils.setField(fetcher, "googleBooksApiKey", "test-key");

        Logger fetcherLogger = (Logger) LoggerFactory.getLogger(GoogleApiFetcher.class);
        ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
        logEvents.start();
        fetcherLogger.addAppender(logEvents);
        try {
            StepVerifier.create(fetcher.streamSearchItems("failure query", 1, "relevance", null, true))
                    .expectError(IllegalStateException.class)
                    .verify();

            List<ILoggingEvent> warnings = logEvents.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
            assertEquals(1, warnings.size());
            ILoggingEvent warning = warnings.getFirst();
            assertTrue(warning.getFormattedMessage().contains("[EXTERNAL-API] [GoogleBooks] FAILURE"));
            assertNull(warning.getThrowableProxy());
            assertEquals(0, logEvents.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .count());
        } finally {
            fetcherLogger.detachAppender(logEvents);
            logEvents.stop();
        }
    }

    @Test
    public void testResetMetrics() {
        // Record some calls
        apiRequestMonitor.recordSuccessfulRequest("test/endpoint");
        apiRequestMonitor.recordFailedRequest("test/endpoint", "Test error message");

        // Verify initial state
        assertEquals(2, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_requests")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_successful")).intValue());
        assertEquals(1, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_failed")).intValue());
        assertEquals(2, ((Number) apiRequestMonitor.getMetricsMap().get("daily_requests")).intValue());

        // Reset hourly metrics
        apiRequestMonitor.resetHourlyCounters();

        // Verify hourly counters were reset
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_requests")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_successful")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_failed")).intValue());

        // But daily and total shouldn't be affected
        assertEquals(2, ((Number) apiRequestMonitor.getMetricsMap().get("daily_requests")).intValue());
        assertEquals(2L, (Long) apiRequestMonitor.getMetricsMap().get("total_requests"));

        // Reset daily metrics
        apiRequestMonitor.resetDailyCounters();

        // Verify daily counters were reset
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("daily_requests")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("daily_successful")).intValue());
        assertEquals(0, ((Number) apiRequestMonitor.getMetricsMap().get("daily_failed")).intValue());

        // But total shouldn't be affected
        assertEquals(2L, (Long) apiRequestMonitor.getMetricsMap().get("total_requests"));
        assertEquals(1L, (Long) apiRequestMonitor.getMetricsMap().get("total_successful"));
        assertEquals(1L, (Long) apiRequestMonitor.getMetricsMap().get("total_failed"));
    }

    @Test
    public void testThreadSafety() throws Exception {
        int threadCount = 10;
        int callsPerThread = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Run multiple threads making concurrent calls
        for (int i = 0; i < threadCount; i++) {
            final String endpoint = "test/endpoint-" + i;
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < callsPerThread; j++) {
                        if (j % 5 == 0) { // Every 5th call is a failure
                            apiRequestMonitor.recordFailedRequest(endpoint, "Intentional concurrent tests to observe thread safety - success");
                        } else {
                            apiRequestMonitor.recordSuccessfulRequest(endpoint);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        latch.await();
        executorService.shutdown();

        // Verify counts
        int totalExpectedCalls = threadCount * callsPerThread;
        int expectedFailures = totalExpectedCalls / 5; // Every 5th call is a failure
        int expectedSuccesses = totalExpectedCalls - expectedFailures;

        assertEquals(totalExpectedCalls, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_requests")).intValue());
        assertEquals(expectedSuccesses, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_successful")).intValue());
        assertEquals(expectedFailures, ((Number) apiRequestMonitor.getMetricsMap().get("hourly_failed")).intValue());
        assertEquals(totalExpectedCalls, ((Number) apiRequestMonitor.getMetricsMap().get("daily_requests")).intValue());
        assertEquals(totalExpectedCalls, apiRequestMonitor.getTotalRequests()); // Direct getter for total

        // Check endpoint tracking without unchecked casts
        Object endpointsObj = apiRequestMonitor.getMetricsMap().get("endpoints");
        assertNotNull(endpointsObj);
        assertTrue(endpointsObj instanceof Map);
        Map<?, ?> endpointCalls = (Map<?, ?>) endpointsObj;
        assertEquals(threadCount, endpointCalls.size());

        // Each endpoint should have 'callsPerThread' calls in total (success + failure)
        for (int i = 0; i < threadCount; i++) {
            String endpoint = "test/endpoint-" + i;
            assertTrue(endpointCalls.containsKey(endpoint));
            assertEquals(callsPerThread, ((Number) endpointCalls.get(endpoint)).intValue());
        }
    }

    @Test
    public void testGetCurrentMetricsReport() {
        // Record some activity
        apiRequestMonitor.recordSuccessfulRequest("test/endpoint-1");
        apiRequestMonitor.recordSuccessfulRequest("test/endpoint-2");
        apiRequestMonitor.recordFailedRequest("test/endpoint-1", "Test error");

        // Get the report
        String report = apiRequestMonitor.generateReport();

        // Verify the report contains expected information
        assertNotNull(report);
        assertTrue(report.contains("API Request Monitor Report")); // Updated report title
        assertTrue(report.contains("Hourly: 3 requests (2 successful, 1 failed)")); // Updated format
        assertTrue(report.contains("Daily: 3 requests (2 successful, 1 failed)")); // Updated format
        assertTrue(report.contains("Total: 3 requests (2 successful, 1 failed)")); // Updated format
        assertTrue(report.contains("Endpoint Counts:")); // Updated section title
        assertTrue(report.contains("test/endpoint-1: 2 requests"));
        assertTrue(report.contains("test/endpoint-2: 1 requests"));
    }

    @Test
    void should_KeepAuthenticatedCircuitOpen_When_ResetAndRateLimitFailureMeetAtMidnight() {
        LocalDate resetDate = LocalDate.of(2026, 1, 2);
        LocalDateTime previousDayFailure = resetDate.minusDays(1).atTime(23, 59);
        LocalDateTime resetDayFailure = resetDate.atStartOfDay();

        // Atomic state transitions are linearizable, so both serial orders cover a race without timing sleeps.
        ApiCircuitBreakerService failureBeforeReset = new ApiCircuitBreakerService();
        failureBeforeReset.recordRateLimitFailure(previousDayFailure);
        failureBeforeReset.recordRateLimitFailure(resetDayFailure);
        assertFalse(failureBeforeReset.isApiCallAllowed(resetDate));
        assertTrue(failureBeforeReset.isApiCallAllowed(resetDate.plusDays(1)));

        ApiCircuitBreakerService resetBeforeFailure = new ApiCircuitBreakerService();
        resetBeforeFailure.recordRateLimitFailure(previousDayFailure);
        assertTrue(resetBeforeFailure.isApiCallAllowed(resetDate));
        resetBeforeFailure.recordRateLimitFailure(resetDayFailure);
        assertFalse(resetBeforeFailure.isApiCallAllowed(resetDate));
        assertTrue(resetBeforeFailure.isApiCallAllowed(resetDate.plusDays(1)));
    }

    @Test
    void should_LogFallbackCircuitTransitionAtWarn_When_FallbackQuotaIsExhausted() {
        ApiCircuitBreakerService circuitBreakerService = new ApiCircuitBreakerService();
        Logger circuitBreakerLogger = (Logger) LoggerFactory.getLogger(ApiCircuitBreakerService.class);
        ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
        logEvents.start();
        circuitBreakerLogger.addAppender(logEvents);

        try {
            circuitBreakerService.recordFallbackRateLimitFailure(LocalDateTime.of(2026, 1, 1, 12, 0));

            List<ILoggingEvent> circuitOpenEvents = logEvents.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Fallback circuit OPENED due to rate limit"))
                    .toList();

            assertEquals(1, circuitOpenEvents.size());
            assertEquals(Level.WARN, circuitOpenEvents.getFirst().getLevel());
            assertEquals(0, logEvents.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .count());
        } finally {
            circuitBreakerLogger.detachAppender(logEvents);
            logEvents.stop();
        }
    }

    @Test
    void should_LogAuthenticatedCircuitTransitionAtError_When_AuthenticatedQuotaIsExhausted() {
        ApiCircuitBreakerService circuitBreakerService = new ApiCircuitBreakerService();
        Logger circuitBreakerLogger = (Logger) LoggerFactory.getLogger(ApiCircuitBreakerService.class);
        ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
        logEvents.start();
        circuitBreakerLogger.addAppender(logEvents);

        try {
            circuitBreakerService.recordRateLimitFailure(LocalDateTime.of(2026, 1, 1, 12, 0));

            List<ILoggingEvent> circuitOpenEvents = logEvents.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Circuit breaker OPENED due to rate limit"))
                    .toList();

            assertEquals(1, circuitOpenEvents.size());
            assertEquals(Level.ERROR, circuitOpenEvents.getFirst().getLevel());
        } finally {
            circuitBreakerLogger.detachAppender(logEvents);
            logEvents.stop();
        }
    }

    @Test
    void should_KeepFallbackCircuitOpen_When_ResetAndRateLimitFailureMeetAtMidnight() {
        LocalDate resetDate = LocalDate.of(2026, 1, 2);
        LocalDateTime previousDayFailure = resetDate.minusDays(1).atTime(23, 59);
        LocalDateTime resetDayFailure = resetDate.atStartOfDay();

        // Atomic state transitions are linearizable, so both serial orders cover a race without timing sleeps.
        ApiCircuitBreakerService failureBeforeReset = new ApiCircuitBreakerService();
        failureBeforeReset.recordFallbackRateLimitFailure(previousDayFailure);
        failureBeforeReset.recordFallbackRateLimitFailure(resetDayFailure);
        assertFalse(failureBeforeReset.isFallbackAllowed(resetDate));
        assertTrue(failureBeforeReset.isFallbackAllowed(resetDate.plusDays(1)));

        ApiCircuitBreakerService resetBeforeFailure = new ApiCircuitBreakerService();
        resetBeforeFailure.recordFallbackRateLimitFailure(previousDayFailure);
        assertTrue(resetBeforeFailure.isFallbackAllowed(resetDate));
        resetBeforeFailure.recordFallbackRateLimitFailure(resetDayFailure);
        assertFalse(resetBeforeFailure.isFallbackAllowed(resetDate));
        assertTrue(resetBeforeFailure.isFallbackAllowed(resetDate.plusDays(1)));
    }
}
