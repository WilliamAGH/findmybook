package net.findmybook.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class HikariConfigurationTest {

    @Test
    void should_BindPoolLivenessSettings_When_LoadingBaseApplicationConfiguration() throws IOException {
        var propertySources = new YamlPropertySourceLoader().load(
            "application", new ClassPathResource("application.yml"));
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(propertySources.getFirst());
        var hikariConfig = new HikariConfig();

        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(hikariConfig));

        assertThat(hikariConfig.getKeepaliveTime()).isEqualTo(Duration.ofMinutes(1).toMillis());
        assertThat(hikariConfig.getKeepaliveTime()).isLessThan(hikariConfig.getMaxLifetime());
        assertThat(hikariConfig.getMaxLifetime()).isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(hikariConfig.getDataSourceProperties().getProperty("tcpKeepAlive"))
            .isEqualTo(Boolean.TRUE.toString());
        assertThat(hikariConfig.getConnectionTestQuery()).isNull();
    }
}
