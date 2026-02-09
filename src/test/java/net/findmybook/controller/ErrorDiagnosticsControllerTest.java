package net.findmybook.controller;

import jakarta.servlet.RequestDispatcher;
import java.util.Map;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SPA-shell error view resolution behavior.
 */
class ErrorDiagnosticsControllerTest {

    @Test
    @DisplayName("Error view resolver returns SPA shell for 404 HTML responses")
    void errorDiagnostics_resolvesNotFoundSpaShellForHtml() throws Exception {
        LocalDiskCoverCacheService localDiskCoverCacheService = Mockito.mock(LocalDiskCoverCacheService.class);
        BookSeoMetadataService seoMetadataService = new BookSeoMetadataService(localDiskCoverCacheService);
        ErrorDiagnosticsController resolver = errorDiagnosticsController(seoMetadataService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/missing-page");

        ModelAndView modelAndView = resolver.resolveErrorView(
            request,
            HttpStatus.NOT_FOUND,
            Map.of("path", "/missing-page")
        );

        View view = modelAndView.getView();
        assertNotNull(view);
        MockHttpServletResponse response = new MockHttpServletResponse();
        view.render(Map.of(), request, response);

        assertEquals(MediaType.TEXT_HTML_VALUE, response.getContentType());
        assertTrue(response.getContentAsString().contains("Page Not Found | findmybook"));
    }

    @Test
    @DisplayName("Error view resolver falls back to /error when request path is unavailable")
    void errorDiagnostics_fallsBackToRequestUriWhenPathMissing() throws Exception {
        LocalDiskCoverCacheService localDiskCoverCacheService = Mockito.mock(LocalDiskCoverCacheService.class);
        BookSeoMetadataService seoMetadataService = new BookSeoMetadataService(localDiskCoverCacheService);
        ErrorDiagnosticsController resolver = errorDiagnosticsController(seoMetadataService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

        ModelAndView modelAndView = resolver.resolveErrorView(
            request,
            HttpStatus.INTERNAL_SERVER_ERROR,
            Map.of()
        );

        View view = modelAndView.getView();
        assertNotNull(view);
        MockHttpServletResponse response = new MockHttpServletResponse();
        view.render(Map.of(), request, response);

        assertEquals(MediaType.TEXT_HTML_VALUE, response.getContentType());
        assertTrue(response.getContentAsString().contains("Error 500 | findmybook"));
    }

    private ErrorDiagnosticsController errorDiagnosticsController(BookSeoMetadataService seoMetadataService) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("bookSeoMetadataService", seoMetadataService);
        return new ErrorDiagnosticsController(beanFactory.getBeanProvider(BookSeoMetadataService.class));
    }
}
