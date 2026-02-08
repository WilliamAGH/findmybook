package net.findmybook.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.WebRequest;
import net.findmybook.util.ApplicationConstants;
import java.util.Map;

/**
 * Handles the shared error endpoint for both HTML pages and JSON API clients.
 */
@Controller
@ConditionalOnWebApplication
public class ErrorDiagnosticsController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ErrorDiagnosticsController.class);

    private final ErrorAttributes errorAttributes;
    private final boolean spaFrontendEnabled;
    private final boolean includeStackTrace;

    public ErrorDiagnosticsController(ErrorAttributes errorAttributes,
                                      @Value("${app.frontend.spa.enabled:true}") boolean spaFrontendEnabled,
                                      @Value("${app.error-diagnostics.include-stacktrace:false}") boolean includeStackTrace) {
        this.errorAttributes = errorAttributes;
        this.spaFrontendEnabled = spaFrontendEnabled;
        this.includeStackTrace = includeStackTrace;
    }

    @RequestMapping(value = "/error", produces = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"})
    public ResponseEntity<ErrorJsonResponse> handleJsonError(HttpServletResponse response, WebRequest webRequest) {
        ErrorContext context = readErrorContext(webRequest);
        response.setStatus(context.statusCode());
        ErrorJsonResponse payload = new ErrorJsonResponse(
            context.timestamp(),
            context.statusCode(),
            context.error(),
            context.message(),
            context.path()
        );
        return ResponseEntity.status(context.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload);
    }

    @RequestMapping("/error")
    public String handleHtmlError(HttpServletResponse response,
                                  WebRequest webRequest,
                                  Model model) {
        ErrorContext context = readErrorContext(webRequest);
        response.setStatus(context.statusCode());
        populateErrorModel(model, context);

        if (missingDiagnosticMessage(context.message()) && !context.exceptionClassName().isBlank()) {
            String exceptionType = context.exceptionClassName();
            int lastDot = exceptionType.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < exceptionType.length() - 1) {
                exceptionType = exceptionType.substring(lastDot + 1);
            }
            model.addAttribute("exceptionType", exceptionType);
        }

        if (context.statusCode() == HttpStatus.NOT_FOUND.value()) {
            if (spaFrontendEnabled) {
                String requestPath = !context.path().isBlank() ? context.path() : "/";
                model.addAttribute("title", "Page Not Found");
                model.addAttribute("description", "The page you requested could not be found.");
                model.addAttribute("keywords", "404, page not found");
                model.addAttribute("canonicalUrl", ApplicationConstants.Urls.BASE_URL + requestPath);
                model.addAttribute("ogImage", "/images/og-logo.png");
                return "spa/index";
            }
            return "error/404";
        }
        return "error_diagnostics";
    }

    private void populateErrorModel(Model model, ErrorContext context) {
        model.addAttribute("timestamp", context.timestamp());
        model.addAttribute("status", context.statusCode());
        model.addAttribute("error", context.error());
        model.addAttribute("message", context.message());
        model.addAttribute("trace", context.trace());
        model.addAttribute("path", context.path());
    }

    private ErrorContext readErrorContext(WebRequest webRequest) {
        ErrorAttributeOptions options = ErrorAttributeOptions.of(
            ErrorAttributeOptions.Include.MESSAGE,
            ErrorAttributeOptions.Include.EXCEPTION,
            ErrorAttributeOptions.Include.BINDING_ERRORS
        );
        if (includeStackTrace) {
            options = options.including(ErrorAttributeOptions.Include.STACK_TRACE);
        }
        Map<String, ?> errors = errorAttributes.getErrorAttributes(webRequest, options);
        int statusCode = resolveStatusCode(errors);
        return new ErrorContext(
            statusCode,
            textOrEmpty(errors, "timestamp"),
            textOrEmpty(errors, "error"),
            textOrEmpty(errors, "message"),
            textOrEmpty(errors, "path"),
            includeStackTrace ? textOrEmpty(errors, "trace") : "",
            textOrEmpty(errors, "exception")
        );
    }

    private int resolveStatusCode(Map<String, ?> errors) {
        if (errors.get("status") instanceof Number numericStatus) {
            return numericStatus.intValue();
        }
        String stringStatus = textOrEmpty(errors, "status");
        if (!stringStatus.isBlank()) {
            try {
                return Integer.parseInt(stringStatus);
            } catch (NumberFormatException ex) {
                log.warn("Unparseable error status '{}', defaulting to 500", stringStatus);
                return HttpStatus.INTERNAL_SERVER_ERROR.value();
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private String textOrEmpty(Map<String, ?> source, String key) {
        return source.get(key) != null ? String.valueOf(source.get(key)) : "";
    }

    private boolean missingDiagnosticMessage(String message) {
        return message.isBlank() || "No message available".equals(message);
    }

    private record ErrorContext(
        int statusCode,
        String timestamp,
        String error,
        String message,
        String path,
        String trace,
        String exceptionClassName
    ) {
    }

    private record ErrorJsonResponse(
        String timestamp,
        int status,
        String error,
        String message,
        String path
    ) {
    }
}
