package net.findmybook.boot;

import com.openai.models.ReasoningEffort;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Canonical OpenAI-compatible provider configuration for generation and embeddings.
 *
 * <p>This owner is the only runtime source for the OpenAI API key, base URL, inference model,
 * embeddings model, and SDK timeout settings.</p>
 */
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private static final String API_KEY_ENVIRONMENT_VARIABLE = "OPENAI_API_KEY";
    private static final String API_KEY_PROPERTY = "openai.api.key";
    private static final String BASE_URL_ENVIRONMENT_VARIABLE = "OPENAI_BASE_URL";
    private static final String BASE_URL_PROPERTY = "openai.base.url";
    private static final String MODEL_ENVIRONMENT_VARIABLE = "OPENAI_MODEL";
    private static final String MODEL_PROPERTY = "openai.model";
    private static final String REASONING_EFFORT_ENVIRONMENT_VARIABLE = "OPENAI_REASONING_EFFORT";
    private static final String REASONING_EFFORT_PROPERTY = "openai.reasoning-effort";
    private static final List<String> SUPPORTED_REASONING_EFFORTS =
        List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final String EMBEDDINGS_MODEL_ENVIRONMENT_VARIABLE = "OPENAI_EMBEDDINGS_MODEL";
    private static final String EMBEDDINGS_MODEL_PROPERTY = "openai.embeddings.model";
    private static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 120L;
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 75L;
    private static final long MIN_TIMEOUT_SECONDS = 1L;

    private Api api = new Api();
    private Base base = new Base();
    private Embeddings embeddings = new Embeddings();
    private String model = "";
    private String reasoningEffort = "";
    private long requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;
    private long readTimeoutSeconds = DEFAULT_READ_TIMEOUT_SECONDS;

    /**
     * Creates a configuration holder that waits for canonical environment variables.
     */
    public OpenAiProperties() {
    }

    /**
     * Projects canonical {@code OPENAI_*} variables loaded from {@code .env} into Spring's
     * bindable {@code openai.*} property names without inventing defaults.
     */
    public static void projectEnvironmentVariablesToSystemProperties() {
        projectSystemProperty(API_KEY_PROPERTY, API_KEY_ENVIRONMENT_VARIABLE);
        projectSystemProperty(BASE_URL_PROPERTY, BASE_URL_ENVIRONMENT_VARIABLE);
        projectSystemProperty(MODEL_PROPERTY, MODEL_ENVIRONMENT_VARIABLE);
        projectSystemProperty(REASONING_EFFORT_PROPERTY, REASONING_EFFORT_ENVIRONMENT_VARIABLE);
        projectSystemProperty(EMBEDDINGS_MODEL_PROPERTY, EMBEDDINGS_MODEL_ENVIRONMENT_VARIABLE);
    }

    /**
     * Returns the bindable API credential group that maps from {@code OPENAI_API_KEY}.
     *
     * @return API credential configuration
     */
    public Api getApi() {
        return api;
    }

    /**
     * Binds the API credential group.
     *
     * @param api API credential configuration
     */
    public void setApi(Api api) {
        this.api = api == null ? new Api() : api;
    }

    /**
     * Returns the bindable base URL group that maps from {@code OPENAI_BASE_URL}.
     *
     * @return base URL configuration
     */
    public Base getBase() {
        return base;
    }

    /**
     * Binds the base URL group.
     *
     * @param base base URL configuration
     */
    public void setBase(Base base) {
        this.base = base == null ? new Base() : base;
    }

    /**
     * Returns the bindable embeddings group that maps from {@code OPENAI_EMBEDDINGS_MODEL}.
     *
     * @return embeddings configuration
     */
    public Embeddings getEmbeddings() {
        return embeddings;
    }

    /**
     * Binds the embeddings group.
     *
     * @param embeddings embeddings configuration
     */
    public void setEmbeddings(Embeddings embeddings) {
        this.embeddings = embeddings == null ? new Embeddings() : embeddings;
    }

    /**
     * Indicates whether outbound OpenAI-compatible API calls can be made.
     *
     * @return true when API key, base URL, and inference model are configured
     */
    public boolean isConfigured() {
        return StringUtils.hasText(api.key) && StringUtils.hasText(base.url) && StringUtils.hasText(model);
    }

    /**
     * Indicates whether outbound embedding calls can be made.
     *
     * @return true when API key, base URL, and embeddings model are configured
     */
    public boolean isEmbeddingsConfigured() {
        return StringUtils.hasText(api.key) && StringUtils.hasText(base.url) && StringUtils.hasText(embeddings.model);
    }

    /**
     * Returns the configured OpenAI-compatible API key.
     *
     * @return trimmed API key, or an empty string when absent
     */
    public String apiKey() {
        return api.key;
    }

    /**
     * Returns the normalized OpenAI-compatible API base URL.
     *
     * @return base URL ending in {@code /v1}
     */
    public String baseUrl() {
        return base.url;
    }

    /**
     * Returns the inference model used by chat/generation workflows.
     *
     * @return configured inference model
     */
    public String model() {
        return model;
    }

    /**
     * Binds the inference model.
     *
     * @param model configured model from {@code OPENAI_MODEL}
     */
    public void setModel(String model) {
        this.model = textOrEmpty(model);
    }

    /**
     * Returns an optional standard reasoning effort for OpenAI-compatible chat completions.
     *
     * @return configured gateway reasoning effort, or empty when the model default should apply
     */
    public Optional<ReasoningEffort> reasoningEffort() {
        return StringUtils.hasText(reasoningEffort)
            ? Optional.of(ReasoningEffort.of(reasoningEffort))
            : Optional.empty();
    }

    /**
     * Returns the canonical gateway reasoning-effort tokens accepted at configuration bind time.
     *
     * <p>Consumers use this immutable projection so tests and other typed surfaces cannot create
     * independent token inventories.</p>
     *
     * @return immutable supported reasoning-effort tokens
     */
    public static List<String> supportedReasoningEfforts() {
        return SUPPORTED_REASONING_EFFORTS;
    }

    /**
     * Binds an optional standard reasoning effort from {@code OPENAI_REASONING_EFFORT}.
     *
     * <p>Validation happens here, at bind time, so a typo fails startup with a clear message
     * instead of reaching the gateway and 4xxing every generation call. Tokens are matched
     * case-insensitively and normalized to lowercase.</p>
     *
     * @param reasoningEffort gateway reasoning effort, or blank to use the model default
     * @throws IllegalArgumentException when the token is not one of {@link #SUPPORTED_REASONING_EFFORTS}
     */
    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = normalizeReasoningEffort(reasoningEffort);
    }

    /**
     * Returns the embeddings model used for vector calculations.
     *
     * @return configured embeddings model
     */
    public String embeddingsModel() {
        return embeddings.model;
    }

    /**
     * Returns the total request timeout in seconds for OpenAI-compatible calls.
     *
     * @return timeout seconds, clamped to at least one second
     */
    public long requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    /**
     * Binds the total request timeout in seconds.
     *
     * @param requestTimeoutSeconds configured {@code openai.request-timeout-seconds} value
     */
    public void setRequestTimeoutSeconds(long requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(MIN_TIMEOUT_SECONDS, requestTimeoutSeconds);
    }

    /**
     * Returns the read timeout in seconds for OpenAI-compatible calls.
     *
     * @return timeout seconds, clamped to at least one second
     */
    public long readTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    /**
     * Binds the read timeout in seconds.
     *
     * @param readTimeoutSeconds configured {@code openai.read-timeout-seconds} value
     */
    public void setReadTimeoutSeconds(long readTimeoutSeconds) {
        this.readTimeoutSeconds = Math.max(MIN_TIMEOUT_SECONDS, readTimeoutSeconds);
    }

    private static String normalizeBaseUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return "";
        }
        String normalized = rawUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/embeddings")) {
            normalized = normalized.substring(0, normalized.length() - "/embeddings".length());
        }
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    private static void projectSystemProperty(String propertyName, String environmentVariableName) {
        if (StringUtils.hasText(System.getProperty(propertyName))) {
            return;
        }
        String value = firstText(System.getenv(environmentVariableName), System.getProperty(environmentVariableName));
        if (StringUtils.hasText(value)) {
            System.setProperty(propertyName, value.trim());
        }
    }

    private static String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizeReasoningEffort(String reasoningEffort) {
        if (!StringUtils.hasText(reasoningEffort)) {
            return "";
        }
        String normalized = reasoningEffort.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_REASONING_EFFORTS.contains(normalized)) {
            throw new IllegalArgumentException(
                "Unsupported " + REASONING_EFFORT_ENVIRONMENT_VARIABLE + " value '" + normalized + "' for "
                    + REASONING_EFFORT_PROPERTY + "; supported values: "
                    + String.join(", ", SUPPORTED_REASONING_EFFORTS));
        }
        return normalized;
    }

    private static String textOrEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    /**
     * Bindable API credentials for the OpenAI-compatible provider.
     */
    public static final class Api {

        private String key = "";

        /**
         * Creates an API credential holder with no key configured.
         */
        public Api() {
        }

        /**
         * Returns the configured API key.
         *
         * @return trimmed API key, or an empty string when absent
         */
        public String getKey() {
            return key;
        }

        /**
         * Binds the API key from {@code OPENAI_API_KEY}.
         *
         * @param key configured API key
         */
        public void setKey(String key) {
            this.key = textOrEmpty(key);
        }
    }

    /**
     * Bindable base URL for the OpenAI-compatible provider.
     */
    public static final class Base {

        private String url = "";

        /**
         * Creates a base URL holder with no endpoint configured.
         */
        public Base() {
        }

        /**
         * Returns the normalized API base URL.
         *
         * @return base URL ending in {@code /v1}
         */
        public String getUrl() {
            return url;
        }

        /**
         * Binds and normalizes the API base URL from {@code OPENAI_BASE_URL}.
         *
         * @param url configured API base URL
         */
        public void setUrl(String url) {
            this.url = normalizeBaseUrl(url);
        }
    }

    /**
     * Bindable embeddings settings for the OpenAI-compatible provider.
     */
    public static final class Embeddings {

        private String model = "";

        /**
         * Creates an embeddings holder with no model configured.
         */
        public Embeddings() {
        }

        /**
         * Returns the embeddings model.
         *
         * @return configured embeddings model
         */
        public String getModel() {
            return model;
        }

        /**
         * Binds the embeddings model from {@code OPENAI_EMBEDDINGS_MODEL}.
         *
         * @param model configured embeddings model
         */
        public void setModel(String model) {
            this.model = textOrEmpty(model);
        }
    }
}
