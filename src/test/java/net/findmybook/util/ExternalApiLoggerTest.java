package net.findmybook.util;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.List;

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
        String url = "https://www.googleapis.com/books/v1/volumes?q=Java&key=AIzaSyA_SECRET_KEY";

        ExternalApiLogger.logApiCallFailure(mockLogger, "GoogleBooks", "FETCH_VOLUME", url, "Error message");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).warn(messageCaptor.capture());

        String logMessage = messageCaptor.getValue();
        assertFalse(logMessage.contains("AIzaSyA_SECRET_KEY"), "Log message should not contain the actual API key");
        assertTrue(logMessage.contains("query='https://www.googleapis.com/books/v1/volumes?q=Java&key=********'"), "Log message should contain masked API key in query field");
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

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(4)).info(messageCaptor.capture());

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
    }
}
