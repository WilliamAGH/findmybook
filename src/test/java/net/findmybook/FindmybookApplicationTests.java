package net.findmybook;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
// CachedBookRepository removed with Redis cleanup
import net.findmybook.config.DatabaseUrlEnvironmentPostProcessor;
import net.findmybook.service.OpenLibraryBookDataService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.http.client.autoconfigure.HttpClientsProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.aop.support.AopUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Basic application context load test for findmybook
 *
 * @author William Callahan
 *
 * Features:
 * - Verifies that the Spring application context loads correctly.
 * - Ensures all required beans are properly instantiated (or mocked where appropriate for test isolation).
 * - Validates application configuration.
 * - Serves as a smoke test for the entire application.
 *
 * Note: Redis-backed cache and `CachedBookRepository` have been removed. This test
 * runs with the "test" profile to load the context with mocked services defined in
 * test configuration where applicable.
 */
@SpringBootTest(properties = {
    "openai.api.key=test",
    "APP_ADMIN_PASSWORD=test-password",
    "APP_USER_PASSWORD=test-password",
    "app.security.admin.password=test-password",
    "app.security.user.password=test-password"
})
@ActiveProfiles("test") // Ensure the "test" profile and its Redis configuration are active
class FindmybookApplicationTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(FindmybookApplicationTests.class);
    private static final String APPLICATION_TASK_SCHEDULER_BEAN = "taskScheduler";
    private static final String MESSAGE_BROKER_TASK_SCHEDULER_BEAN = "messageBrokerTaskScheduler";
    private static final String APPLICATION_SCHEDULER_THREAD_PREFIX = "AppScheduler-";
    private static final String MESSAGE_BROKER_SCHEDULER_THREAD_PREFIX = "MessageBroker-";

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private S3Client s3Client;

    @Autowired
    @Qualifier(APPLICATION_TASK_SCHEDULER_BEAN)
    private TaskScheduler applicationTaskScheduler;

    @Autowired
    @Qualifier(MESSAGE_BROKER_TASK_SCHEDULER_BEAN)
    private TaskScheduler messageBrokerTaskScheduler;

    @Autowired
    private Environment environment;

    @Autowired
    private HttpClientsProperties httpClientsProperties;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private OpenLibraryBookDataService openLibraryBookDataService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    // No-op: cached repository removed

    /**
     * Verifies that the Spring application context loads successfully
     */
    @Test
    void contextLoads() {
        // Test will pass if the context loads with the mocked repository
    }

    @Test
    void should_ExportWeeklyRefreshCounter_When_PrometheusEndpointIsScraped() throws Exception {
        var mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity())
            .build();
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                .with(httpBasic("user", "test-password")))
            .andExpect(status().isForbidden());

        String scrape = mockMvc.perform(get("/actuator/prometheus")
                .with(httpBasic("admin", "test-password")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertTrue(scrape.contains("findmybook_weekly_refresh_phase_total"));
        assertTrue(scrape.contains("phase=\"nyt\""));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void should_RedactEveryCredentialShape_When_ApplicationLogbackRendersThrowable(CapturedOutput output) {
        DataBufferLimitException requestFailure = assertThrows(
            DataBufferLimitException.class,
            () -> Mono.error(new DataBufferLimitException("Exceeded max bytes to buffer"))
                .checkpoint(
                    "Request to GET https://api.nytimes.com/lists/overview.json?api-key=throwable-secret-sentinel "
                        + "[DefaultWebClient]"
                )
                .block()
        );
        requestFailure.addSuppressed(new IllegalStateException(
            "Reactor checkpoint details:token=suppressed-secret-sentinel "
                + "url=https://covers.example/object?X-Amz-Signature=signed-url-secret-sentinel checkpoint-marker"
        ));

        LOGGER.error(
            "API_KEY=message-secret-sentinel NYT refresh failed "
                + "bucket=covers key=images/books/cover.jpg request-marker",
            requestFailure
        );

        String renderedOutput = output.getOut();
        assertFalse(renderedOutput.contains("message-secret-sentinel"));
        assertFalse(renderedOutput.contains("throwable-secret-sentinel"));
        assertFalse(renderedOutput.contains("suppressed-secret-sentinel"));
        assertFalse(renderedOutput.contains("signed-url-secret-sentinel"));
        assertTrue(renderedOutput.contains("API_KEY=********"));
        assertTrue(renderedOutput.contains("api-key=********"));
        assertTrue(renderedOutput.contains("token=********"));
        assertTrue(renderedOutput.contains("X-Amz-Signature=********"));
        assertTrue(renderedOutput.contains("key=images/books/cover.jpg"));
        assertTrue(renderedOutput.contains("request-marker"));
        assertTrue(renderedOutput.contains("checkpoint-marker"));
        assertTrue(renderedOutput.contains("DataBufferLimitException"));
        assertTrue(renderedOutput.contains("Request to GET"));
        assertTrue(renderedOutput.contains("DefaultWebClient"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void should_ApplyProxiedOpenLibraryFallbackWithoutRecordingRateLimiterDenialAsCircuitFailure(
        CapturedOutput output
    ) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("openLibraryDataService");
        assertEquals(1, rateLimiter.getRateLimiterConfig().getLimitForPeriod());
        assertEquals(Duration.ofSeconds(2), rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod());
        assertEquals(Duration.ofSeconds(2), rateLimiter.getRateLimiterConfig().getTimeoutDuration());

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("openLibraryDataService");
        circuitBreaker.reset();
        rateLimiter.changeTimeoutDuration(Duration.ZERO);
        rateLimiter.drainPermissions();
        try {
            assertTrue(AopUtils.isAopProxy(openLibraryBookDataService));

            StepVerifier.create(openLibraryBookDataService.queryBooksByEverything(
                    "rate-limit-probe",
                    "relevance",
                    0,
                    1
                ))
                .expectError(RequestNotPermitted.class)
                .verify(Duration.ofSeconds(1));

            assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
            assertEquals(0, circuitBreaker.getMetrics().getNumberOfBufferedCalls());
            assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
            assertTrue(output.getOut().contains("LOCAL_ADMISSION"));
            assertTrue(output.getOut().contains("RequestNotPermitted"));
        } finally {
            rateLimiter.changeTimeoutDuration(Duration.ofSeconds(2));
        }
    }

    @Test
    void should_DecodePercentEncodedCredentialsAndDatabase_When_NormalizingPostgresUrl() {
        Optional<DatabaseUrlEnvironmentPostProcessor.JdbcParseResult> result =
            DatabaseUrlEnvironmentPostProcessor.normalizePostgresUrl(
                "postgres://user:pass%23word%2Bmore@localhost:5432/my%20db%2Barchive"
            );

        assertTrue(result.isPresent());
        DatabaseUrlEnvironmentPostProcessor.JdbcParseResult parsed = result.get();
        assertEquals("jdbc:postgresql://localhost:5432/my db+archive", parsed.jdbcUrl);
        assertEquals("user", parsed.username);
        assertEquals("pass#word+more", parsed.password);
    }

    @Test
    void should_PreserveLiteralPlusCharacters_When_NormalizingPostgresUrl() {
        Optional<DatabaseUrlEnvironmentPostProcessor.JdbcParseResult> result =
            DatabaseUrlEnvironmentPostProcessor.normalizePostgresUrl(
                "postgres://user:p+ss@localhost:5432/books+archive"
            );

        assertTrue(result.isPresent());
        DatabaseUrlEnvironmentPostProcessor.JdbcParseResult parsed = result.get();
        assertEquals("jdbc:postgresql://localhost:5432/books+archive", parsed.jdbcUrl);
        assertEquals("user", parsed.username);
        assertEquals("p+ss", parsed.password);
    }

    @Test
    void normalizePostgresUrl_doesNotTreatQueryAtSignAsCredentialsSeparator() {
        Optional<DatabaseUrlEnvironmentPostProcessor.JdbcParseResult> result =
            DatabaseUrlEnvironmentPostProcessor.normalizePostgresUrl(
                "postgres://localhost/db?email=user@example.com"
            );

        assertTrue(result.isPresent());
        DatabaseUrlEnvironmentPostProcessor.JdbcParseResult parsed = result.get();
        assertEquals("jdbc:postgresql://localhost:5432/db?email=user@example.com", parsed.jdbcUrl);
        assertNull(parsed.username);
        assertNull(parsed.password);
    }

    @Test
    void should_ApplyDatabaseUrlFallback_When_SpringDatasourceUrlMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DATABASE_URL", "postgres://fallback_user:fallback+pass@db.example.com:5433/books+archive");

        DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication(FindmybookApplication.class));

        assertEquals("jdbc:postgresql://db.example.com:5433/books+archive", environment.getProperty("spring.datasource.url"));
        assertEquals("fallback_user", environment.getProperty("spring.datasource.username"));
        assertEquals("fallback+pass", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void should_PreferSpringDatasourceUrl_When_BothDatasourceUrlEnvironmentVariablesProvided() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("SPRING_DATASOURCE_URL", "postgres://primary_user:primary_pass@primary-db.example.com:5432/primary");
        environment.setProperty("DATABASE_URL", "postgres://fallback_user:fallback_pass@fallback-db.example.com:5432/fallback");

        DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication(FindmybookApplication.class));

        assertEquals("jdbc:postgresql://primary-db.example.com:5432/primary", environment.getProperty("spring.datasource.url"));
        assertEquals("primary_user", environment.getProperty("spring.datasource.username"));
        assertEquals("primary_pass", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void should_ApplyJdbcDatabaseUrlFallback_When_UrlAlreadyInJdbcFormat() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("JDBC_DATABASE_URL", "jdbc:postgresql://jdbc-db.example.com:5439/jdbc_db?sslmode=require");

        DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication(FindmybookApplication.class));

        assertEquals("jdbc:postgresql://jdbc-db.example.com:5439/jdbc_db?sslmode=require",
            environment.getProperty("spring.datasource.url"));
        assertEquals("org.postgresql.Driver", environment.getProperty("spring.datasource.driver-class-name"));
    }

    @Test
    void should_UseDedicatedSchedulerPrefixes_WhenContextLoadsTaskSchedulers() {
        ThreadPoolTaskScheduler applicationScheduler =
            assertInstanceOf(ThreadPoolTaskScheduler.class, applicationTaskScheduler);
        ThreadPoolTaskScheduler brokerScheduler =
            assertInstanceOf(ThreadPoolTaskScheduler.class, messageBrokerTaskScheduler);
        var applicationExecutor = applicationScheduler.getScheduledThreadPoolExecutor();

        assertEquals(APPLICATION_SCHEDULER_THREAD_PREFIX, applicationScheduler.getThreadNamePrefix());
        assertEquals(MESSAGE_BROKER_SCHEDULER_THREAD_PREFIX, brokerScheduler.getThreadNamePrefix());
        assertNotEquals(applicationScheduler.getThreadNamePrefix(), brokerScheduler.getThreadNamePrefix());
        assertNotNull(applicationExecutor);
        assertFalse(applicationExecutor.getContinueExistingPeriodicTasksAfterShutdownPolicy());
        assertFalse(applicationExecutor.getExecuteExistingDelayedTasksAfterShutdownPolicy());
    }

    @Test
    void should_RequireDatasource_When_NoProfilesAreResolved() {
        assertTrue(FindmybookApplication.isDatasourceRequired(null));
    }

    @Test
    void should_NotRequireDatasource_When_NodbProfileIsActive() {
        assertFalse(FindmybookApplication.isDatasourceRequired("nodb"));
        assertFalse(FindmybookApplication.isDatasourceRequired("dev,nodb"));
    }

    @Test
    void should_NotRequireDatasource_When_TestProfileIsActive() {
        assertFalse(FindmybookApplication.isDatasourceRequired("test"));
        assertFalse(FindmybookApplication.isDatasourceRequired("test,dev"));
    }

    @Test
    void should_ResolveProfilesFromCommandLineEqualsSyntax_When_Provided() {
        String resolved = FindmybookApplication.resolveStartupActiveProfiles(
            new String[]{"--spring.profiles.active=dev,nodb"});
        assertEquals("dev,nodb", resolved);
    }

    @Test
    void should_ResolveProfilesFromCommandLineSplitSyntax_When_Provided() {
        String resolved = FindmybookApplication.resolveStartupActiveProfiles(
            new String[]{"--spring.profiles.active", "nodb"});
        assertEquals("nodb", resolved);
    }

    /**
     * Guards the shared environment-mode contract used by API responses and SPA logic.
     * Regressions here can reintroduce production diagnostics to end users.
     */
    @Test
    void should_ExposeTestEnvironmentModeProperty_WhenTestProfileIsActive() {
        assertEquals("test", environment.getProperty("app.environment.mode"));
    }

    /**
     * Keeps the documented local default port stable for scripts and operator tooling.
     */
    @Test
    void should_DefaultServerPortTo8095_WhenNotOverridden() {
        assertEquals("8095", environment.getProperty("server.port"));
    }

    /**
     * Keeps stalled outbound responses bounded after delegating WebClient construction to Spring Boot.
     */
    @Test
    void should_DefaultOutboundHttpReadTimeoutToFiveSeconds_WhenNotOverridden() {
        assertEquals(Duration.ofSeconds(5), httpClientsProperties.getReadTimeout());
    }

    /**
     * Verifies test-profile logging defaults remain constrained and predictable.
     */
    @Test
    void should_KeepTestRootLoggingLevelAtWarn() {
        assertEquals("WARN", environment.getProperty("logging.level.root"));
    }

}
