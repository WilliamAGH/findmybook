package net.findmybook.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.service.BookSeoMetadataService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.Set;

/**
 * Resolves HTML error views for Boot's global error controller by rendering the
 * existing SEO-aware SPA shell.
 *
 * <p>JSON and ProblemDetail responses remain the default for API/static paths,
 * while page-like browser navigations are rendered with the shared SPA shell.
 */
@Component
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication
public class ErrorDiagnosticsController implements ErrorViewResolver {

    private static final Set<String> STATIC_FILE_EXTENSIONS = Set.of(
        "js", "css", "png", "jpg", "jpeg", "gif", "svg", "ico",
        "woff", "woff2", "ttf", "eot", "map", "json", "xml", "webmanifest"
    );

    private final ObjectProvider<BookSeoMetadataService> bookSeoMetadataServiceProvider;

    /**
     * Creates the HTML error view resolver that renders the shared SPA shell.
     *
     * @param bookSeoMetadataServiceProvider provider for route-specific SEO metadata
     */
    public ErrorDiagnosticsController(ObjectProvider<BookSeoMetadataService> bookSeoMetadataServiceProvider) {
        this.bookSeoMetadataServiceProvider = bookSeoMetadataServiceProvider;
    }

    /**
     * Resolves the HTML error response view while preserving the HTTP status chosen
     * by Boot's global error controller.
     *
     * @param request servlet request that triggered fallback error dispatch
     * @param status resolved HTTP status for the failing request
     * @param model Boot-provided error attribute model
     * @return model-and-view that writes the SEO-aware SPA shell HTML
     */
    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        BookSeoMetadataService bookSeoMetadataService = bookSeoMetadataServiceProvider.getIfAvailable();
        if (bookSeoMetadataService == null) {
            return null;
        }
        String requestPath = resolveRequestPath(request, model);
        SeoMetadata metadata = status == HttpStatus.NOT_FOUND
            ? bookSeoMetadataService.notFoundMetadata(requestPath)
            : bookSeoMetadataService.errorMetadata(status.value(), requestPath);

        String html = bookSeoMetadataService.renderSpaShell(metadata);
        return new ModelAndView(new SpaShellErrorHtmlView(html));
    }

    /**
     * Handles unresolved resources with content-aware behavior.
     *
     * <p>Browser page navigations receive the SPA 404 shell while API/static
     * lookups keep RFC 9457 ProblemDetail responses.
     *
     * @param exception missing-resource exception raised by MVC
     * @param request current servlet request
     * @return HTML 404 response for page-like requests, or ProblemDetail for non-page requests
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleNoResourceFoundHtml(NoResourceFoundException exception, HttpServletRequest request) {
        BookSeoMetadataService bookSeoMetadataService = bookSeoMetadataServiceProvider.getIfAvailable();
        if (bookSeoMetadataService == null) {
            return ResponseEntity.status(exception.getStatusCode())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.getBody());
        }

        String requestPath = resolveRequestPath(request, Map.of());
        if (!shouldRenderSpaNotFound(request, requestPath, bookSeoMetadataService)) {
            return ResponseEntity.status(exception.getStatusCode())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.getBody());
        }

        String html = bookSeoMetadataService.renderSpaShell(bookSeoMetadataService.notFoundMetadata(requestPath));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.TEXT_HTML)
            .body(html);
    }

    private String resolveRequestPath(HttpServletRequest request, Map<String, Object> model) {
        Object modelPath = model.get("path");
        if (modelPath != null) {
            String pathValue = String.valueOf(modelPath).trim();
            if (!pathValue.isEmpty()) {
                return pathValue;
            }
        }

        Object dispatchPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (dispatchPath != null) {
            String pathValue = String.valueOf(dispatchPath).trim();
            if (!pathValue.isEmpty()) {
                return pathValue;
            }
        }

        String requestUri = request.getRequestURI();
        return requestUri != null && !requestUri.trim().isEmpty() ? requestUri : "/";
    }

    private boolean isPassthroughPath(String requestPath, BookSeoMetadataService bookSeoMetadataService) {
        String normalizedPath = requestPath.startsWith("/") ? requestPath : "/" + requestPath;
        for (String prefix : bookSeoMetadataService.routeManifest().passthroughPrefixes()) {
            if (normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldRenderSpaNotFound(HttpServletRequest request,
                                            String requestPath,
                                            BookSeoMetadataService bookSeoMetadataService) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader != null) {
            String normalizedAccept = acceptHeader.toLowerCase();
            if (!normalizedAccept.contains(MediaType.TEXT_HTML_VALUE) && !normalizedAccept.contains("*/*")) {
                return false;
            }
        }
        if (isPassthroughPath(requestPath, bookSeoMetadataService)) {
            return false;
        }
        if (requestPath == null || requestPath.isBlank()) {
            return true;
        }
        if (requestPath.startsWith("/frontend/") || requestPath.startsWith("/webjars/")) {
            return false;
        }
        return !hasStaticFileExtension(requestPath);
    }

    private boolean hasStaticFileExtension(String requestPath) {
        int lastSlash = requestPath.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? requestPath.substring(lastSlash + 1) : requestPath;
        int dotIndex = lastSegment.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == lastSegment.length() - 1) {
            return false;
        }
        String extension = lastSegment.substring(dotIndex + 1).toLowerCase(java.util.Locale.ROOT);
        return STATIC_FILE_EXTENSIONS.contains(extension);
    }

    private record SpaShellErrorHtmlView(String html) implements View {
        @Override
        public String getContentType() {
            return MediaType.TEXT_HTML_VALUE;
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws Exception {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.getWriter().write(html);
        }
    }
}
