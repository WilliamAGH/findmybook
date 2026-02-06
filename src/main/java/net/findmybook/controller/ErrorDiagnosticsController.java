/**
 * Controller for handling application errors and providing diagnostic information
 *
 * @author William Callahan
 *
 * Features:
 * - Implements Spring Boot's ErrorController interface
 * - Captures detailed error information including stack traces
 * - Renders user-friendly error diagnostic page
 * - Provides enhanced error details for debugging
 * - Extracts exception type information when available
 */

package net.findmybook.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.WebRequest;
import java.util.List;
import java.util.Map;

@Controller
@ConditionalOnWebApplication
public class ErrorDiagnosticsController implements ErrorController {

    private final ErrorAttributes errorAttributes;

    public ErrorDiagnosticsController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    /**
     * Handles all application errors and prepares diagnostic information
     * - Extracts error attributes from the request
     * - Includes stack traces and exception details
     * - Populates model with error information for template rendering
     * - Extracts exception class name when available
     * 
     * @param request WebRequest containing error information
     * @param model Spring MVC model for view rendering
     * @return Template name for error page
     */
    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request,
                              HttpServletResponse response,
                              WebRequest webRequest,
                              Model model) {
        Map<String, Object> errors = errorAttributes.getErrorAttributes(webRequest, 
            ErrorAttributeOptions.of(
                ErrorAttributeOptions.Include.STACK_TRACE, 
                ErrorAttributeOptions.Include.MESSAGE, 
                ErrorAttributeOptions.Include.EXCEPTION,
                ErrorAttributeOptions.Include.BINDING_ERRORS
            ));
        int statusCode = resolveStatusCode(errors.get("status"));
        response.setStatus(statusCode);

        if (clientPrefersJson(request)) {
            return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errors);
        }

        model.addAttribute("timestamp", errors.get("timestamp"));
        model.addAttribute("status", errors.get("status"));
        model.addAttribute("error", errors.get("error"));
        model.addAttribute("message", errors.get("message"));
        model.addAttribute("trace", errors.get("trace"));
        model.addAttribute("path", errors.get("path"));

        Object message = errors.get("message");
        if ((message == null || message.toString().isEmpty() || "No message available".equals(message)) && errors.get("exception") != null) {
            try {
                String exceptionClassName = (String) errors.get("exception");
                model.addAttribute("exceptionType", Class.forName(exceptionClassName).getSimpleName());
            } catch (ClassNotFoundException e) {
                // Ignore if class not found
            }
        }
        
        if (statusCode == HttpStatus.NOT_FOUND.value()) {
            return "error/404";
        }

        return "error_diagnostics";
    }

    private int resolveStatusCode(Object statusValue) {
        if (statusValue instanceof Number numericStatus) {
            return numericStatus.intValue();
        }
        if (statusValue instanceof String stringStatus) {
            try {
                return Integer.parseInt(stringStatus);
            } catch (NumberFormatException ignored) {
                return HttpStatus.INTERNAL_SERVER_ERROR.value();
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private boolean clientPrefersJson(HttpServletRequest request) {
        String acceptHeader = request.getHeader(HttpHeaders.ACCEPT);
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        try {
            List<MediaType> acceptedTypes = MediaType.parseMediaTypes(acceptHeader);
            for (MediaType acceptedType : acceptedTypes) {
                if (acceptedType.isCompatibleWith(MediaType.TEXT_HTML)) {
                    return false;
                }
                if (acceptedType.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || acceptedType.getSubtype().endsWith("+json")) {
                    return true;
                }
            }
            return false;
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
    }
}
