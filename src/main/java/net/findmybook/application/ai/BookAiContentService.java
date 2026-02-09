package net.findmybook.application.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
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

    private static final int MAX_TAKEAWAY_COUNT = 5;
    private static final long MAX_COMPLETION_TOKENS = 1000L;

    /**
     * Minimum character count for a book description to be considered sufficient
     * for AI content generation. Descriptions shorter than this produce unreliable
     * output because the model lacks enough source material and resorts to fabrication.
     */
    private static final int MIN_DESCRIPTION_LENGTH = 50;
    /**
     * Low temperature for grounded, natural-sounding output.
     * 0.2 provides enough variation for readable prose while keeping
     * output faithful to the source material. Values above 0.5 risk
     * creative divergence; the original 0.7 was a hallucination vector.
     */
    private static final double SAMPLING_TEMPERATURE = 0.2;

    /**
     * Sentinel value set by {@code normalizeOpenAiSdkConfig} when no real API key is available.
     * Must match {@code BookRecommendationEngineApplication.AI_KEY_NOT_CONFIGURED}.
     */
    private static final String API_KEY_SENTINEL = "not-configured";

    private static final String SYSTEM_PROMPT = """
        You are a knowledgeable book content writer. You receive a book's title, \
        authors, publisher, and description. Use ALL of this context — plus your \
        general knowledge of the author, genre, and subject matter — to produce \
        useful, accurate content.

        ACCURACY RULES (highest priority):
        - You MAY use your training knowledge to infer genre, audience, themes, \
          and context from the title, author, categories, and description.
        - You MUST NOT fabricate specific facts: no invented quotes, page counts, \
          chapter titles, plot points, sales figures, publication details, awards, \
          or biographical claims unless they appear in the provided description.
        - You MUST NOT invent statistics, studies, or research findings.
        - If a field cannot be reasonably inferred, set it to null (for strings) \
          or an empty array [] (for arrays). Never pad content with guesses.

        Return ONLY strict JSON using this exact shape:
        {
          "summary": string | null,
          "readerFit": string | null,
          "keyThemes": string[],
          "takeaways": string[],
          "context": string | null
        }

        Field rules (subject to accuracy rules above):
        - summary: 2 concise sentences describing the book's content based on \
          the description and your knowledge of the subject matter. Null only if \
          the description is truly empty.
        - readerFit: 1-2 sentences on the intended audience, inferred from the \
          description, genre, and author. Null if genuinely indeterminate.
        - keyThemes: 3 to 6 short topic phrases. Draw from the description and \
          reasonable inference about the subject. Empty array [] only when the \
          description is too vague to identify any themes.
        - takeaways: 2 to 5 specific points a reader will gain, based on what \
          the description states or clearly implies. Empty array [] if the \
          description does not support any.
        - context: 1-2 sentences placing the book in its genre or field. Use \
          your knowledge of the author and subject to provide useful framing.
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
            // openai-java 4.16.1 deprecates maxTokens() in favor of maxCompletionTokens().
            .maxCompletionTokens(MAX_COMPLETION_TOKENS)
            .temperature(SAMPLING_TEMPERATURE)
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
        } catch (OpenAIException ex) {
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

        String description = detail.description();
        if (!StringUtils.hasText(description) || description.trim().length() < MIN_DESCRIPTION_LENGTH) {
            throw new BookAiGenerationException(
                "Book description is missing or too short for faithful AI generation (bookId=" + bookId
                    + ", length=" + (description == null ? 0 : description.trim().length())
                    + ", minimum=" + MIN_DESCRIPTION_LENGTH + ")"
            );
        }

        String title = firstText(detail.title(), "Unknown title");
        String authorList = detail.authors() == null || detail.authors().isEmpty()
            ? "Unknown author"
            : String.join(", ", detail.authors());
        String publishedDate = detail.publishedDate() != null ? detail.publishedDate().toString() : "Unknown";
        String publisher = firstText(detail.publisher(), "Unknown");

        return new BookPromptContext(bookId, title, authorList, description.trim(), publishedDate, publisher);
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
        Optional<String> readerFit = parseOptionalText(payload, "readerFit", "reader_fit", "idealReader");
        List<String> themes = parseThemeList(payload, "keyThemes", "key_themes", "themes");
        Optional<List<String>> takeaways = parseOptionalStringList(payload, "takeaways");
        Optional<String> context = parseOptionalText(payload, "context");

        if (themes.isEmpty() && takeaways.isEmpty()) {
            log.warn("AI generated content with no themes and no takeaways — " +
                "likely insufficient source material");
        }

        return new BookAiContent(
            summary, readerFit.orElse(null), themes, takeaways.orElse(null), context.orElse(null));
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
        JsonNode node = resolveJsonNode(payload, field, aliases);
        String text = textOrNull(node);
        if (StringUtils.hasText(text)) {
            return text;
        }
        throw new IllegalStateException("AI response missing required field: " + field);
    }

    /**
     * Parses a theme list that may legitimately be empty when the model
     * cannot extract themes from the provided description.
     * Themes are capped at {@link #MAX_KEY_THEME_COUNT}.
     */
    private List<String> parseThemeList(JsonNode payload, String field, String... aliases) {
        JsonNode node = resolveJsonNode(payload, field, aliases);

        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }

        List<String> values = collectTextValues(node);
        if (values.size() > MAX_KEY_THEME_COUNT) {
            values = values.subList(0, MAX_KEY_THEME_COUNT);
        }

        return List.copyOf(values);
    }

    private Optional<List<String>> parseOptionalStringList(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (!isValidNode(node) || !node.isArray() || node.size() == 0) {
            return Optional.empty();
        }
        List<String> values = collectTextValues(node);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        if (values.size() > MAX_TAKEAWAY_COUNT) {
            values = values.subList(0, MAX_TAKEAWAY_COUNT);
        }
        return Optional.of(List.copyOf(values));
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

    private Optional<String> parseOptionalText(JsonNode payload, String field, String... aliases) {
        JsonNode node = resolveJsonNode(payload, field, aliases);
        String text = textOrNull(node);
        return StringUtils.hasText(text) ? Optional.of(text) : Optional.empty();
    }

    /**
     * Resolves a JSON node from a primary field name or any of its aliases.
     * Returns the first non-null, non-missing node found, or null if none match.
     */
    private JsonNode resolveJsonNode(JsonNode payload, String field, String... aliases) {
        JsonNode node = payload.get(field);
        if (isValidNode(node)) {
            return node;
        }
        for (String alias : aliases) {
            JsonNode aliasNode = payload.get(alias);
            if (isValidNode(aliasNode)) {
                return aliasNode;
            }
        }
        return null;
    }

    /** Returns true if the node exists and is not explicitly null. */
    private boolean isValidNode(JsonNode node) {
        return node != null && !node.isNull();
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asString();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String text, String fallback) {
        return StringUtils.hasText(text) ? text.trim() : fallback;
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
     * @param rawUrl raw base URL from environment configuration
     * @return SDK-ready base URL ending in {@code /v1}
     */
    public static String normalizeSdkBaseUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return "https://api.openai.com/v1";
        }
        String normalized = rawUrl.trim();
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
