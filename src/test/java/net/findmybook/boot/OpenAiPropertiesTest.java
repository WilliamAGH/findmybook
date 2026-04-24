package net.findmybook.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

class OpenAiPropertiesTest {

    private static final String[] OPENAI_SYSTEM_PROPERTY_NAMES = {
        "OPENAI_API_KEY",
        "OPENAI_BASE_URL",
        "OPENAI_MODEL",
        "OPENAI_EMBEDDINGS_MODEL",
        "openai.api.key",
        "openai.base.url",
        "openai.model",
        "openai.embeddings.model"
    };

    @Test
    void should_BindCanonicalEnvironmentVariables_When_EnvironmentUsesOpenAiNames() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        TestPropertyValues.of(
            "OPENAI_API_KEY=  test-key  ",
            "OPENAI_BASE_URL=https://llm.example.test/v1/embeddings/",
            "OPENAI_MODEL=  gpt-test  ",
            "OPENAI_EMBEDDINGS_MODEL=  embedding-test  "
        ).applyTo(environment, TestPropertyValues.Type.SYSTEM_ENVIRONMENT);

        OpenAiProperties properties = Binder.get(environment)
            .bind("openai", Bindable.of(OpenAiProperties.class))
            .orElseThrow(() -> new AssertionError("OpenAI properties should bind from canonical environment variables"));

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.isEmbeddingsConfigured()).isTrue();
        assertThat(properties.apiKey()).isEqualTo("test-key");
        assertThat(properties.baseUrl()).isEqualTo("https://llm.example.test/v1");
        assertThat(properties.model()).isEqualTo("gpt-test");
        assertThat(properties.embeddingsModel()).isEqualTo("embedding-test");
    }

    @Test
    void should_ProjectDotEnvVariables_When_EnvironmentStyleSystemPropertiesAreLoaded() {
        withRestoredOpenAiSystemProperties(() -> {
            System.setProperty("OPENAI_API_KEY", "  dot-env-key  ");
            System.setProperty("OPENAI_BASE_URL", "https://dotenv.example.test/v1/");
            System.setProperty("OPENAI_MODEL", "  dotenv-inference  ");
            System.setProperty("OPENAI_EMBEDDINGS_MODEL", "  dotenv-embeddings  ");

            OpenAiProperties.projectEnvironmentVariablesToSystemProperties();

            OpenAiProperties properties = Binder.get(new StandardEnvironment())
                .bind("openai", Bindable.of(OpenAiProperties.class))
                .orElseThrow(() -> new AssertionError("OpenAI properties should bind from projected .env variables"));

            assertThat(properties.isConfigured()).isTrue();
            assertThat(properties.isEmbeddingsConfigured()).isTrue();
            assertThat(properties.apiKey()).isEqualTo("dot-env-key");
            assertThat(properties.baseUrl()).isEqualTo("https://dotenv.example.test/v1");
            assertThat(properties.model()).isEqualTo("dotenv-inference");
            assertThat(properties.embeddingsModel()).isEqualTo("dotenv-embeddings");
        });
    }

    @Test
    void should_ClampOpenAiTimeouts_When_PropertiesAreBound() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("openai.request-timeout-seconds", "0");
        source.put("openai.read-timeout-seconds", "-5");
        OpenAiProperties properties = new OpenAiProperties();

        new Binder(source).bind("openai", Bindable.ofInstance(properties));

        assertThat(properties.requestTimeoutSeconds()).isEqualTo(1);
        assertThat(properties.readTimeoutSeconds()).isEqualTo(1);
    }

    @Test
    void should_KeepProviderValuesBlank_When_EnvironmentVariablesAreBlank() {
        OpenAiProperties properties = new OpenAiProperties();

        properties.getApi().setKey(" ");
        properties.getBase().setUrl(" ");
        properties.setModel(" ");
        properties.getEmbeddings().setModel(" ");

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.isEmbeddingsConfigured()).isFalse();
        assertThat(properties.apiKey()).isEmpty();
        assertThat(properties.baseUrl()).isEmpty();
        assertThat(properties.model()).isEmpty();
        assertThat(properties.embeddingsModel()).isEmpty();
    }

    private static void withRestoredOpenAiSystemProperties(Runnable assertion) {
        Map<String, String> originalValues = new HashMap<>();
        for (String propertyName : OPENAI_SYSTEM_PROPERTY_NAMES) {
            originalValues.put(propertyName, System.getProperty(propertyName));
            System.clearProperty(propertyName);
        }
        try {
            assertion.run();
        } finally {
            for (String propertyName : OPENAI_SYSTEM_PROPERTY_NAMES) {
                String originalValue = originalValues.get(propertyName);
                if (originalValue == null) {
                    System.clearProperty(propertyName);
                } else {
                    System.setProperty(propertyName, originalValue);
                }
            }
        }
    }
}
