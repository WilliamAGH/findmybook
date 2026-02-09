/**
 * Custom implementation of Spring Security's BasicAuthenticationEntryPoint
 * This class is responsible for commencing the HTTP Basic authentication scheme
 * When an unauthenticated user attempts to access a protected resource requiring Basic Auth,
 * this entry point is invoked to send a 401 Unauthorized response, prompting the client
 * for credentials
 *
 * Key Features:
 * - Returns an RFC 9457 ProblemDetail body on authentication failure for admin endpoints
 * - Specifies the WWW-Authenticate header with a custom realm name
 * - Provides clear instructions to the user on how to authenticate, including the expected header format
 *   and the environment variable for the admin password
 *
 * @author William Callahan
 */
package net.findmybook.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Customizes the response for unauthorized access to endpoints protected by HTTP Basic authentication
 * Instead of a generic 401 page, it returns a ProblemDetail response with guidance on how to authenticate,
 * specifically tailored for the admin API, including the expected username and the environment
 * variable for the password
 */
@Component
public class CustomBasicAuthenticationEntryPoint extends BasicAuthenticationEntryPoint {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authEx) throws IOException {
        response.addHeader("WWW-Authenticate", "Basic realm=\"" + getRealmName() + "\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "HTTP Basic Authentication required for admin endpoints."
        );
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty(
            "expectedHeader",
            "Authorization: Basic <base64_encoded_username:password>"
        );
        problemDetail.setProperty("username", "admin");
        problemDetail.setProperty(
            "passwordEnvironmentVariable",
            "APP_SECURITY_ADMIN_PASSWORD (or app.security.admin.password property)"
        );

        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(problemDetail));
    }

    @Override
    public void afterPropertiesSet() {
        setRealmName("BookRecommendationAdmin");
        super.afterPropertiesSet();
    }
}
