package net.findmybook;

// CachedBookRepository removed with Redis cleanup
import net.findmybook.config.DatabaseUrlEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic application context load test for the Book Finder
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
    "AI_DEFAULT_OPENAI_API_KEY=test",
    "APP_ADMIN_PASSWORD=test-password",
    "APP_USER_PASSWORD=test-password",
    "app.security.admin.password=test-password",
    "app.security.user.password=test-password"
})
@ActiveProfiles("test") // Ensure the "test" profile and its Redis configuration are active
class BookRecommendationEngineApplicationTests {

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

    // No-op: cached repository removed

    /**
     * Verifies that the Spring application context loads successfully
     */
    @Test
    void contextLoads() {
        // Test will pass if the context loads with the mocked repository
    }

    @Test
    void normalizePostgresUrl_decodesCredentialsAndDatabase() {
        Optional<DatabaseUrlEnvironmentPostProcessor.JdbcParseResult> result =
            DatabaseUrlEnvironmentPostProcessor.normalizePostgresUrl(
                "postgres://user:pass%23word@localhost:5432/my%20db"
            );

        assertTrue(result.isPresent());
        DatabaseUrlEnvironmentPostProcessor.JdbcParseResult parsed = result.get();
        assertEquals("jdbc:postgresql://localhost:5432/my db", parsed.jdbcUrl);
        assertEquals("user", parsed.username);
        assertEquals("pass#word", parsed.password);
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
        environment.setProperty("DATABASE_URL", "postgres://fallback_user:fallback_pass@db.example.com:5433/books");

        DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication(BookRecommendationEngineApplication.class));

        assertEquals("jdbc:postgresql://db.example.com:5433/books", environment.getProperty("spring.datasource.url"));
        assertEquals("fallback_user", environment.getProperty("spring.datasource.username"));
        assertEquals("fallback_pass", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void should_PreferSpringDatasourceUrl_When_BothDatasourceUrlEnvironmentVariablesProvided() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("SPRING_DATASOURCE_URL", "postgres://primary_user:primary_pass@primary-db.example.com:5432/primary");
        environment.setProperty("DATABASE_URL", "postgres://fallback_user:fallback_pass@fallback-db.example.com:5432/fallback");

        DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication(BookRecommendationEngineApplication.class));

        assertEquals("jdbc:postgresql://primary-db.example.com:5432/primary", environment.getProperty("spring.datasource.url"));
        assertEquals("primary_user", environment.getProperty("spring.datasource.username"));
        assertEquals("primary_pass", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void should_UseDedicatedSchedulerPrefixes_WhenContextLoadsTaskSchedulers() {
        ThreadPoolTaskScheduler applicationScheduler =
            assertInstanceOf(ThreadPoolTaskScheduler.class, applicationTaskScheduler);
        ThreadPoolTaskScheduler brokerScheduler =
            assertInstanceOf(ThreadPoolTaskScheduler.class, messageBrokerTaskScheduler);

        assertEquals(APPLICATION_SCHEDULER_THREAD_PREFIX, applicationScheduler.getThreadNamePrefix());
        assertEquals(MESSAGE_BROKER_SCHEDULER_THREAD_PREFIX, brokerScheduler.getThreadNamePrefix());
        assertNotEquals(applicationScheduler.getThreadNamePrefix(), brokerScheduler.getThreadNamePrefix());
    }

}
