package net.findmybook.application.seo;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIException;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles OpenAI request/retry behavior for SEO title and description generation.
 */
@Component
class BookSeoMetadataClient {

    private static final Logger log = LoggerFactory.getLogger(BookSeoMetadataClient.class);
    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_MODEL = "gpt-5-mini";
    private static final long MAX_COMPLETION_TOKENS = 220L;
    private static final int MAX_GENERATION_ATTEMPTS = 3;
    private static final double SAMPLING_TEMPERATURE = 0.2;
    private static final String API_KEY_SENTINEL = "not-configured";

    private static final String SYSTEM_PROMPT = """
        You are an SEO metadata specialist for findmybook.net.
        Return ONLY strict JSON in this exact shape:
        {"seoTitle": string, "seoDescription": string}
        Rules:
        - seoTitle must be in this exact format: [Book Title] - Book Details | findmybook.net
        - Optimize seoTitle for around 50-60 characters when possible.
        - seoDescription must be natural, specific, and between 140 and 160 characters.
        - Do not use markdown, code fences, or extra keys.
        - Do not fabricate factual claims not supported by the provided context.
        """;

    private final SeoMetadataJsonParser parser;
    private final OpenAIClient openAiClient;
    private final boolean available;
    private final String configuredModel;
    private final long requestTimeoutSeconds;
    private final long readTimeoutSeconds;

    /**
     * Creates the configured OpenAI-backed SEO metadata client.
     */
    BookSeoMetadataClient(
        ObjectMapper objectMapper,
        @Value("${AI_DEFAULT_OPENAI_API_KEY:${OPENAI_API_KEY:}}") String apiKey,
        @Value("${AI_DEFAULT_OPENAI_BASE_URL:${OPENAI_BASE_URL:https://api.openai.com/v1}}") String baseUrl,
        @Value("${AI_DEFAULT_SEO_LLM_MODEL:${AI_DEFAULT_LLM_MODEL:${OPENAI_MODEL:" + DEFAULT_MODEL + "}}}") String model,
        @Value("${AI_DEFAULT_OPENAI_REQUEST_TIMEOUT_SECONDS:120}") long requestTimeoutSeconds,
        @Value("${AI_DEFAULT_OPENAI_READ_TIMEOUT_SECONDS:75}") long readTimeoutSeconds
    ) {
        this.parser = new SeoMetadataJsonParser(objectMapper);
        this.configuredModel = StringUtils.hasText(model) ? model.trim() : DEFAULT_MODEL;
        this.requestTimeoutSeconds = Math.max(1L, requestTimeoutSeconds);
        this.readTimeoutSeconds = Math.max(1L, readTimeoutSeconds);

        if (StringUtils.hasText(apiKey) && !API_KEY_SENTINEL.equals(apiKey.trim())) {
            String resolvedBaseUrl = UrlUtils.normalizeOpenAiBaseUrl(baseUrl);
            this.openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(resolvedBaseUrl)
                .maxRetries(0)
                .build();
            this.available = true;
            log.info(
                "Book SEO metadata generation service configured (model={}, baseUrl={})",
                this.configuredModel,
                resolvedBaseUrl
            );
            return;
        }

        this.openAiClient = null;
        this.available = false;
        log.warn("Book SEO metadata generation service is disabled: no API key configured");
    }

    /**
     * Indicates whether the upstream API client is configured.
     */
    boolean isAvailable() {
        return available;
    }

    /**
     * Returns the configured model used for persistence metadata.
     */
    String configuredModel() {
        return configuredModel;
    }

    /**
     * Returns the provider identifier used for generated metadata rows.
     */
    String provider() {
        return DEFAULT_PROVIDER;
    }

    /**
     * Generates SEO metadata for a prompt with retry behavior.
     */
    SeoMetadataCandidate generate(UUID bookId, String prompt) {
        ensureAvailable();

        BookSeoGenerationException lastGenerationFailure = null;
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return generateOnce(prompt);
            } catch (BookSeoGenerationException generationFailure) {
                lastGenerationFailure = generationFailure;
                if (attempt < MAX_GENERATION_ATTEMPTS && isRetryableGenerationFailure(generationFailure)) {
                    log.warn(
                        "Book SEO metadata generation attempt {}/{} failed for bookId={} model={} (will retry): {}",
                        attempt,
                        MAX_GENERATION_ATTEMPTS,
                        bookId,
                        configuredModel,
                        generationFailure.getMessage()
                    );
                    continue;
                }
                break;
            }
        }

        if (lastGenerationFailure == null) {
            throw new BookSeoGenerationException(
                "SEO metadata generation failed for bookId=%s model=%s".formatted(bookId, configuredModel)
            );
        }
        throw lastGenerationFailure;
    }

    private SeoMetadataCandidate generateOnce(String prompt) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(configuredModel))
            .messages(List.of(
                ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder().content(SYSTEM_PROMPT).build()),
                ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build())
            ))
            .maxCompletionTokens(MAX_COMPLETION_TOKENS)
            .temperature(SAMPLING_TEMPERATURE)
            .build();

        RequestOptions options = RequestOptions.builder()
            .timeout(Timeout.builder()
                .request(Duration.ofSeconds(requestTimeoutSeconds))
                .read(Duration.ofSeconds(readTimeoutSeconds))
                .build())
            .build();

        try {
            ChatCompletion completion = openAiClient.chat().completions().create(params, options);
            if (completion.choices().isEmpty()) {
                throw new BookSeoGenerationException("SEO metadata response contained no choices");
            }
            String response = completion.choices().get(0).message().content().orElse("");
            if (!StringUtils.hasText(response)) {
                throw new BookSeoGenerationException("SEO metadata response was empty");
            }
            return parser.parse(response);
        } catch (OpenAIException openAiException) {
            String detail = BookAiGenerationException.describeApiError(openAiException);
            log.error("SEO metadata API call failed (model={}): {}", configuredModel, detail);
            throw new BookSeoGenerationException(
                "SEO metadata generation failed (%s): %s".formatted(configuredModel, detail),
                openAiException
            );
        }
    }

    private boolean isRetryableGenerationFailure(BookSeoGenerationException generationFailure) {
        if (generationFailure.getCause() != null) {
            return true;
        }
        String failureMessage = generationFailure.getMessage();
        if (!StringUtils.hasText(failureMessage)) {
            return false;
        }
        return failureMessage.contains("response was empty")
            || failureMessage.contains("did not include a valid JSON object")
            || failureMessage.contains("JSON parsing failed")
            || failureMessage.contains("generation failed");
    }

    private void ensureAvailable() {
        if (!available || openAiClient == null) {
            throw new BookSeoGenerationException("SEO metadata generation service is not configured");
        }
    }
}
