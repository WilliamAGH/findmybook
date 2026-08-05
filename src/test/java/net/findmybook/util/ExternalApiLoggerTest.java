package net.findmybook.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.findmybook.service.NewYorkTimesService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class ExternalApiLoggerTest {

    @Test
    public void testLogHttpRequestMasksApiKey() {
        Logger mockLogger = mock(Logger.class);
        String url = "https://www.googleapis.com/books/v1/volumes?q=Java&key=AIzaSyA_SECRET_KEY";

        ExternalApiLogger.logHttpRequest(mockLogger, "GET", url, true);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).info(messageCaptor.capture());

        String logMessage = messageCaptor.getValue();
        assertFalse(logMessage.contains("AIzaSyA_SECRET_KEY"), "Log message should not contain the actual API key");
        assertTrue(logMessage.contains("key=********"), "Log message should contain masked API key");
    }

    @Test
    public void testLogHttpResponseMasksApiKey() {
        Logger mockLogger = mock(Logger.class);
        String url = "https://www.googleapis.com/books/v1/volumes?q=Java&key=AIzaSyA_SECRET_KEY";

        ExternalApiLogger.logHttpResponse(mockLogger, 200, url, 1024);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).info(messageCaptor.capture());

        String logMessage = messageCaptor.getValue();
        assertFalse(logMessage.contains("AIzaSyA_SECRET_KEY"), "Log message should not contain the actual API key");
        assertTrue(logMessage.contains("key=********"), "Log message should contain masked API key");
    }

    @Test
    public void testLogApiCallFailureMasksApiKey() {
        Logger mockLogger = mock(Logger.class);
        String reason = "TOKEN=failure-secret diagnostics:API_Key=colon-secret api-key=space-secret status=503";

        ExternalApiLogger.logApiCallFailure(mockLogger, "GoogleBooks", "FETCH_VOLUME", "volume lookup", reason);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).warn(messageCaptor.capture());

        String logMessage = messageCaptor.getValue();
        assertFalse(logMessage.contains("failure-secret"), "Log message should redact an assignment at fragment start");
        assertFalse(logMessage.contains("colon-secret"), "Log message should redact an assignment after a colon");
        assertFalse(logMessage.contains("space-secret"), "Log message should redact an assignment after whitespace");
        assertTrue(logMessage.contains("TOKEN=********"), "Log message should preserve the rendered credential key");
        assertTrue(logMessage.contains("diagnostics:API_Key=********"), "Log message should preserve diagnostic labels");
        assertTrue(logMessage.contains("api-key=********"), "Log message should preserve whitespace-separated fields");
        assertTrue(logMessage.contains("status=503"), "Log message should preserve nonsecret diagnostics");
    }

    @Test
    public void testSanitizeHandlesMultipleParametersAndPositions() {
        Logger mockLogger = mock(Logger.class);

        // key at the end
        String url1 = "https://api.example.com/data?q=test&key=secret123";
        ExternalApiLogger.logHttpRequest(mockLogger, "GET", url1, true);

        // api_key at the beginning
        String url2 = "https://api.example.com/data?api_key=secret456&q=test";
        ExternalApiLogger.logHttpRequest(mockLogger, "GET", url2, true);

        // token in the middle
        String url3 = "https://api.example.com/data?a=1&token=secret789&b=2";
        ExternalApiLogger.logHttpRequest(mockLogger, "GET", url3, true);

        // multiple sensitive params
        String url4 = "https://api.example.com/data?key=s1&token=s2";
        ExternalApiLogger.logHttpRequest(mockLogger, "GET", url4, true);

        // NYT uses a hyphenated query parameter name
        String url5 = "https://api.nytimes.com/svc/books/v3/lists/overview.json?api-key=nyt-secret";
        ExternalApiLogger.logHttpRequest(mockLogger, "GET", url5, true);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(5)).info(messageCaptor.capture());

        List<String> messages = messageCaptor.getAllValues();

        assertTrue(messages.get(0).contains("key=********"), "Should mask key at the end");
        assertFalse(messages.get(0).contains("secret123"));

        assertTrue(messages.get(1).contains("api_key=********"), "Should mask api_key at the beginning");
        assertFalse(messages.get(1).contains("secret456"));

        assertTrue(messages.get(2).contains("token=********"), "Should mask token in the middle");
        assertFalse(messages.get(2).contains("secret789"));
        assertTrue(messages.get(2).contains("&b=2"), "Should preserve subsequent parameters");

        assertTrue(messages.get(3).contains("key=********"), "Should mask multiple params (key)");
        assertTrue(messages.get(3).contains("token=********"), "Should mask multiple params (token)");
        assertFalse(messages.get(3).contains("s1"));
        assertFalse(messages.get(3).contains("s2"));

        assertTrue(messages.get(4).contains("api-key=********"), "Should mask api-key");
        assertFalse(messages.get(4).contains("nyt-secret"));
    }

    @Test
    void should_PreserveFailureCauseWithoutCredentialInReactorCheckpoint_When_NytRequestFails() {
        String apiKey = "nyt-secret-sentinel";
        AtomicReference<URI> outboundUri = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            outboundUri.set(request.url());
            return Mono.error(new IllegalStateException("network failure"));
        });
        NewYorkTimesService service = new NewYorkTimesService(
            builder,
            "https://api.nytimes.com/svc/books/v3",
            apiKey,
            null
        );
        ch.qos.logback.classic.Logger serviceLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NewYorkTimesService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        try {
            StepVerifier.create(service.fetchBestsellerListOverview(LocalDate.of(2026, 8, 2)))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof IllegalStateException);
                    assertTrue(error.getMessage().contains("IllegalStateException"));
                    assertFalse(error.getMessage().contains(apiKey));
                    assertTrue(error.getCause() instanceof IllegalStateException);
                    String diagnostic = error.getCause().toString()
                        + List.of(error.getCause().getSuppressed());
                    assertFalse(diagnostic.contains(apiKey));
                    assertTrue(diagnostic.contains("Request to GET https://api.nytimes.com"));
                })
                .verify();

            assertTrue(outboundUri.get().getQuery().contains("api-key=" + apiKey));
            assertTrue(outboundUri.get().getQuery().contains("published_date=2026-08-02"));
            assertTrue(appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(apiKey)));
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_RedactCredentialFromCompleteRenderedEvent_When_ThrowableContainsRequestUrl() {
        String apiKey = "nyt-rendered-secret-sentinel";
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.getInstanceConverterMap().put(
            "maskSensitiveQueryParameters",
            ExternalApiLogger.SensitiveQueryParameterConverter::new
        );
        layout.setPattern("%maskSensitiveQueryParameters(%msg%n%ex)");
        layout.start();
        try {
            IllegalStateException requestFailure = new IllegalStateException(
                "credentials:API-KEY=" + apiKey + " published_date=2026-08-02"
            );
            LoggingEvent event = new LoggingEvent(
                ExternalApiLoggerTest.class.getName(),
                context.getLogger("credential-redaction-test"),
                Level.ERROR,
                "ToKeN=rendered-message-secret NYT refresh failed",
                requestFailure,
                null
            );

            String renderedEvent = layout.doLayout(event);

            assertFalse(renderedEvent.contains(apiKey));
            assertFalse(renderedEvent.contains("rendered-message-secret"));
            assertTrue(renderedEvent.contains("API-KEY=********"));
            assertTrue(renderedEvent.contains("ToKeN=********"));
            assertTrue(renderedEvent.contains("published_date=2026-08-02"));
            assertTrue(renderedEvent.contains("IllegalStateException"));
            assertTrue(renderedEvent.contains("NYT refresh failed"));
        } finally {
            layout.stop();
            context.stop();
        }
    }
}
