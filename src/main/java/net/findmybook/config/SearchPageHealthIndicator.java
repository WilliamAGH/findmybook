/**
 * Health indicator for checking search page availability
 *
 * @author William Callahan
 *
 * Features:
 * - Verifies search functionality is operational by checking search page
 * - Uses a predefined query term for consistent health checks
 * - Dynamically configures itself when application server starts
 * - Reports search page status to Spring Boot Actuator health endpoint
 */
package net.findmybook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
@Component("searchPageHealthIndicator")
class SearchPageHealthIndicator implements ReactiveHealthIndicator, ApplicationListener<WebServerInitializedEvent> {
    private volatile WebPageHealthIndicator delegate;
    private final WebClient.Builder webClientBuilder;
    private final boolean reportErrorsAsDown;
    private static final String HEALTHCHECK_QUERY = "healthcheck";
    private static final String SEARCH_HEALTH_PATH = "/search?query=" + HEALTHCHECK_QUERY;
    private volatile boolean isConfigured = false;
    private static final Logger logger = LoggerFactory.getLogger(SearchPageHealthIndicator.class);

    /**
     * Constructs SearchPageHealthIndicator with configurable error handling
     *
     * @param webClientBuilder builder for creating WebClient instances
     * @param reportErrorsAsDown whether to report errors as DOWN instead of UNKNOWN
     */
    public SearchPageHealthIndicator(WebClient.Builder webClientBuilder,
                                   @Value("${healthcheck.report-errors-as-down:true}") boolean reportErrorsAsDown) {
        this.webClientBuilder = webClientBuilder;
        this.reportErrorsAsDown = reportErrorsAsDown;
        this.delegate = unconfiguredDelegate();
    }

    /**
     * Configures health indicator when web server initializes
     * 
     * @param event web server initialization event containing server port
     */
    @Override
    public void onApplicationEvent(@NonNull WebServerInitializedEvent event) {
        String serverNamespace = event.getApplicationContext().getServerNamespace();
        if (serverNamespace != null && !serverNamespace.isBlank()) {
            logger.debug("Ignoring WebServerInitializedEvent for namespace '{}'", serverNamespace);
            return;
        }

        if (isConfigured) {
            logger.debug("SearchPageHealthIndicator already configured for primary server; ignoring duplicate event.");
            return;
        }

        try {
            int port = event.getWebServer().getPort();
            if (port <= 0) {
                logger.warn("WebServerInitializedEvent reported an invalid port: {}. SearchPageHealthIndicator will remain unconfigured.", port);
                configureUninitialized();
                return;
            }
            String baseUrl = "http://localhost:" + port;
            this.delegate = new WebPageHealthIndicator(this.webClientBuilder, baseUrl, SEARCH_HEALTH_PATH, "search_page", this.reportErrorsAsDown, true);
            this.isConfigured = true;
            logger.info("SearchPageHealthIndicator configured with port: {}", port);
        } catch (RuntimeException e) {
            logger.error("Error configuring SearchPageHealthIndicator from WebServerInitializedEvent: {}", e.getMessage(), e);
            configureUninitialized();
        }
    }

    /**
     * Checks search page health status
     * 
     * @return health status with details about search page availability
     */
    @Override
    public Mono<Health> health() {
        if (!isConfigured || this.delegate == null) {
            return Mono.just(Health.unknown().withDetail("reason", "Server port not available or health indicator not fully configured for health check").build());
        }
        return delegate.checkPage();
    }

    private WebPageHealthIndicator unconfiguredDelegate() {
        return new WebPageHealthIndicator(
            this.webClientBuilder,
            null,
            SEARCH_HEALTH_PATH,
            "search_page",
            this.reportErrorsAsDown,
            false
        );
    }

    private void configureUninitialized() {
        this.isConfigured = false;
        this.delegate = unconfiguredDelegate();
    }
}
