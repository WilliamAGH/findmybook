package net.findmybook.application.seo;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.ChatModel;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.boot.OpenAiProperties;
import net.findmybook.support.llm.LlmGatewayTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final int SDK_MAX_RETRIES = 2;
    private static final double SAMPLING_TEMPERATURE = 0.2;
    private static final String RETRY_RECOVERY_INSTRUCTION = "\n\nRecovery attempt %d: the prior response was empty or invalid. "
        + "Return only the exact canonical JSON object with seoTitle and seoDescription string fields.";

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
    private final Map<LlmGatewayTier, OpenAIClient> clientsByTier;
    private final boolean available;
    private final String configuredModel;
    private final Optional<ReasoningEffort> configuredReasoningEffort;
    private final long requestTimeoutSeconds;
    private final long readTimeoutSeconds;

    /**
     * Creates one OpenAI client per {@link LlmGatewayTier} so the {@code X-Tier} header is fixed
     * per connection and each call routes to the correct gateway queue.
     */
    BookSeoMetadataClient(
        ObjectMapper objectMapper,
        OpenAiProperties openAiProperties
    ) {
        this.parser = new SeoMetadataJsonParser(objectMapper);
        this.configuredModel = openAiProperties.model();
        this.configuredReasoningEffort = openAiProperties.reasoningEffort();
        this.requestTimeoutSeconds = openAiProperties.requestTimeoutSeconds();
        this.readTimeoutSeconds = openAiProperties.readTimeoutSeconds();

        if (openAiProperties.isConfigured()) {
            EnumMap<LlmGatewayTier, OpenAIClient> clients = new EnumMap<>(LlmGatewayTier.class);
            for (LlmGatewayTier tier : LlmGatewayTier.values()) {
                clients.put(tier, OpenAIOkHttpClient.builder()
                    .apiKey(openAiProperties.apiKey())
                    .baseUrl(openAiProperties.baseUrl())
                    .maxRetries(SDK_MAX_RETRIES)
                    .putHeader(LlmGatewayTier.HEADER_NAME, tier.headerValue())
                    .build());
            }
            this.clientsByTier = Map.copyOf(clients);
            this.available = true;
            log.info(
                "Book SEO metadata generation service configured (model={}, baseUrl={}, tiers={})",
                this.configuredModel,
                openAiProperties.baseUrl(),
                clients.keySet()
            );
            return;
        }

        this.clientsByTier = Map.of();
        this.available = false;
        log.warn("Book SEO metadata generation service is disabled: missing OPENAI_API_KEY, OPENAI_BASE_URL, or OPENAI_MODEL");
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
     * Generates SEO metadata for a prompt with retry behavior at the supplied gateway tier.
     *
     * <p>Retry attempts preserve the same model and generation settings while adding a concise
     * recovery instruction so an invalid provider response does not trigger an identical request.
     *
     * @param bookId canonical book UUID
     * @param prompt rendered prompt text
     * @param tier gateway priority tier controlling the {@code X-Tier} header on outbound calls
     * @return parsed SEO metadata candidate
     */
    SeoMetadataCandidate generate(UUID bookId, String prompt, LlmGatewayTier tier) {
        ensureAvailable();
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }

        BookSeoGenerationException lastGenerationFailure = null;
        int maxGenerationAttempts = tier.maxGenerationAttempts();
        for (int attempt = 1; attempt <= maxGenerationAttempts; attempt++) {
            try {
                return generateOnce(promptForAttempt(prompt, attempt), tier);
            } catch (BookSeoGenerationException generationFailure) {
                lastGenerationFailure = generationFailure;
                if (attempt < maxGenerationAttempts && isRetryableGenerationFailure(generationFailure)) {
                    log.warn(
                        "Book SEO metadata generation attempt {}/{} failed for bookId={} model={} tier={} (will retry): {}",
                        attempt,
                        maxGenerationAttempts,
                        bookId,
                        configuredModel,
                        tier.headerValue(),
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

    private String promptForAttempt(String canonicalPrompt, int attempt) {
        if (attempt == 1) {
            return canonicalPrompt;
        }
        return canonicalPrompt + RETRY_RECOVERY_INSTRUCTION.formatted(attempt);
    }

    private SeoMetadataCandidate generateOnce(String prompt, LlmGatewayTier tier) {
        OpenAIClient tieredClient = clientsByTier.get(tier);
        if (tieredClient == null) {
            throw new BookSeoGenerationException("No SEO metadata client configured for tier " + tier);
        }
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(configuredModel))
            .messages(List.of(
                ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder().content(SYSTEM_PROMPT).build()),
                ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build())
            ))
            .maxCompletionTokens(tier.maxCompletionTokens())
            .temperature(SAMPLING_TEMPERATURE);
        if (configuredReasoningEffort.isPresent()) {
            paramsBuilder.reasoningEffort(configuredReasoningEffort.get());
        }
        ChatCompletionCreateParams params = paramsBuilder.build();

        long effectiveRequestTimeoutSeconds = tier == LlmGatewayTier.LIVE_RENDER
            ? Math.min(requestTimeoutSeconds, tier.callTimeoutSeconds())
            : Math.max(requestTimeoutSeconds, tier.callTimeoutSeconds());
        long effectiveReadTimeoutSeconds = tier == LlmGatewayTier.LIVE_RENDER
            ? Math.min(readTimeoutSeconds, tier.callTimeoutSeconds())
            : Math.max(readTimeoutSeconds, tier.callTimeoutSeconds());
        RequestOptions options = RequestOptions.builder()
            .timeout(Timeout.builder()
                .request(Duration.ofSeconds(effectiveRequestTimeoutSeconds))
                .read(Duration.ofSeconds(effectiveReadTimeoutSeconds))
                .build())
            .build();

        try {
            ChatCompletion completion = tieredClient.chat().completions().create(params, options);
            if (completion.choices().isEmpty()) {
                throw new BookSeoGenerationException(
                    BookSeoGenerationException.ErrorCode.INVALID_RESPONSE,
                    "SEO metadata response contained no choices"
                );
            }
            ChatCompletion.Choice choice = completion.choices().get(0);
            String finishReason = choice.finishReason().asString();
            if (ChatCompletion.Choice.FinishReason.LENGTH.equals(choice.finishReason())) {
                throw new BookSeoGenerationException(
                    BookSeoGenerationException.ErrorCode.INVALID_RESPONSE,
                    "SEO metadata response exhausted completion token budget "
                        + "(maxCompletionTokens=%d, finishReason=%s)"
                            .formatted(tier.maxCompletionTokens(), finishReason)
                );
            }
            if (choice.message().refusal().filter(StringUtils::hasText).isPresent()) {
                throw new BookSeoGenerationException(
                    "SEO metadata request was refused (finishReason=%s, refusalPresent=true)".formatted(finishReason)
                );
            }
            if (!ChatCompletion.Choice.FinishReason.STOP.equals(choice.finishReason())) {
                throw new BookSeoGenerationException(
                    BookSeoGenerationException.ErrorCode.INVALID_RESPONSE,
                    "SEO metadata response ended without stop (finishReason=%s)".formatted(finishReason)
                );
            }
            String response = choice.message().content().orElse("");
            if (!StringUtils.hasText(response)) {
                throw new BookSeoGenerationException(
                    BookSeoGenerationException.ErrorCode.INVALID_RESPONSE,
                    "SEO metadata response was empty (finishReason=%s, refusalPresent=false)".formatted(finishReason)
                );
            }
            return parser.parse(response);
        } catch (OpenAIInvalidDataException invalidDataException) {
            String detail = BookAiGenerationException.describeApiError(invalidDataException);
            log.warn("SEO metadata API returned invalid data (model={}, tier={}): {}",
                configuredModel, tier.headerValue(), detail);
            throw new BookSeoGenerationException(
                BookSeoGenerationException.ErrorCode.INVALID_RESPONSE,
                "SEO metadata API returned invalid data (%s, tier=%s): %s"
                    .formatted(configuredModel, tier.headerValue(), detail),
                invalidDataException
            );
        } catch (OpenAIException openAiException) {
            String detail = BookAiGenerationException.describeApiError(openAiException);
            log.warn("SEO metadata API call failed (model={}, tier={}): {}", configuredModel, tier.headerValue(), detail);
            throw new BookSeoGenerationException(
                BookSeoGenerationException.ErrorCode.API_CALL_FAILED,
                "SEO metadata generation failed (%s, tier=%s): %s".formatted(configuredModel, tier.headerValue(), detail),
                openAiException
            );
        }
    }

    private boolean isRetryableGenerationFailure(BookSeoGenerationException generationFailure) {
        return generationFailure.errorCode() == BookSeoGenerationException.ErrorCode.INVALID_RESPONSE;
    }

    private void ensureAvailable() {
        if (!available || clientsByTier.isEmpty()) {
            throw new BookSeoGenerationException("SEO metadata generation service is not configured");
        }
    }
}
