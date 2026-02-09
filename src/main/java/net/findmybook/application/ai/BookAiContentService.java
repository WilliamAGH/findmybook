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

/** Coordinates cache-first AI content generation for book detail pages. */
@Service
public class BookAiContentService {

    private static final Logger log = LoggerFactory.getLogger(BookAiContentService.class);
    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_API_MODE = "chat";
    private static final String DEFAULT_MODEL = "gpt-5-mini";
    private static final int MAX_KEY_THEME_COUNT = 6;
    private static final int MAX_TAKEAWAY_COUNT = 5;
    private static final long MAX_COMPLETION_TOKENS = 1000L;
    private static final int MIN_DESCRIPTION_LENGTH = 50;
    private static final double SAMPLING_TEMPERATURE = 0.2;
    private static final String API_KEY_SENTINEL = "not-configured";

    private static final String SYSTEM_PROMPT = """
        You are a knowledgeable book content writer. You receive a book's title, authors, publisher, and description. Use all context plus your genre/subject knowledge.
        ACCURACY RULES:
        - You MAY infer genre, audience, themes, and context from provided metadata.
        - You MUST NOT fabricate quotes, page counts, chapter titles, plot points, sales, awards, publication details, or biography claims absent from the description.
        - You MUST NOT invent statistics, studies, or research findings.
        - If a field cannot be reasonably inferred, return null (string) or [] (array).
        Return ONLY strict JSON with this exact shape:
        {"summary": string|null, "readerFit": string|null, "keyThemes": string[], "takeaways": string[], "context": string|null}
        Field rules:
        - summary: 2 concise sentences. Null only if description is truly empty.
        - readerFit: 1-2 sentences on audience. Null if indeterminate.
        - keyThemes: 3-6 short phrases. [] only when source is too vague.
        - takeaways: 2-5 specific points. [] if unsupported.
        - context: 1-2 sentences situating the book in its field.
        - No markdown, prose, or extra keys.
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

    /** Creates the service with persistence, lookup, and SDK dependencies. */
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

        if (StringUtils.hasText(apiKey) && !API_KEY_SENTINEL.equals(apiKey.trim())) {
            String resolvedBaseUrl = normalizeSdkBaseUrl(baseUrl);
            this.openAiClient = OpenAIOkHttpClient.builder().apiKey(apiKey.trim()).baseUrl(resolvedBaseUrl).maxRetries(0).build();
            this.available = true;
            log.info("Book AI content service configured (model={}, baseUrl={})", this.configuredModel, resolvedBaseUrl);
            return;
        }

        this.openAiClient = null;
        this.available = false;
        log.warn("Book AI content service is disabled: no API key configured");
    }

    /** Resolves any user-facing book identifier to canonical UUID. */
    public Optional<UUID> resolveBookId(String identifier) {
        return identifierResolver.resolveToUuid(identifier);
    }

    /** Loads the current persisted AI snapshot for a canonical book UUID. */
    public Optional<BookAiContentSnapshot> findCurrent(UUID bookId) {
        return repository.fetchCurrent(bookId);
    }

    /** Generates fresh AI content, streams text deltas, and persists a new current version. */
    public GeneratedContent generateAndPersist(UUID bookId, Consumer<String> onDelta) {
        ensureAvailable();
        String prompt = buildPrompt(loadPromptContext(bookId));
        String promptHash = sha256(prompt);

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
            .timeout(Timeout.builder().request(Duration.ofSeconds(requestTimeoutSeconds)).read(Duration.ofSeconds(readTimeoutSeconds)).build())
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
            throw new BookAiGenerationException(BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                "AI streaming failed for book: " + bookId, ex);
        }

        String rawMessage = fullResponseBuilder.toString();
        BookAiContent aiContent = parseAiContent(rawMessage);
        BookAiContentSnapshot snapshot = repository.insertNewCurrentVersion(bookId, aiContent, configuredModel, DEFAULT_PROVIDER, promptHash);
        return new GeneratedContent(rawMessage, snapshot);
    }

    /** Indicates whether AI generation is currently configured and available. */
    public boolean isAvailable() {
        return available;
    }

    /** Returns the configured model identifier for stream metadata. */
    public String configuredModel() {
        return configuredModel;
    }

    /** Returns the configured API mode label for stream metadata. */
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
        int descriptionLength = description == null ? 0 : description.trim().length();
        if (!StringUtils.hasText(description) || descriptionLength < MIN_DESCRIPTION_LENGTH) {
            throw new BookAiGenerationException(
                BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT,
                "Book description is missing or too short for faithful AI generation (bookId=" + bookId
                    + ", length=" + descriptionLength + ", minimum=" + MIN_DESCRIPTION_LENGTH + ")"
            );
        }

        String title = textOrFallback(detail.title(), "Unknown title");
        String authors = detail.authors() == null || detail.authors().isEmpty() ? "Unknown author" : String.join(", ", detail.authors());
        String publishedDate = detail.publishedDate() != null ? detail.publishedDate().toString() : "Unknown";
        String publisher = textOrFallback(detail.publisher(), "Unknown");
        return new BookPromptContext(bookId, title, authors, description.trim(), publishedDate, publisher);
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
            """.formatted(context.bookId(), context.title(), context.authors(), context.publishedDate(), context.publisher(), context.description());
    }

    private BookAiContent parseAiContent(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            throw new IllegalStateException("AI content response was empty");
        }

        JsonNode payload = parseJsonPayload(responseText);
        String summary = requiredText(payload, "summary");
        Optional<String> readerFit = optionalText(payload, "readerFit", "reader_fit", "idealReader");
        List<String> themes = stringList(payload, MAX_KEY_THEME_COUNT, "keyThemes", "key_themes", "themes");
        List<String> takeawaysList = stringList(payload, MAX_TAKEAWAY_COUNT, "takeaways");
        Optional<List<String>> takeaways = takeawaysList.isEmpty() ? Optional.empty() : Optional.of(takeawaysList);
        Optional<String> context = optionalText(payload, "context");

        if (themes.isEmpty() && takeaways.isEmpty()) {
            log.warn("AI generated content with no themes and no takeaways - likely insufficient source material");
        }

        return new BookAiContent(summary, readerFit.orElse(null), themes, takeaways.orElse(null), context.orElse(null));
    }

    private JsonNode parseJsonPayload(String responseText) {
        String cleaned = responseText.replace("```json", "").replace("```", "").trim();
        try {
            return objectMapper.readTree(cleaned);
        } catch (JacksonException initialParseException) {
            int openBrace = cleaned.indexOf('{');
            int closeBrace = cleaned.lastIndexOf('}');
            if (openBrace < 0 || closeBrace <= openBrace) {
                throw new IllegalStateException("AI response did not include a valid JSON object");
            }
            log.warn("AI response required brace extraction fallback (initial parse failed: {})", initialParseException.getMessage());
            String extracted = cleaned.substring(openBrace, closeBrace + 1);
            try {
                return objectMapper.readTree(extracted);
            } catch (JacksonException exception) {
                throw new IllegalStateException("AI response JSON parsing failed", exception);
            }
        }
    }

    private String requiredText(JsonNode payload, String field, String... aliases) {
        return optionalText(payload, field, aliases)
            .orElseThrow(() -> new IllegalStateException("AI response missing required field: " + field));
    }

    private Optional<String> optionalText(JsonNode payload, String field, String... aliases) {
        JsonNode node = resolveJsonNode(payload, field, aliases);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        String text = node.asText(null);
        return StringUtils.hasText(text) ? Optional.of(text.trim()) : Optional.empty();
    }

    private List<String> stringList(JsonNode payload, int maxSize, String field, String... aliases) {
        JsonNode node = resolveJsonNode(payload, field, aliases);
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode elementNode : node) {
            String text = elementNode == null || elementNode.isNull() ? null : elementNode.asText(null);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            values.add(text.trim());
            if (values.size() == maxSize) {
                break;
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private JsonNode resolveJsonNode(JsonNode payload, String field, String... aliases) {
        JsonNode node = payload.get(field);
        if (node != null && !node.isNull()) {
            return node;
        }
        for (String alias : aliases) {
            JsonNode aliasNode = payload.get(alias);
            if (aliasNode != null && !aliasNode.isNull()) {
                return aliasNode;
            }
        }
        return null;
    }

    private String textOrFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Normalizes an OpenAI-compatible base URL for the SDK. */
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
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    /** Immutable generation result used by SSE controllers. */
    public record GeneratedContent(String rawMessage, BookAiContentSnapshot snapshot) {}
    private record BookPromptContext(UUID bookId, String title, String authors, String description, String publishedDate, String publisher) {}
}
