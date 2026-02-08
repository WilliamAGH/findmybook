package net.findmybook.application.ai;

import jakarta.annotation.Nullable;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Coordinates cache-first AI content generation for book detail pages.
 *
 * <p>This application service keeps controller logic thin by owning:
 * prompt construction, OpenAI streaming, strict response parsing, and
 * persistence into versioned Postgres snapshots.</p>
 */
@Service
public class BookAiContentService {

    private static final Logger log = LoggerFactory.getLogger(BookAiContentService.class);

    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_API_MODE = "chat";
    private static final String DEFAULT_MODEL = "gpt-5-mini";
    private static final int MAX_KEY_THEME_COUNT = 6;
    private static final int MIN_KEY_THEME_COUNT = 1;

    private static final int MAX_TAKEAWAY_COUNT = 5;
    private static final int MIN_TAKEAWAY_COUNT = 1;

    /**
     * Sentinel value set by {@code normalizeOpenAiSdkConfig} when no real API key is available.
     * Must match {@code BookRecommendationEngineApplication.AI_KEY_NOT_CONFIGURED}.
     */
    private static final String API_KEY_SENTINEL = "not-configured";

    private static final String SYSTEM_PROMPT = """
        You write concise, useful book content.
        Return ONLY strict JSON using this exact shape:
        {
          \"summary\": string,
          \"readerFit\": string,
          \"keyThemes\": string[],
          \"takeaways\": string[],
          \"context\": string
        }

        Rules:
        - summary: 2 concise sentences describing the book's content.
        - readerFit: 1-2 sentences on who should read it and why.
        - keyThemes: 3 to 6 short topic phrases.
        - takeaways: 2 to 5 specific insights or points a reader will gain.
        - context: 1-2 sentences placing the book in its genre or field.
        - No markdown, no prose outside JSON, no extra keys.
        """;

    private final BookAiContentRepository repository;
    private final BookIdentifierResolver identifierResolver;
    private final BookSearchService bookSearchService;
    private final ObjectMapper objectMapper;
    private final OpenAIClient openAiClient;
    private final boolean available;
    private final String configuredModel;
    private final long requestTimeoutSeconds;
    private final long readTimeoutSeconds;

    /**
     * Creates the service with persistence, lookup, and SDK dependencies.
     */
    public BookAiContentService(
        BookAiContentRepository repository,
        BookIdentifierResolver identifierResolver,
        BookSearchService bookSearchService,
        ObjectMapper objectMapper,
        @Value("${AI_DEFAULT_OPENAI_API_KEY:${OPENAI_API_KEY:}}") String apiKey,
        @Value("${AI_DEFAULT_OPENAI_BASE_URL:${OPENAI_BASE_URL:https://api.openai.com/v1}}") String baseUrl,
        @Value("${AI_DEFAULT_LLM_MODEL:${OPENAI_MODEL:" + DEFAULT_MODEL + "}}") String model,
        @Value("${AI_DEFAULT_OPENAI_REQUEST_TIMEOUT_SECONDS:120}") long requestTimeoutSeconds,
        @Value("${AI_DEFAULT_OPENAI_READ_TIMEOUT_SECONDS:75}") long readTimeoutSeconds
    ) {
        this.repository = repository;
        this.identifierResolver = identifierResolver;
        this.bookSearchService = bookSearchService;
        this.objectMapper = objectMapper;
        this.configuredModel = StringUtils.hasText(model) ? model.trim() : DEFAULT_MODEL;
        this.requestTimeoutSeconds = Math.max(1L, requestTimeoutSeconds);
        this.readTimeoutSeconds = Math.max(1L, readTimeoutSeconds);

        if (StringUtils.hasText(apiKey)
                && !API_KEY_SENTINEL.equals(apiKey.trim())) {
            String resolvedBaseUrl = normalizeSdkBaseUrl(baseUrl);
            this.openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(resolvedBaseUrl)
                .maxRetries(0)
                .build();
            this.available = true;
            log.info("Book AI content service configured (model={}, baseUrl={})", this.configuredModel, resolvedBaseUrl);
        } else {
            this.openAiClient = null;
            this.available = false;
            log.warn("Book AI content service is disabled: no API key configured");
        }
    }

    /**
     * Resolves any user-facing book identifier to canonical UUID.
     */
    public Optional<UUID> resolveBookId(String identifier) {
        return identifierResolver.resolveToUuid(identifier);
    }

