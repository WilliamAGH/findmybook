package net.findmybook.testutil;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Minimal test database configuration used by BaseRepositoryTest.
 * Relies on Spring Boot test slices to provision an embedded/in-memory datasource
 * unless overridden by test properties.
 *
 * Note:
 * - Marked as @TestConfiguration so it is NOT picked up by component scanning in @SpringBootTest
 *   contexts; it will only apply when explicitly imported (e.g., via @ContextConfiguration).
 * - Do not combine @AutoConfigureTestDatabase with configuration classes. Apply it on test classes
 *   like @JdbcTest or @SpringBootTest when needed.
 */
@TestConfiguration
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
public class TestDatabaseConfig {
}
