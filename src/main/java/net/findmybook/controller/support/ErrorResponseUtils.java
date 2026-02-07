package net.findmybook.controller.support;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Small helper for producing consistent error payloads across controllers.
 */
public final class ErrorResponseUtils {

    private ErrorResponseUtils() {
        // Utility class
    }

    public static Map<String, String> errorBody(String message) {
        return errorBody(message, null);
    }

    public static Map<String, String> errorBody(String message, String detail) {
        Map<String, String> body = new HashMap<>();
        body.put("error", message);
        if (detail != null && !detail.isBlank()) {
            body.put("message", detail);
        }
        return body;
    }

    public static ResponseEntity<Object> error(HttpStatus status, String message) {
        return error(status, message, null);
    }

    public static ResponseEntity<Object> error(HttpStatus status, String message, String detail) {
        return ResponseEntity.status(status).body(errorBody(message, detail));
    }

    public static ResponseEntity<Object> internalServerError(String message, String detail) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, message, detail);
    }

    public static ResponseEntity<Object> badRequest(String message, String detail) {
        return error(HttpStatus.BAD_REQUEST, message, detail);
    }
}
