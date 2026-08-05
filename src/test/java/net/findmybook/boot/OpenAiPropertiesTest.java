package net.findmybook.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.models.ReasoningEffort;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
        "OPENAI_REASONING_EFFORT",
        "OPENAI_EMBEDDINGS_MODEL",
        "openai.api.key",
        "openai.base.url",
        "openai.model",
        "openai.reasoning-effort",
        "openai.embeddings.model"
    };

    @Test
    void should_BindCanonicalEnvironmentVariables_When_EnvironmentUsesOpenAiNames() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        TestPropertyValues.of(
            "OPENAI_API_KEY=  test-key  ",
            "OPENAI_BASE_URL=https://llm.example.test/v1/embeddings/",
            "OPENAI_MODEL=  gpt-test  ",
            "OPENAI_REASONING_EFFORT=  max  ",
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
        assertThat(properties.reasoningEffort()).map(ReasoningEffort::asString).contains("max");
        assertThat(properties.embeddingsModel()).isEqualTo("embedding-test");
    }

    @Test
    void should_ProjectDotEnvVariables_When_EnvironmentStyleSystemPropertiesAreLoaded() {
        withRestoredOpenAiSystemProperties(() -> {
            System.setProperty("OPENAI_API_KEY", "  dot-env-key  ");
            System.setProperty("OPENAI_BASE_URL", "https://dotenv.example.test/v1/");
            System.setProperty("OPENAI_MODEL", "  dotenv-inference  ");
            System.setProperty("OPENAI_REASONING_EFFORT", "  none  ");
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
            assertThat(properties.reasoningEffort()).map(ReasoningEffort::asString).contains("none");
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

    @ParameterizedTest
    @MethodSource("canonicalReasoningEfforts")
    void should_BindEveryCanonicalReasoningEffort_When_TokenIsSupported(String reasoningEffort) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("openai.reasoning-effort", reasoningEffort);
        OpenAiProperties properties = new OpenAiProperties();

        new Binder(source).bind("openai", Bindable.ofInstance(properties));

        assertThat(properties.reasoningEffort()).map(ReasoningEffort::asString).contains(reasoningEffort);
    }

    @Test
    void should_NormalizeReasoningEffort_When_TokenHasMixedCaseAndWhitespace() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("openai.reasoning-effort", "  MaX  ");
        OpenAiProperties properties = new OpenAiProperties();

        new Binder(source).bind("openai", Bindable.ofInstance(properties));

        assertThat(properties.reasoningEffort()).map(ReasoningEffort::asString).contains("max");
    }

    @Test
    void should_FailBindFast_When_ReasoningEffortIsNotACanonicalToken() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("openai.reasoning-effort", "ultra");
        String supportedReasoningEfforts = String.join(", ", OpenAiProperties.supportedReasoningEfforts());

        assertThatThrownBy(() -> new Binder(source).bind("openai", Bindable.ofInstance(new OpenAiProperties())))
            .hasRootCauseMessage(
                "Unsupported OPENAI_REASONING_EFFORT value 'ultra' for openai.reasoning-effort; "
                    + "supported values: " + supportedReasoningEfforts
            );
    }

    private static Stream<String> canonicalReasoningEfforts() {
        return OpenAiProperties.supportedReasoningEfforts().stream();
    }

    @Test
    void should_KeepProviderValuesBlank_When_EnvironmentVariablesAreBlank() {
        OpenAiProperties properties = new OpenAiProperties();

        properties.getApi().setKey(" ");
        properties.getBase().setUrl(" ");
        properties.setModel(" ");
        properties.setReasoningEffort(" ");
        properties.getEmbeddings().setModel(" ");

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.isEmbeddingsConfigured()).isFalse();
        assertThat(properties.apiKey()).isEmpty();
        assertThat(properties.baseUrl()).isEmpty();
        assertThat(properties.model()).isEmpty();
        assertThat(properties.reasoningEffort()).isEmpty();
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
