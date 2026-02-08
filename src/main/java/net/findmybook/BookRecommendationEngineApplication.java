/**
 * Main application class for Book Finder
 *
 * @author William Callahan
 *
 * Features:
 * - Excludes default database auto-configurations to allow conditional DB setup
 * - Enables caching for improved performance
 * - Supports asynchronous operations for non-blocking API calls
 * - Entry point for Spring Boot application
 */

package net.findmybook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.util.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.retry.annotation.EnableRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication(exclude = {
    // Disable default Spring Security auto-configuration to allow public access
    org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
    org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class,
    // Disable reactive security auto-configuration for WebFlux endpoints
    org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration.class,
    // Disable SQL initialization to prevent automatic schema.sql execution
    org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration.class
})
@EnableCaching(proxyTargetClass = true)
@EnableAsync
@EnableScheduling
@EnableRetry
public class BookRecommendationEngineApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BookRecommendationEngineApplication.class);
    private static final int APPLICATION_SCHEDULER_POOL_SIZE = 4;
    private static final int APPLICATION_SCHEDULER_SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final String APPLICATION_SCHEDULER_THREAD_PREFIX = "AppScheduler-";

    /**
     * Sentinel value set as the OpenAI API key when no real key is configured.
     * Runtime code should check {@link #isAiConfigured()} before calling OpenAI.
     */
    static final String AI_KEY_NOT_CONFIGURED = "not-configured";

    /**
     * Returns {@code true} when a real OpenAI API key is available.
     * Use this guard before any OpenAI call to fail fast with a clear message
     * instead of sending a request with the sentinel placeholder key.
     */
    public static boolean isAiConfigured() {
        String key = System.getProperty("spring.ai.openai.api-key");
        return StringUtils.hasText(key) && !AI_KEY_NOT_CONFIGURED.equals(key);
    }

    /**
     * Main method that starts the Spring Boot application
     *
     * @param args Command line arguments passed to the application
     */
    public static void main(String[] args) {
        // Load .env file first
        loadDotEnvFile();
        disableNettyUnsafeAccess();
        normalizeDatasourceUrlFromEnv();
        normalizeOpenAiConfig();
        SpringApplication.run(BookRecommendationEngineApplication.class, args);
    }

    /**
     * Provides a dedicated scheduler for application {@code @Scheduled} workloads.
     * This keeps business/background jobs isolated from the STOMP broker heartbeat
     * scheduler to avoid thread-pool coupling and improve log observability.
     *
     * @return application task scheduler used by Spring scheduling infrastructure
     */
    @Bean(name = "taskScheduler")
    public TaskScheduler applicationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(APPLICATION_SCHEDULER_THREAD_PREFIX);
        scheduler.setPoolSize(APPLICATION_SCHEDULER_POOL_SIZE);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(APPLICATION_SCHEDULER_SHUTDOWN_TIMEOUT_SECONDS);
        return scheduler;
    }

    private static void disableNettyUnsafeAccess() {
        if (System.getProperty("io.netty.noUnsafe") == null) {
            System.setProperty("io.netty.noUnsafe", "true");
        }
    }

    private static void loadDotEnvFile() {
        try {
            // Check if .env file exists and load it
            java.nio.file.Path envFile = java.nio.file.Paths.get(".env");
            if (java.nio.file.Files.exists(envFile)) {
                java.util.Properties props = new java.util.Properties();
                try (java.io.InputStream is = java.nio.file.Files.newInputStream(envFile)) {
                    props.load(is);
                }
                // Set as system properties only if not already set as environment variables
                for (String key : props.stringPropertyNames()) {
                    if (System.getenv(key) == null) {
                        System.setProperty(key, props.getProperty(key));
                    }
                }
            }
        } catch (IOException | SecurityException e) {
            log.warn("Failed to load .env file; aborting startup", e);
            throw new IllegalStateException("Failed to load .env file", e);
        }
    }

    private static void normalizeDatasourceUrlFromEnv() {
        try {
            String url = firstText(
                System.getenv("SPRING_DATASOURCE_URL"),
                System.getProperty("SPRING_DATASOURCE_URL")
            );
            if (!StringUtils.hasText(url)) {
                return;
            }

            java.util.Optional<net.findmybook.config.DatabaseUrlEnvironmentPostProcessor.JdbcParseResult> parsed =
                net.findmybook.config.DatabaseUrlEnvironmentPostProcessor.normalizePostgresUrl(url);
            if (parsed.isEmpty()) return;

            var result = parsed.get();
            String jdbcUrl = result.jdbcUrl;
            // Set Spring + Hikari properties before the context initializes
            System.setProperty("spring.datasource.url", jdbcUrl);
            System.setProperty("spring.datasource.jdbc-url", jdbcUrl);
            System.setProperty("spring.datasource.hikari.jdbc-url", jdbcUrl);
            System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");

            // Set username and password if extracted and not already provided
            String existingUser = firstText(
                System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getProperty("SPRING_DATASOURCE_USERNAME"),
                System.getProperty("spring.datasource.username")
            );

            String existingPass = firstText(
                System.getenv("SPRING_DATASOURCE_PASSWORD"),
                System.getProperty("SPRING_DATASOURCE_PASSWORD"),
                System.getProperty("spring.datasource.password")
            );

            String decodedUser = decodeUrlComponent(result.username);
            String decodedPass = decodeUrlComponent(result.password);

            if (!StringUtils.hasText(existingUser) && StringUtils.hasText(decodedUser)) {
                System.setProperty("spring.datasource.username", decodedUser);
            }
            if (!StringUtils.hasText(existingPass) && StringUtils.hasText(decodedPass)) {
                System.setProperty("spring.datasource.password", decodedPass);
            }

            // Echo minimal confirmation to stdout (password omitted)
            String safeUrl = jdbcUrl.replaceAll("://[^@]+@", "://***:***@");
            log.info("[DB] Normalized SPRING_DATASOURCE_URL to JDBC: {}", safeUrl);
        } catch (RuntimeException e) {
            log.error("[DB] Failed to normalize SPRING_DATASOURCE_URL", e);
            throw e;
        }
    }

    /**
     * Normalizes Spring AI OpenAI environment variables to Spring properties.
     * Sets placeholder key when no credentials are provided so the app can boot
     * without OpenAI configured (the feature simply won't be available).
     *
     * <p>Per Spring AI docs, only these properties are recognized:
     * <ul>
     *   <li>spring.ai.openai.api-key</li>
     *   <li>spring.ai.openai.base-url</li>
     *   <li>spring.ai.openai.chat.options.model</li>
     * </ul>
     */
    private static void normalizeOpenAiConfig() {
        try {
            // Normalize API key from environment variable to Spring property
            String openAiEnvKey = firstText(
                System.getenv("SPRING_AI_OPENAI_API_KEY"),
                System.getProperty("SPRING_AI_OPENAI_API_KEY")
            );

            if (StringUtils.hasText(openAiEnvKey)
                && !StringUtils.hasText(System.getProperty("spring.ai.openai.api-key"))) {
                System.setProperty("spring.ai.openai.api-key", openAiEnvKey);
            }

            // Normalize base URL from environment variable to Spring property
            String baseUrl = firstText(
                System.getenv("SPRING_AI_OPENAI_BASE_URL"),
                System.getProperty("SPRING_AI_OPENAI_BASE_URL")
            );
            if (StringUtils.hasText(baseUrl)
                && !StringUtils.hasText(System.getProperty("spring.ai.openai.base-url"))) {
                System.setProperty("spring.ai.openai.base-url", baseUrl);
            }

            // Normalize model from environment variable to Spring property
            String model = firstText(
                System.getenv("SPRING_AI_OPENAI_MODEL"),
                System.getProperty("SPRING_AI_OPENAI_MODEL")
            );
            if (StringUtils.hasText(model)
                && !StringUtils.hasText(System.getProperty("spring.ai.openai.chat.options.model"))) {
                System.setProperty("spring.ai.openai.chat.options.model", model);
            }

            boolean hasApiKey = StringUtils.hasText(firstText(
                System.getProperty("spring.ai.openai.api-key"),
                openAiEnvKey
            ));

            if (!hasApiKey) {
                // Spring AI auto-configuration requires this property to exist.
                // The sentinel value lets the app boot; runtime AI calls will check
                // for this sentinel and throw immediately with a clear message.
                System.setProperty("spring.ai.openai.api-key", AI_KEY_NOT_CONFIGURED);
                log.error("[AI] No OpenAI API key configured. "
                    + "Set SPRING_AI_OPENAI_API_KEY to enable AI features. "
                    + "All AI operations will fail until a valid key is provided.");
            }
        } catch (SecurityException e) {
            log.warn("[AI] Unable to set OpenAI system properties due to security restrictions", e);
            throw e;
        }
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Validates CLI args for removed migrations and fails fast with guidance.
     *
     * @param args Application arguments provided at startup
     */
    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("migrate.s3.books")) {
            log.error("--migrate.s3.books has been removed. Run the manual SQL migration instead (see AGENTS.md manual)." );
            throw new IllegalStateException("S3-backed book migrations are no longer automated; run manual SQL steps instead.");
        }

        if (args.containsOption("migrate.s3.lists")) {
            log.error("--migrate.s3.lists has been removed. Run the manual SQL migration instead (see AGENTS.md manual)." );
            throw new IllegalStateException("S3-backed list migrations are no longer automated; run manual SQL steps instead.");
        }
    }

    private static String decodeUrlComponent(String value) {
        if (value == null) {
            return null;
        }
        // Only attempt URL decoding if the value contains percent-encoded sequences
        // This prevents corruption of passwords with literal '+' characters
        if (!value.contains("%")) {
            return value;
        }
        try {
            // Pre-escape literal '+' characters to preserve them during decoding
            // URLDecoder treats '+' as space, but in passwords it should be literal
            String prepared = value.replace("+", "%2B");
            return java.net.URLDecoder.decode(prepared, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            log.error("Failed to URL-decode datasource credential component (malformed encoding). "
                + "Fix the percent-encoding in SPRING_DATASOURCE_URL or provide credentials separately.", ex);
            throw new IllegalStateException(
                "Cannot decode datasource credential: malformed percent-encoding in URL", ex);
        }
    }

}
