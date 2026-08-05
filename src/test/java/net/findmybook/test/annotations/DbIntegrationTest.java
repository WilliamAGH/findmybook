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
@SpringBootTest(properties = {
    "openai.api.key=test",
    "APP_ADMIN_PASSWORD=test-password",
    "APP_USER_PASSWORD=test-password",
    "app.security.admin.password=test-password",
    "app.security.user.password=test-password"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
public @interface DbIntegrationTest {
}
