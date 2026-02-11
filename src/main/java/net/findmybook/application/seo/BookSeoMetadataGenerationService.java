package net.findmybook.application.seo;

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
import java.util.regex.Pattern;
import net.findmybook.adapters.persistence.BookSeoMetadataRepository;
import net.findmybook.domain.seo.BookSeoMetadataSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.HashUtils;
import net.findmybook.util.SeoUtils;
import net.findmybook.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates and persists versioned SEO metadata for book detail pages.
 */
@Service
public class BookSeoMetadataGenerationService {

    private static final Logger log = LoggerFactory.getLogger(BookSeoMetadataGenerationService.class);
    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_MODEL = "gpt-5-mini";
    private static final long MAX_COMPLETION_TOKENS = 220L;
    private static final int MIN_DESCRIPTION_LENGTH = 50;
    private static final int TARGET_TITLE_LENGTH = 60;
    private static final int TARGET_DESCRIPTION_LENGTH = 160;
    private static final String API_KEY_SENTINEL = "not-configured";
    private static final String TITLE_SUFFIX = " - Book Details | findmybook.net";
    private static final Pattern EXPECTED_TITLE_PATTERN =
        Pattern.compile("^.+ - Book Details \\| findmybook\\.net$", Pattern.CASE_INSENSITIVE);

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

    private final BookSeoMetadataRepository repository;
    private final BookSearchService bookSearchService;
    private final BookDataOrchestrator bookDataOrchestrator;
    private final SeoMetadataJsonParser parser;
    private final OpenAIClient openAiClient;
    private final boolean available;
    private final String configuredModel;
    private final long requestTimeoutSeconds;
    private final long readTimeoutSeconds;

    /**
     * Creates the SEO generation service.
     */
    public BookSeoMetadataGenerationService(
        BookSeoMetadataRepository repository,
        BookSearchService bookSearchService,
        BookDataOrchestrator bookDataOrchestrator,
        ObjectMapper objectMapper,
        @Value("${AI_DEFAULT_OPENAI_API_KEY:${OPENAI_API_KEY:}}") String apiKey,
        @Value("${AI_DEFAULT_OPENAI_BASE_URL:${OPENAI_BASE_URL:https://api.openai.com/v1}}") String baseUrl,
        @Value("${AI_DEFAULT_SEO_LLM_MODEL:${AI_DEFAULT_LLM_MODEL:${OPENAI_MODEL:" + DEFAULT_MODEL + "}}}") String model,
        @Value("${AI_DEFAULT_OPENAI_REQUEST_TIMEOUT_SECONDS:120}") long requestTimeoutSeconds,
        @Value("${AI_DEFAULT_OPENAI_READ_TIMEOUT_SECONDS:75}") long readTimeoutSeconds
    ) {
        this.repository = repository;
        this.bookSearchService = bookSearchService;
        this.bookDataOrchestrator = bookDataOrchestrator;
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
            log.info("Book SEO metadata generation service configured (model={}, baseUrl={})", this.configuredModel, resolvedBaseUrl);
            return;
        }