    /**
     * Loads the current persisted AI snapshot for a canonical book UUID.
     */
    public Optional<BookAiContentSnapshot> findCurrent(UUID bookId) {
        return repository.fetchCurrent(bookId);
    }

    /**
     * Generates fresh AI content, streams text deltas, and persists a new current version.
     *
     * @param bookId canonical book UUID
     * @param onDelta callback invoked for each model text delta
     * @return generated raw message plus persisted snapshot metadata
     */
    public GeneratedContent generateAndPersist(UUID bookId, Consumer<String> onDelta) {
        ensureAvailable();

        BookPromptContext promptContext = loadPromptContext(bookId);
        String prompt = buildPrompt(promptContext);
        String promptHash = sha256(prompt);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(configuredModel))
            .messages(List.of(
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(SYSTEM_PROMPT).build()
                ),
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder().content(prompt).build()
                )
            ))
            .maxTokens(1000L) // LM Studio 0.4.x only supports max_tokens, not max_completion_tokens
            .temperature(0.7)
            .build();

        RequestOptions options = RequestOptions.builder()
            .timeout(Timeout.builder()
                .request(Duration.ofSeconds(requestTimeoutSeconds))
                .read(Duration.ofSeconds(readTimeoutSeconds))
                .build())
            .build();

        StringBuilder fullResponseBuilder = new StringBuilder();

        try (StreamResponse<ChatCompletionChunk> stream = openAiClient.chat().completions().createStreaming(params, options)) {
            stream.stream().forEach(chunk -> {
                if (chunk.choices().isEmpty()) {
                    return;
                }
                String delta = chunk.choices().get(0).delta().content().orElse("");
                if (!delta.isEmpty()) {
                    fullResponseBuilder.append(delta);
                    onDelta.accept(delta);
                }
            });
        } catch (Exception ex) {
            log.error("Book AI streaming failed for bookId={} model={}", bookId, configuredModel, ex);
            throw new BookAiGenerationException("AI streaming failed for book: " + bookId, ex);
        }

        String rawMessage = fullResponseBuilder.toString();
        BookAiContent aiContent = parseAiContent(rawMessage);

        // Persist the new version
        BookAiContentSnapshot snapshot = repository.insertNewCurrentVersion(
            bookId,
            aiContent,
            configuredModel,
            DEFAULT_PROVIDER,
            promptHash
        );

        return new GeneratedContent(rawMessage, snapshot);
    }

    /**
     * Converts domain snapshot to API DTO.
     */
    public static BookAiContentSnapshotDto toDto(BookAiContentSnapshot snapshot) {
        return new BookAiContentSnapshotDto(
            snapshot.aiContent().summary(),
            snapshot.aiContent().readerFit(),
            snapshot.aiContent().keyThemes(),
            snapshot.aiContent().takeaways(),
            snapshot.aiContent().context(),
            snapshot.version(),
            snapshot.generatedAt(),
            snapshot.model(),
            snapshot.provider()
        );
    }

    /**
     * Indicates whether AI generation is currently configured and available.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns the configured model identifier for stream metadata.
     */
    public String configuredModel() {
        return configuredModel;
    }

    /**
     * Returns the configured API mode label for stream metadata.
     */
    public String apiMode() {
        return DEFAULT_API_MODE;
    }

    private void ensureAvailable() {
        if (!available || openAiClient == null) {
            throw new IllegalStateException("AI content service is not configured");
        }
    }

    private BookPromptContext loadPromptContext(UUID bookId) {
        BookDetail detail = bookSearchService.fetchBookDetail(bookId)
            .orElseThrow(() -> new IllegalStateException("Book details unavailable for AI content: " + bookId));

        String title = firstText(detail.title(), "Unknown title");
        String authorList = detail.authors() == null || detail.authors().isEmpty()
            ? "Unknown author"
            : String.join(", ", detail.authors());
        String description = firstText(detail.description(), "No description provided.");
        String publishedDate = detail.publishedDate() != null ? detail.publishedDate().toString() : "Unknown";
        String publisher = firstText(detail.publisher(), "Unknown");

        return new BookPromptContext(bookId, title, authorList, description, publishedDate, publisher);
    }

    private String buildPrompt(BookPromptContext context) {
        return """
            Book ID: %s
            Title: %s
            Authors: %s
            Published: %s
            Publisher: %s

            Description:
            %s
            """.formatted(
                context.bookId(),
                context.title(),
                context.authors(),
                context.publishedDate(),
                context.publisher(),
                context.description());
    }

    private BookAiContent parseAiContent(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            throw new IllegalStateException("AI content response was empty");
        }

        JsonNode payload = parseJsonPayload(responseText);
        String summary = requireText(payload, "summary");
        String readerFit = requireText(payload, "readerFit", "reader_fit", "idealReader");
        List<String> themes = requireThemeList(payload, "keyThemes", "key_themes", "themes");
        List<String> takeaways = parseOptionalStringList(payload, "takeaways");
        String context = parseOptionalText(payload, "context");

        return new BookAiContent(summary, readerFit, themes, takeaways, context);
    }

    private JsonNode parseJsonPayload(String responseText) {
        String cleaned = responseText
            .replace("```json", "")
            .replace("```", "")
            .trim();

        try {
            return objectMapper.readTree(cleaned);
        } catch (JacksonException initialParseException) {
            int openBrace = cleaned.indexOf('{');
            int closeBrace = cleaned.lastIndexOf('}');
            if (openBrace < 0 || closeBrace <= openBrace) {
                throw new IllegalStateException("AI response did not include a valid JSON object");
            }
            log.warn("AI response required brace extraction fallback (initial parse failed: {})",
                initialParseException.getMessage());
            String extracted = cleaned.substring(openBrace, closeBrace + 1);
            try {
                return objectMapper.readTree(extracted);
            } catch (JacksonException exception) {
                throw new IllegalStateException("AI response JSON parsing failed", exception);
            }
        }
    }

    private String requireText(JsonNode payload, String field, String... aliases) {
        String direct = textOrNull(payload.get(field));
        if (StringUtils.hasText(direct)) {
            return direct;
        }

        for (String alias : aliases) {
            String aliasValue = textOrNull(payload.get(alias));
            if (StringUtils.hasText(aliasValue)) {
                return aliasValue;
            }
        }

        throw new IllegalStateException("AI response missing required field: " + field);
    }

    private List<String> requireThemeList(JsonNode payload, String field, String... aliases) {
        JsonNode node = payload.get(field);
        if (!isNonEmptyArray(node)) {
            for (String alias : aliases) {
                JsonNode aliasNode = payload.get(alias);
                if (isNonEmptyArray(aliasNode)) {
                    node = aliasNode;
                    break;
                }
            }
        }

        if (!isNonEmptyArray(node)) {
            throw new IllegalStateException("AI response missing required key theme array");
        }

        List<String> values = collectTextValues(node);
        if (values.size() < MIN_KEY_THEME_COUNT) {
            throw new IllegalStateException("AI response key theme list was empty");
        }
        if (values.size() > MAX_KEY_THEME_COUNT) {
            values = values.subList(0, MAX_KEY_THEME_COUNT);
        }

        return List.copyOf(values);
    }

    @Nullable
    private List<String> parseOptionalStringList(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (!isNonEmptyArray(node)) {
            return null;
        }
        List<String> values = collectTextValues(node);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > MAX_TAKEAWAY_COUNT) {
            values = values.subList(0, MAX_TAKEAWAY_COUNT);
        }
        return List.copyOf(values);
    }

    /** Extracts non-blank text values from a JSON array node. */
    private List<String> collectTextValues(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode elementNode : arrayNode) {
            String elementText = textOrNull(elementNode);
            if (StringUtils.hasText(elementText)) {
                values.add(elementText);
            }
        }
        return values;
    }

    @Nullable
    private String parseOptionalText(JsonNode payload, String field) {
        String value = textOrNull(payload.get(field));
        return StringUtils.hasText(value) ? value : null;
    }

    private boolean isNonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && node.size() > 0;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asString();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Normalizes an OpenAI-compatible base URL for the SDK.
     *
     * <p>Mirrors the normalization logic from the java-chat project's
     * {@code OpenAiSdkUrlNormalizer}: strips trailing slashes, strips
     * trailing {@code /embeddings}, and ensures a {@code /v1} suffix.</p>
     *
     * @param value raw base URL from environment configuration
     * @return SDK-ready base URL ending in {@code /v1}
     */
    public static String normalizeSdkBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://api.openai.com/v1";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/embeddings")) {
            normalized = normalized.substring(0, normalized.length() - "/embeddings".length());
        }
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    /**
     * Immutable generation result used by SSE controllers.
     */
    public record GeneratedContent(String rawMessage, BookAiContentSnapshot snapshot) {
    }

    private record BookPromptContext(UUID bookId,
                                     String title,
                                     String authors,
                                     String description,
                                     String publishedDate,
                                     String publisher) {
    }
}
