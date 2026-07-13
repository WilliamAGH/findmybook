package net.findmybook.application.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionChunk;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.findmybook.adapters.persistence.BookAiContentRepository;
import net.findmybook.boot.OpenAiProperties;
import net.findmybook.support.llm.LlmGatewayTier;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final int LIVE_SDK_MAX_RETRIES = 0;
    private static final int BACKGROUND_SDK_MAX_RETRIES = 2;
    private static final int MIN_DESCRIPTION_LENGTH = 50;
    private static final double SAMPLING_TEMPERATURE = 0.2;
    private static final String SYSTEM_PROMPT = """
        You are a knowledgeable book content writer. You receive a book's title, authors, publisher, and description. Use all context plus your genre/subject knowledge.
        ACCURACY RULES:
        - You MAY infer genre, audience, themes, and context from provided metadata.
        - You MUST NOT fabricate quotes, page counts, chapter titles, plot points, sales, awards, publication details, or biography claims absent from the description.
        - You MUST NOT invent statistics, studies, or research findings.
        - If an optional field cannot be reasonably inferred, return null (string) or [] (array).
        Return ONLY strict JSON with this exact shape:
        {"summary": string, "readerFit": string|null, "keyThemes": string[], "takeaways": string[], "context": string|null}
        Field rules:
        - summary: Exactly 2 concise, non-empty sentences grounded in the supplied description.
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
    private final Map<LlmGatewayTier, OpenAIClient> clientsByTier;
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
        OpenAiProperties openAiProperties
    ) {
        this.repository = repository;
        this.identifierResolver = identifierResolver;
        this.bookSearchService = bookSearchService;
        this.bookDataOrchestrator = bookDataOrchestrator;
        this.jsonParser = new AiContentJsonParser(objectMapper);
        this.configuredModel = openAiProperties.model();
        this.requestTimeoutSeconds = openAiProperties.requestTimeoutSeconds();
        this.readTimeoutSeconds = openAiProperties.readTimeoutSeconds();

        if (openAiProperties.isConfigured()) {
            EnumMap<LlmGatewayTier, OpenAIClient> clients = new EnumMap<>(LlmGatewayTier.class);
            for (LlmGatewayTier tier : LlmGatewayTier.values()) {
                clients.put(tier, OpenAIOkHttpClient.builder()
                    .apiKey(openAiProperties.apiKey())
                    .baseUrl(openAiProperties.baseUrl())
                    .maxRetries(tier == LlmGatewayTier.LIVE_RENDER ? LIVE_SDK_MAX_RETRIES : BACKGROUND_SDK_MAX_RETRIES)
                    .putHeader(LlmGatewayTier.HEADER_NAME, tier.headerValue())
                    .build());
            }
            this.clientsByTier = Map.copyOf(clients);
            this.available = true;
            log.info(
                "Book AI content service configured (model={}, baseUrl={}, tiers={})",
                this.configuredModel,
                openAiProperties.baseUrl(),
                clients.keySet()
            );
            return;
        }

        this.clientsByTier = Map.of();
        this.available = false;
        log.warn("Book AI content service is disabled: missing OPENAI_API_KEY, OPENAI_BASE_URL, or OPENAI_MODEL");
    }

    /** Resolves any user-facing book identifier to the exact displayed-book UUID. */
    public Optional<UUID> resolveBookId(String identifier) {
        return identifierResolver.resolveExactBookUuid(identifier);
    }

    /** Loads the current persisted AI snapshot for a canonical book UUID. */
    public Optional<BookAiContentSnapshot> findCurrent(UUID bookId) {
        return repository.fetchCurrent(bookId);
    }

    /**
     * Generates, validates, and persists fresh AI content before delivering its complete buffered payload.
     *
     * @param bookId canonical book UUID
     * @param onValidatedBufferedPayload callback for the complete payload after validation and persistence
     * @param tier gateway priority tier controlling the {@code X-Tier} header on outbound calls
     * @return generated content + persisted snapshot
     */
    public GeneratedContent generateAndPersist(
        UUID bookId,
        Consumer<String> onValidatedBufferedPayload,
        LlmGatewayTier tier
    ) {
        return generateAndPersist(bookId, onValidatedBufferedPayload, tier, new GenerationControl());
    }

    /**
     * Generates and persists fresh AI content while honoring caller-owned cancellation.
     *
     * @param bookId canonical book UUID
     * @param onValidatedBufferedPayload callback for the complete payload after validation and persistence
     * @param tier gateway priority tier controlling the {@code X-Tier} header on outbound calls
     * @param generationControl atomically coordinates cancellation with the persistence boundary
     * @return generated content + persisted snapshot
     */
    public GeneratedContent generateAndPersist(
        UUID bookId,
        Consumer<String> onValidatedBufferedPayload,
        LlmGatewayTier tier,
        GenerationControl generationControl
    ) {
        requireGenerationControl(generationControl);
        throwIfGenerationCancelled(generationControl);
        ensureAvailable();
        requireTier(tier);
        String prompt = buildPrompt(loadPromptContext(bookId));
        String promptHash = sha256(prompt);
        return generateAndPersistFromPrompt(
            bookId,
            prompt,
            promptHash,
            onValidatedBufferedPayload,
            tier,
            generationControl
        );
    }

    /**
     * Generates fresh AI content only when prompt context has changed since the current version.
     *
     * @param bookId canonical book UUID
     * @param onValidatedBufferedPayload callback for the complete payload after validation and persistence
     * @param tier gateway priority tier controlling the {@code X-Tier} header on outbound calls
     * @return generation outcome with generated/skipped semantics
     */
    public GenerationOutcome generateAndPersistIfPromptChanged(
        UUID bookId,
        Consumer<String> onValidatedBufferedPayload,
        LlmGatewayTier tier
    ) {
        GenerationControl generationControl = new GenerationControl();
        throwIfGenerationCancelled(generationControl);
        ensureAvailable();
        requireTier(tier);
        String prompt = buildPrompt(loadPromptContext(bookId));
        String promptHash = sha256(prompt);
        Optional<String> existingPromptHash = repository.fetchCurrentPromptHash(bookId);
        if (existingPromptHash.isPresent() && existingPromptHash.get().equals(promptHash)) {
            return GenerationOutcome.skipped(bookId, promptHash, findCurrent(bookId));
        }
        GeneratedContent generated = generateAndPersistFromPrompt(
            bookId,
            prompt,
            promptHash,
            onValidatedBufferedPayload,
            tier,
            generationControl
        );
        return GenerationOutcome.generated(bookId, promptHash, Optional.of(generated.snapshot()));
    }

    private static void requireTier(LlmGatewayTier tier) {
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
    }

    private GeneratedContent generateAndPersistFromPrompt(
        UUID bookId,
        String prompt,
        String promptHash,
        Consumer<String> onValidatedBufferedPayload,
        LlmGatewayTier tier,
        GenerationControl generationControl
    ) {
        int maxGenerationAttempts = tier.maxGenerationAttempts();
        BookAiGenerationException lastGenerationFailure = null;
        for (int attempt = 1; attempt <= maxGenerationAttempts; attempt++) {
            throwIfGenerationCancelled(generationControl);
            AtomicBoolean deliveredValidatedPayload = new AtomicBoolean(false);
            try {
                return generateAndPersistSingleAttempt(bookId, prompt, promptHash, validatedBufferedPayload -> {
                    deliveredValidatedPayload.set(true);
                    onValidatedBufferedPayload.accept(validatedBufferedPayload);
                }, tier, generationControl);
            } catch (BookAiGenerationException generationFailure) {
                throwIfGenerationCancelled(generationControl);
                lastGenerationFailure = generationFailure;
                boolean retryDoesNotReplayValidatedPayload = tier == LlmGatewayTier.BACKGROUND_BATCH
                    || !deliveredValidatedPayload.get();
                if (attempt < maxGenerationAttempts
                    && retryDoesNotReplayValidatedPayload
                    && isRetryableGenerationFailure(generationFailure, tier)) {
                    log.warn("AI generation attempt {}/{} failed for bookId={} model={} tier={} (will retry): {}",
                        attempt, maxGenerationAttempts, bookId, configuredModel, tier.headerValue(), generationFailure.getMessage());
                    continue;
                }
                break;
            }
        }

        if (lastGenerationFailure == null) {
            throw new BookAiGenerationException(BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                "AI content generation failed (%s)".formatted(configuredModel));
        }
        if (tier == LlmGatewayTier.LIVE_RENDER) {
            log.error(
                "AI generation exhausted attempts for bookId={} model={} tier={} attempts={}: {}",
                bookId,
                configuredModel,
                tier.headerValue(),
                maxGenerationAttempts,
                lastGenerationFailure.getMessage()
            );
        }
        throw lastGenerationFailure;
    }

    private GeneratedContent generateAndPersistSingleAttempt(
        UUID bookId,
        String prompt,
        String promptHash,
        Consumer<String> onValidatedBufferedPayload,
        LlmGatewayTier tier,
        GenerationControl generationControl
    ) {
        throwIfGenerationCancelled(generationControl);
        OpenAIClient tieredClient = clientsByTier.get(tier);
        if (tieredClient == null) {
            throw new BookAiGenerationException(BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                "No AI content client configured for tier %s".formatted(tier));
        }
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(configuredModel))
            .messages(List.of(
                ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder().content(SYSTEM_PROMPT).build()),
                ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build())
            ))
            .maxCompletionTokens(tier.maxCompletionTokens())
            .temperature(SAMPLING_TEMPERATURE)
            .build();

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
        StringBuilder fullResponseBuilder = new StringBuilder();
        AtomicReference<ChatCompletionChunk.Choice.FinishReason> finishReason = new AtomicReference<>();
        AtomicBoolean refusalPresent = new AtomicBoolean(false);
        try (StreamResponse<ChatCompletionChunk> stream = tieredClient.chat().completions().createStreaming(params, options)) {
            stream.stream().forEach(chunk -> {
                throwIfGenerationCancelled(generationControl);
                if (chunk.choices().isEmpty()) {
                    return;
                }
                ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                choice.finishReason().ifPresent(finishReason::set);
                choice.delta().refusal()
                    .filter(StringUtils::hasText)
                    .ifPresent(refusal -> refusalPresent.set(true));
                String delta = choice.delta().content().orElse("");
                if (!delta.isEmpty()) {
                    fullResponseBuilder.append(delta);
                }
            });
        } catch (OpenAIException ex) {
            throwIfGenerationCancelled(generationControl);
            String detail = BookAiGenerationException.describeApiError(ex);
            log.warn("AI streaming failed for bookId={} model={} tier={}: {}", bookId, configuredModel, tier.headerValue(), detail);
            BookAiGenerationException.ErrorCode errorCode = ex instanceof OpenAIInvalidDataException
                ? BookAiGenerationException.ErrorCode.INVALID_RESPONSE
                : BookAiGenerationException.ErrorCode.GENERATION_FAILED;
            throw new BookAiGenerationException(errorCode,
                "AI content generation failed (%s, tier=%s): %s".formatted(configuredModel, tier.headerValue(), detail), ex);
        }

        throwIfGenerationCancelled(generationControl);
        if (ChatCompletionChunk.Choice.FinishReason.LENGTH.equals(finishReason.get())) {
            log.warn(
                "AI content response exhausted completion token budget for bookId={} model={} tier={} maxCompletionTokens={} finishReason=length",
                bookId, configuredModel, tier.headerValue(), tier.maxCompletionTokens()
            );
            throw new BookAiGenerationException(
                BookAiGenerationException.ErrorCode.INCOMPLETE_RESPONSE,
                "AI content response exhausted completion token budget "
                    + "(maxCompletionTokens=%d, finishReason=length)".formatted(tier.maxCompletionTokens())
            );
        }
        if (refusalPresent.get()) {
            log.warn(
                "AI content request was refused for bookId={} model={} tier={} refusalPresent=true",
                bookId, configuredModel, tier.headerValue()
            );
            throw new BookAiGenerationException(
                BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                "AI content request was refused (refusalPresent=true)"
            );
        }
        if (!ChatCompletionChunk.Choice.FinishReason.STOP.equals(finishReason.get())) {
            String terminalReason = finishReason.get() == null ? "missing" : finishReason.get().asString();
            log.warn(
                "AI content response ended without stop for bookId={} model={} tier={} finishReason={} responseCharacters={}",
                bookId, configuredModel, tier.headerValue(), terminalReason, fullResponseBuilder.length()
            );
            throw new BookAiGenerationException(
                BookAiGenerationException.ErrorCode.INCOMPLETE_RESPONSE,
                "AI content response ended without stop (finishReason=%s)".formatted(terminalReason)
            );
        }

        String validatedBufferedPayload = fullResponseBuilder.toString();
        throwIfGenerationCancelled(generationControl);
        BookAiContent aiContent;
        try {
            aiContent = jsonParser.parse(validatedBufferedPayload);
        } catch (IllegalStateException parseFailure) {
            log.warn("AI content parsing failed for bookId={} model={} tier={}: {}",
                bookId, configuredModel, tier.headerValue(), parseFailure.getMessage());
            String parseMessage = StringUtils.hasText(parseFailure.getMessage()) ? parseFailure.getMessage() : "invalid JSON response";
            boolean isQualityFailure = parseMessage.contains("quality check failed");
            BookAiGenerationException.ErrorCode errorCode = isQualityFailure
                ? BookAiGenerationException.ErrorCode.DEGENERATE_CONTENT
                : BookAiGenerationException.ErrorCode.INVALID_RESPONSE;
            throw new BookAiGenerationException(errorCode,
                "AI content generation failed (%s): %s".formatted(configuredModel, parseMessage), parseFailure);
        }
        throwIfGenerationCancelled(generationControl);
        BookAiContentSnapshot snapshot = generationControl.persistIfActive(
            () -> repository.insertNewCurrentVersion(bookId, aiContent, configuredModel, DEFAULT_PROVIDER, promptHash)
        );
        throwIfGenerationCancelled(generationControl);
        try {
            onValidatedBufferedPayload.accept(validatedBufferedPayload);
        } catch (IllegalStateException deliveryFailure) {
            log.warn(
                "Validated AI content delivery failed after persistence for bookId={} model={} tier={} version={}",
                bookId,
                configuredModel,
                tier.headerValue(),
                snapshot.version(),
                deliveryFailure
            );
            throw deliveryFailure;
        }
        return new GeneratedContent(validatedBufferedPayload, snapshot);
    }

    private static void requireGenerationControl(GenerationControl generationControl) {
        if (generationControl == null) {
            throw new IllegalArgumentException("generationControl is required");
        }
    }

    private static void throwIfGenerationCancelled(GenerationControl generationControl) {
        if (generationControl.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("AI content generation cancelled");
        }
    }

    /**
     * One-generation state machine that gives either cancellation or persistence an atomic claim.
     * Persistence that claims first is allowed to finish; cancellation that claims first prevents it.
     */
    public static final class GenerationControl {
        private final AtomicReference<GenerationState> state = new AtomicReference<>(GenerationState.ACTIVE);

        /**
         * Claims cancellation while persistence has not started.
         *
         * @return true when cancellation won and the running queue task should be interrupted
         */
        public boolean cancel() {
            return state.compareAndSet(GenerationState.ACTIVE, GenerationState.CANCELLED);
        }

        private boolean isCancellationRequested() {
            return state.get() == GenerationState.CANCELLED;
        }

        <T> T persistIfActive(Supplier<T> persistence) {
            if (!state.compareAndSet(GenerationState.ACTIVE, GenerationState.PERSISTING)) {
                throw new CancellationException("AI content generation cancelled before persistence");
            }
            try {
                return persistence.get();
            } finally {
                state.compareAndSet(GenerationState.PERSISTING, GenerationState.COMPLETED);
            }
        }

        private enum GenerationState {
            ACTIVE,
            CANCELLED,
            PERSISTING,
            COMPLETED
        }
    }

    private boolean isRetryableGenerationFailure(
        BookAiGenerationException generationFailure,
        LlmGatewayTier tier
    ) {
        return switch (generationFailure.errorCode()) {
            case DEGENERATE_CONTENT, INCOMPLETE_RESPONSE, INVALID_RESPONSE -> true;
            case GENERATION_FAILED -> tier == LlmGatewayTier.LIVE_RENDER
                && isRetryableOpenAiFailure(generationFailure.getCause());
            case DESCRIPTION_TOO_SHORT, ENRICHMENT_FAILED -> false;
        };
    }

    private boolean isRetryableOpenAiFailure(Throwable failure) {
        if (failure instanceof OpenAIIoException || failure instanceof OpenAIRetryableException) {
            return true;
        }
        if (failure instanceof OpenAIServiceException serviceFailure) {
            int statusCode = serviceFailure.statusCode();
            return statusCode == 408
                || statusCode == 409
                || statusCode == 429
                || (statusCode >= 500 && statusCode <= 599);
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
        if (!available || clientsByTier.isEmpty()) {
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

    /** Immutable result containing the complete validated buffered payload and its persisted snapshot. */
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
