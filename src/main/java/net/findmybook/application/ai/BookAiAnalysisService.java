package net.findmybook.application.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
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
import net.findmybook.adapters.persistence.BookAiAnalysisRepository;
import net.findmybook.controller.dto.BookAiSnapshotDto;
import net.findmybook.domain.ai.BookAiAnalysis;
import net.findmybook.domain.ai.BookAiSnapshot;
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
 * Coordinates cache-first reader-fit AI analysis for book detail pages.
 *
 * <p>This application service keeps controller logic thin by owning:
 * prompt construction, OpenAI streaming, strict response parsing, and
 * persistence into versioned Postgres snapshots.</p>
 */
@Service
public class BookAiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BookAiAnalysisService.class);

    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_API_MODE = "responses";
    private static final String DEFAULT_MODEL = "gpt-5-mini";
    private static final int MAX_KEY_THEME_COUNT = 6;
    private static final int MIN_KEY_THEME_COUNT = 1;

    private static final String SYSTEM_PROMPT = """
        You write concise, helpful reader-fit analysis for a single book.
        Return ONLY strict JSON using this exact shape:
        {
          \"summary\": string,
          \"readerFit\": string,
          \"keyThemes\": string[]
        }

        Rules:
        - summary: 2 concise sentences, concrete and specific.
        - readerFit: 1-2 concise sentences on who should read it and why.
        - keyThemes: 3 to 6 short phrases.
        - No markdown, no prose outside JSON, no extra keys.
        """;

    private final BookAiAnalysisRepository repository;
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
    public BookAiAnalysisService(
        BookAiAnalysisRepository repository,
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

        if (StringUtils.hasText(apiKey)) {
            String resolvedBaseUrl = normalizeBaseUrl(baseUrl);
            this.openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(resolvedBaseUrl)
                .maxRetries(0)
                .build();
            this.available = true;
            log.info("Book AI analysis service configured (model={}, baseUrl={})", this.configuredModel, resolvedBaseUrl);
        } else {
            this.openAiClient = null;
            this.available = false;
            log.warn("Book AI analysis service is disabled: no API key configured");
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
    public Optional<BookAiSnapshot> findCurrent(UUID bookId) {
        return repository.fetchCurrent(bookId);
    }

    /**
     * Generates fresh AI analysis, streams text deltas, and persists a new current version.
     *
     * @param bookId canonical book UUID
     * @param onDelta callback invoked for each model text delta
     * @return generated raw message plus persisted snapshot metadata
     */
    public GeneratedAnalysis generateAndPersist(UUID bookId, Consumer<String> onDelta) {
        ensureAvailable();

        BookPromptContext promptContext = loadPromptContext(bookId);
        String prompt = buildPrompt(promptContext);
        String promptHash = sha256(prompt);

        ResponseCreateParams params = ResponseCreateParams.builder()
            .model(ResponsesModel.ofString(configuredModel))
            .instructions(SYSTEM_PROMPT)
            .input(prompt)
            .build();

        RequestOptions requestOptions = RequestOptions.builder()
            .timeout(Timeout.builder()
                .request(Duration.ofSeconds(requestTimeoutSeconds))
                .read(Duration.ofSeconds(readTimeoutSeconds))
                .build())
            .build();

        StringBuilder streamedText = new StringBuilder();
        try (StreamResponse<ResponseStreamEvent> stream = openAiClient.responses().createStreaming(params, requestOptions)) {
            stream.stream().forEach(event -> {
                Optional<ResponseTextDeltaEvent> deltaEvent = event.outputTextDelta();
                if (deltaEvent.isEmpty()) {
                    return;
                }
                String delta = deltaEvent.get().delta();
                if (!StringUtils.hasText(delta)) {
                    return;
                }
                streamedText.append(delta);
                onDelta.accept(delta);
            });
        } catch (RuntimeException exception) {
            log.error("Book AI streaming failed for bookId={} model={}", bookId, configuredModel, exception);
            throw new IllegalStateException("AI analysis generation failed", exception);
        }

        String rawMessage = streamedText.toString().trim();
        BookAiAnalysis analysis = parseAnalysis(rawMessage);
        BookAiSnapshot persistedSnapshot = repository.insertNewCurrentVersion(
            bookId,
            analysis,
            configuredModel,
            DEFAULT_PROVIDER,
            promptHash
        );

        return new GeneratedAnalysis(rawMessage, persistedSnapshot);
    }

    /**
     * Converts domain snapshot to API DTO.
     */
    public static BookAiSnapshotDto toDto(BookAiSnapshot snapshot) {
        return new BookAiSnapshotDto(
            snapshot.analysis().summary(),
            snapshot.analysis().readerFit(),
            snapshot.analysis().keyThemes(),
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
            throw new IllegalStateException("AI analysis service is not configured");
        }
    }

    private BookPromptContext loadPromptContext(UUID bookId) {
        BookDetail detail = bookSearchService.fetchBookDetail(bookId)
            .orElseThrow(() -> new IllegalStateException("Book details unavailable for AI analysis: " + bookId));

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

    private BookAiAnalysis parseAnalysis(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            throw new IllegalStateException("AI analysis response was empty");
        }

        JsonNode payload = parseJsonPayload(responseText);
        String summary = requireText(payload, "summary");
        String readerFit = requireText(payload, "readerFit", "reader_fit", "idealReader");
        List<String> themes = requireThemeList(payload, "keyThemes", "key_themes", "themes");

        return new BookAiAnalysis(summary, readerFit, themes);
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

        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = textOrNull(item);
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }

        if (values.size() < MIN_KEY_THEME_COUNT) {
            throw new IllegalStateException("AI response key theme list was empty");
        }
        if (values.size() > MAX_KEY_THEME_COUNT) {
            values = values.subList(0, MAX_KEY_THEME_COUNT);
        }

        return List.copyOf(values);
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String normalizeBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://api.openai.com/v1";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Immutable generation result used by SSE controllers.
     */
    public record GeneratedAnalysis(String rawMessage, BookAiSnapshot snapshot) {
    }

    private record BookPromptContext(UUID bookId,
                                     String title,
                                     String authors,
                                     String description,
                                     String publishedDate,
                                     String publisher) {
    }
}
