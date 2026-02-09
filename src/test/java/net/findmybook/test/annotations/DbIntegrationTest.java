package net.findmybook.test.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimal replacement for the original DbIntegrationTest meta-annotation.
 * Keeps legacy integration tests compiling while using the standard Spring Boot
 * test context and the "test" profile.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = "AI_DEFAULT_OPENAI_API_KEY=test")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
public @interface DbIntegrationTest {
}
