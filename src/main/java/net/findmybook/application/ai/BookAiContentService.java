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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.HashUtils;
import net.findmybook.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/** Coordinates cache-first AI content generation for book detail pages. */
@Service
public class BookAiContentService {

    private static final Logger log = LoggerFactory.getLogger(BookAiContentService.class);
    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_API_MODE = "chat";
    private static final String DEFAULT_MODEL = "gpt-5-mini";
    private static final long MAX_COMPLETION_TOKENS = 1000L;
    private static final int MAX_GENERATION_ATTEMPTS = 3;
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
    private final BookDataOrchestrator bookDataOrchestrator;
    private final AiContentJsonParser jsonParser;
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
        BookDataOrchestrator bookDataOrchestrator,
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
        this.bookDataOrchestrator = bookDataOrchestrator;
        this.jsonParser = new AiContentJsonParser(objectMapper);
        this.configuredModel = StringUtils.hasText(model) ? model.trim() : DEFAULT_MODEL;
        this.requestTimeoutSeconds = Math.max(1L, requestTimeoutSeconds);
        this.readTimeoutSeconds = Math.max(1L, readTimeoutSeconds);

        if (StringUtils.hasText(apiKey) && !API_KEY_SENTINEL.equals(apiKey.trim())) {
            String resolvedBaseUrl = UrlUtils.normalizeOpenAiBaseUrl(baseUrl);
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
        return generateAndPersistFromPrompt(bookId, prompt, promptHash, onDelta);
    }

    /**
     * Generates fresh AI content only when prompt context has changed since the current version.
     *
     * @param bookId canonical book UUID
     * @param onDelta callback for streamed model deltas
     * @return generation outcome with generated/skipped semantics
     */
    public GenerationOutcome generateAndPersistIfPromptChanged(UUID bookId, Consumer<String> onDelta) {
        ensureAvailable();
        String prompt = buildPrompt(loadPromptContext(bookId));
        String promptHash = sha256(prompt);
        Optional<String> existingPromptHash = repository.fetchCurrentPromptHash(bookId);
        if (existingPromptHash.isPresent() && existingPromptHash.get().equals(promptHash)) {
            return GenerationOutcome.skipped(bookId, promptHash, findCurrent(bookId));
        }
        GeneratedContent generated = generateAndPersistFromPrompt(bookId, prompt, promptHash, onDelta);
        return GenerationOutcome.generated(bookId, promptHash, Optional.of(generated.snapshot()));
    }

    private GeneratedContent generateAndPersistFromPrompt(UUID bookId, String prompt, String promptHash, Consumer<String> onDelta) {
        BookAiGenerationException lastGenerationFailure = null;
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return generateAndPersistSingleAttempt(bookId, prompt, promptHash, onDelta);
            } catch (BookAiGenerationException generationFailure) {
                lastGenerationFailure = generationFailure;
                if (attempt < MAX_GENERATION_ATTEMPTS && isRetryableGenerationFailure(generationFailure)) {
                    log.warn("AI generation attempt {}/{} failed for bookId={} model={} (will retry): {}",
                        attempt, MAX_GENERATION_ATTEMPTS, bookId, configuredModel, generationFailure.getMessage());
                    continue;
                }
                break;
            }
        }

        if (lastGenerationFailure == null) {
            throw new BookAiGenerationException(BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                "AI content generation failed (%s)".formatted(configuredModel));
        }
        throw lastGenerationFailure;
    }

    private GeneratedContent generateAndPersistSingleAttempt(UUID bookId, String prompt, String promptHash, Consumer<String> onDelta) {
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
            String detail = BookAiGenerationException.describeApiError(ex);
            log.error("AI streaming failed for bookId={} model={}: {}", bookId, configuredModel, detail);
            throw new BookAiGenerationException(BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                "AI content generation failed (%s): %s".formatted(configuredModel, detail), ex);
        }

        String rawMessage = fullResponseBuilder.toString();
        BookAiContent aiContent;
        try {
            aiContent = jsonParser.parse(rawMessage);
        } catch (IllegalStateException parseFailure) {
            log.error("AI content parsing failed for bookId={} model={}: {}", bookId, configuredModel, parseFailure.getMessage());
            String parseMessage = StringUtils.hasText(parseFailure.getMessage()) ? parseFailure.getMessage() : "invalid JSON response";
            throw new BookAiGenerationException(BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                "AI content generation failed (%s): %s".formatted(configuredModel, parseMessage), parseFailure);
        }
        BookAiContentSnapshot snapshot = repository.insertNewCurrentVersion(bookId, aiContent, configuredModel, DEFAULT_PROVIDER, promptHash);
        return new GeneratedContent(rawMessage, snapshot);
    }

    private boolean isRetryableGenerationFailure(BookAiGenerationException generationFailure) {
        if (generationFailure.errorCode() != BookAiGenerationException.ErrorCode.GENERATION_FAILED) {
            return false;
        }
        Throwable cause = generationFailure.getCause();
        if (cause instanceof OpenAIException) {
            return true;
        }
        if (cause instanceof IllegalStateException parseFailure) {
            String message = parseFailure.getMessage();
            if (!StringUtils.hasText(message)) {
                return false;
            }
            return message.contains("response was empty")
                || message.contains("did not include a valid JSON object")
                || message.contains("JSON parsing failed")
                || message.contains("missing required field")
                || message.contains("contained no choices");
        }
        return false;
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
        String description = detail.description() == null ? null : detail.description().trim();
        try {
            description = bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, description, MIN_DESCRIPTION_LENGTH);
        } catch (IllegalStateException | DataAccessException ex) {
            log.error("Description enrichment failed for bookId={}", bookId, ex);
            throw new BookAiGenerationException(BookAiGenerationException.ErrorCode.ENRICHMENT_FAILED,
                "Description enrichment failed for book: " + bookId, ex);
        }
        int descriptionLength = descriptionLength(description);
        if (isDescriptionTooShort(description)) {
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
        return new BookPromptContext(bookId, title, authors, description, publishedDate, publisher);
    }

    private boolean isDescriptionTooShort(String description) {
        return !StringUtils.hasText(description) || description.trim().length() < MIN_DESCRIPTION_LENGTH;
    }

    private int descriptionLength(String description) {
        return description == null ? 0 : description.trim().length();
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

    private String textOrFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String sha256(String input) {
        try {
            return HashUtils.sha256Hex(input);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Immutable generation result used by SSE controllers. */
    public record GeneratedContent(String rawMessage, BookAiContentSnapshot snapshot) {}

    /** Immutable outcome used by background ingestion generation workflows. */
    public record GenerationOutcome(UUID bookId,
                                    boolean generated,
                                    String promptHash,
                                    Optional<BookAiContentSnapshot> snapshot) {
        private static GenerationOutcome skipped(UUID bookId, String promptHash, Optional<BookAiContentSnapshot> snapshot) {
            return new GenerationOutcome(bookId, false, promptHash, snapshot);
        }

        private static GenerationOutcome generated(UUID bookId, String promptHash, Optional<BookAiContentSnapshot> snapshot) {
            return new GenerationOutcome(bookId, true, promptHash, snapshot);
        }
    }

    private record BookPromptContext(UUID bookId, String title, String authors, String description, String publishedDate, String publisher) {}
}
