/**
 * Configuration for WebClient
 * - Adds application-wide headers to Boot-managed WebClient builders
 *
 * @author William Callahan
 */
package net.findmybook.config;

import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * Configures Boot-managed WebClient builders with application-wide headers.
 *
 * <p>Spring Boot owns the prototype builder, shared HTTP resources, codecs, and
 * connector policy. This configuration is additive so Boot customizers remain active.
 */
@Configuration(proxyBeanMethods = false)
public class WebClientConfig {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * Adds the canonical outbound user agent to each Boot-managed builder.
     *
     * @return additive customizer applied to every auto-configured builder
     */
    @Bean
    public WebClientCustomizer defaultUserAgentWebClientCustomizer() {
        return builder -> builder.defaultHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT);
    }
}
