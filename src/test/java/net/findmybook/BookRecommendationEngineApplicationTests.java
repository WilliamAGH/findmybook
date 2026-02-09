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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void should_ApplyJdbcDatabaseUrlFallback_When_UrlAlreadyInJdbcFormat() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("JDBC_DATABASE_URL", "jdbc:postgresql://jdbc-db.example.com:5439/jdbc_db?sslmode=require");

        DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication(BookRecommendationEngineApplication.class));

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

        assertEquals(APPLICATION_SCHEDULER_THREAD_PREFIX, applicationScheduler.getThreadNamePrefix());
        assertEquals(MESSAGE_BROKER_SCHEDULER_THREAD_PREFIX, brokerScheduler.getThreadNamePrefix());
        assertNotEquals(applicationScheduler.getThreadNamePrefix(), brokerScheduler.getThreadNamePrefix());
    }

    @Test
    void should_RequireDatasource_When_NoProfilesAreResolved() {
        assertTrue(BookRecommendationEngineApplication.isDatasourceRequired(null));
    }

    @Test
    void should_NotRequireDatasource_When_NodbProfileIsActive() {
        assertFalse(BookRecommendationEngineApplication.isDatasourceRequired("nodb"));
        assertFalse(BookRecommendationEngineApplication.isDatasourceRequired("dev,nodb"));
    }

    @Test
    void should_NotRequireDatasource_When_TestProfileIsActive() {
        assertFalse(BookRecommendationEngineApplication.isDatasourceRequired("test"));
        assertFalse(BookRecommendationEngineApplication.isDatasourceRequired("test,dev"));
    }

    @Test
    void should_ResolveProfilesFromCommandLineEqualsSyntax_When_Provided() {
        String resolved = BookRecommendationEngineApplication.resolveStartupActiveProfiles(
            new String[]{"--spring.profiles.active=dev,nodb"});
        assertEquals("dev,nodb", resolved);
    }

    @Test
    void should_ResolveProfilesFromCommandLineSplitSyntax_When_Provided() {
        String resolved = BookRecommendationEngineApplication.resolveStartupActiveProfiles(
            new String[]{"--spring.profiles.active", "nodb"});
        assertEquals("nodb", resolved);
    }

}
