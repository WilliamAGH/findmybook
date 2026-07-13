package net.findmybook.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Content Security Policy header built by {@link SecurityConfig}
 * includes the directives required for the cover relay feature and analytics.
 */
class SecurityConfigCspTest {

    @Test
    @DisplayName("connect-src includes Google Books origins for browser cover relay")
    void should_IncludeGoogleBooksOrigins_When_CspBuilt() {
        String csp = SecurityConfig.buildContentSecurityPolicy(false, false);

        assertThat(csp).contains("connect-src");
        assertThat(csp).contains("https://books.google.com");
        assertThat(csp).contains("https://books.googleusercontent.com");
    }

    @Test
    @DisplayName("connect-src retains Google Books origins when analytics are enabled")
    void should_RetainGoogleBooksOrigins_When_AnalyticsEnabled() {
        String csp = SecurityConfig.buildContentSecurityPolicy(true, true);

        assertThat(csp).contains("https://books.google.com");
        assertThat(csp).contains("https://books.googleusercontent.com");
    }

    @Test
    @DisplayName("connect-src includes analytics origins when Simple Analytics is enabled")
    void should_IncludeSimpleAnalyticsOrigins_When_SimpleAnalyticsEnabled() {
        String csp = SecurityConfig.buildContentSecurityPolicy(false, true);

        assertThat(csp).contains("https://queue.simpleanalyticscdn.com");
        assertThat(csp).contains("https://scripts.simpleanalyticscdn.com");
    }

    @Test
    @DisplayName("CSP includes Clicky HTTPS origins when Clicky is enabled")
    void should_IncludeClickyOrigins_When_ClickyEnabled() {
        String csp = SecurityConfig.buildContentSecurityPolicy(true, false);

        assertThat(csp).contains("https://static.getclicky.com");
        assertThat(csp).contains("https://in.getclicky.com");
    }

    @Test
    @DisplayName("CSP excludes Clicky origins when Clicky is disabled")
    void should_ExcludeClickyOrigins_When_ClickyDisabled() {
        String csp = SecurityConfig.buildContentSecurityPolicy(false, false);

        assertThat(csp).doesNotContain("getclicky.com");
    }

    @Test
    void should_NotWritePrivateCacheHeaders_When_RequestIsOpenGraphImage() {
        HeaderWriter writer = SecurityConfig.nonOpenGraphCacheControlHeaderWriter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/pages/og/book/test-book");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeHeaders(request, response);

        assertThat(response.getHeaders("Cache-Control")).isEmpty();
        assertThat(response.getHeader("Pragma")).isNull();
        assertThat(response.getHeader("Expires")).isNull();
    }

    @Test
    void should_WritePrivateCacheHeaders_When_RequestIsNotOpenGraphImage() {
        HeaderWriter writer = SecurityConfig.nonOpenGraphCacheControlHeaderWriter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/books/search");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeHeaders(request, response);

        assertThat(response.getHeaders("Cache-Control"))
            .containsExactly("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("Expires")).isEqualTo("0");
    }
}