        this.openAiClient = null;
        this.available = false;
        log.warn("Book SEO metadata generation service is disabled: no API key configured");
    }

    /**
     * Indicates whether SEO generation is currently configured.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Loads the current persisted SEO snapshot for a canonical book UUID.
     */
    public Optional<BookSeoMetadataSnapshot> findCurrent(UUID bookId) {
        return repository.fetchCurrent(bookId);
    }

    /**
     * Generates and persists SEO metadata even when prompt hash is unchanged.
     *
     * @param bookId canonical book UUID
     * @return generation outcome details
     */
    public GenerationOutcome generateAndPersist(UUID bookId) {
        ensureAvailable();
        PromptContext promptContext = loadPromptContext(bookId);
        String prompt = buildPrompt(promptContext);
        String promptHash = sha256(prompt);
        BookSeoMetadataSnapshot snapshot = generateAndPersistFromPrompt(bookId, promptContext, prompt, promptHash);
        return GenerationOutcome.generated(bookId, promptHash, snapshot);
    }

    /**
     * Generates and persists SEO metadata only when the current prompt hash has changed.
     *
     * @param bookId canonical book UUID
     * @return generation outcome details
     */
    public GenerationOutcome generateAndPersistIfPromptChanged(UUID bookId) {
        ensureAvailable();

        PromptContext promptContext = loadPromptContext(bookId);
        String prompt = buildPrompt(promptContext);
        String promptHash = sha256(prompt);
        Optional<String> existingPromptHash = repository.fetchCurrentPromptHash(bookId);
        if (existingPromptHash.isPresent() && existingPromptHash.get().equals(promptHash)) {
            return GenerationOutcome.skipped(bookId, promptHash);
        }
        BookSeoMetadataSnapshot snapshot = generateAndPersistFromPrompt(bookId, promptContext, prompt, promptHash);
        return GenerationOutcome.generated(bookId, promptHash, snapshot);
    }

    private BookSeoMetadataSnapshot generateAndPersistFromPrompt(UUID bookId,
                                                                 PromptContext promptContext,
                                                                 String prompt,
                                                                 String promptHash) {
        String rawResponse = streamAndCollectResponse(prompt);
        SeoMetadataJsonParser.ParsedSeoMetadata parsedMetadata = parser.parse(rawResponse);
        String normalizedTitle = normalizeSeoTitle(parsedMetadata.seoTitle(), promptContext.bookTitle());
        String normalizedDescription = normalizeSeoDescription(parsedMetadata.seoDescription(), promptContext.description());
        return repository.insertNewCurrentVersion(
            bookId,
            normalizedTitle,
            normalizedDescription,
            configuredModel,
            DEFAULT_PROVIDER,
            promptHash
        );
    }

    private String streamAndCollectResponse(String prompt) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(configuredModel))
            .messages(List.of(
                ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder().content(SYSTEM_PROMPT).build()),
                ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build())
            ))
            .maxCompletionTokens(MAX_COMPLETION_TOKENS)
            .temperature(0.2)
            .build();

        RequestOptions options = RequestOptions.builder()
            .timeout(Timeout.builder()
                .request(Duration.ofSeconds(requestTimeoutSeconds))
                .read(Duration.ofSeconds(readTimeoutSeconds))
                .build())
            .build();

        StringBuilder responseBuilder = new StringBuilder();
        try (StreamResponse<ChatCompletionChunk> stream = openAiClient.chat().completions().createStreaming(params, options)) {
            stream.stream().forEach(chunk -> {
                if (chunk.choices().isEmpty()) {
                    return;
                }
                String delta = chunk.choices().get(0).delta().content().orElse("");
                if (!delta.isEmpty()) {
                    responseBuilder.append(delta);
                }
            });
        } catch (OpenAIException openAiException) {
            log.error("Book SEO metadata generation failed (model={})", configuredModel, openAiException);
            throw new IllegalStateException("SEO metadata generation failed", openAiException);
        }
        return responseBuilder.toString();
    }

    private PromptContext loadPromptContext(UUID bookId) {
        BookDetail detail = bookSearchService.fetchBookDetail(bookId)
            .orElseThrow(() -> new IllegalStateException("Book details unavailable for SEO metadata generation: " + bookId));

        String description = detail.description() == null ? null : detail.description().trim();
        description = bookDataOrchestrator.enrichDescriptionForAiIfNeeded(bookId, detail, description, MIN_DESCRIPTION_LENGTH);
        if (!StringUtils.hasText(description)) {
            description = "No description available.";
        }

        String bookTitle = StringUtils.hasText(detail.title()) ? detail.title().trim() : "Unknown Title";
        String authors = detail.authors() == null || detail.authors().isEmpty()
            ? "Unknown author"
            : String.join(", ", detail.authors());
        String publisher = StringUtils.hasText(detail.publisher()) ? detail.publisher().trim() : "Unknown publisher";
        String publishedDate = detail.publishedDate() != null ? detail.publishedDate().toString() : "Unknown";

        return new PromptContext(bookTitle, authors, publisher, publishedDate, description);
    }

    private String normalizeSeoTitle(String candidateTitle, String fallbackBookTitle) {
        String fallbackTitle = buildDeterministicTitle(fallbackBookTitle);
        if (!StringUtils.hasText(candidateTitle)) {
            return fallbackTitle;
        }
        String normalized = candidateTitle.replaceAll("\\s+", " ").trim();
        if (!EXPECTED_TITLE_PATTERN.matcher(normalized).matches() || normalized.length() > TARGET_TITLE_LENGTH) {
            return fallbackTitle;
        }
        return normalized;
    }

    private String normalizeSeoDescription(String candidateDescription, String fallbackDescriptionSource) {
        String fallbackDescription = SeoUtils.truncateDescription(fallbackDescriptionSource, TARGET_DESCRIPTION_LENGTH);
        if (!StringUtils.hasText(candidateDescription)) {
            return fallbackDescription;
        }
        String plainDescription = candidateDescription
            .replaceAll("<[^>]*>", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (!StringUtils.hasText(plainDescription)) {
            return fallbackDescription;
        }
        return SeoUtils.truncateDescription(plainDescription, TARGET_DESCRIPTION_LENGTH);
    }

    private String buildDeterministicTitle(String rawBookTitle) {
        String title = StringUtils.hasText(rawBookTitle) ? rawBookTitle.trim() : "Book";
        int titleBudget = Math.max(10, TARGET_TITLE_LENGTH - TITLE_SUFFIX.length());
        String normalizedTitle = truncateForTitle(title, titleBudget);
        return normalizedTitle + TITLE_SUFFIX;
    }

    private String truncateForTitle(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        String shortened = value.substring(0, maxLength - 3);
        int lastSpace = shortened.lastIndexOf(' ');
        if (lastSpace > 5) {
            shortened = shortened.substring(0, lastSpace);
        }
        return shortened + "...";
    }

    private String buildPrompt(PromptContext context) {
        return """
            Title: %s
            Authors: %s
            Publisher: %s
            Published: %s
            Description:
            %s
            """.formatted(
            context.bookTitle(),
            context.authors(),
            context.publisher(),
            context.publishedDate(),
            context.description()
        );
    }

    private void ensureAvailable() {
        if (!available || openAiClient == null) {
            throw new IllegalStateException("SEO metadata generation service is not configured");
        }
    }

    private String sha256(String input) {
        try {
            return HashUtils.sha256Hex(input);
        } catch (java.security.NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 unavailable", noSuchAlgorithmException);
        }
    }

    /**
     * Immutable outcome for background SEO generation jobs.
     *
     * @param bookId canonical book UUID
     * @param generated whether generation executed and persisted a new version
     * @param promptHash prompt hash computed for the generation context
     * @param snapshot persisted snapshot when generated
     */
    public record GenerationOutcome(UUID bookId,
                                    boolean generated,
                                    String promptHash,
                                    BookSeoMetadataSnapshot snapshot) {
        private static GenerationOutcome skipped(UUID bookId, String promptHash) {
            return new GenerationOutcome(bookId, false, promptHash, null);
        }

        private static GenerationOutcome generated(UUID bookId, String promptHash, BookSeoMetadataSnapshot snapshot) {
            return new GenerationOutcome(bookId, true, promptHash, snapshot);
        }
    }

    private record PromptContext(String bookTitle,
                                 String authors,
                                 String publisher,
                                 String publishedDate,
                                 String description) {
    }
}
