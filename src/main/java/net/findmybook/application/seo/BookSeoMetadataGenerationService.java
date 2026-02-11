package net.findmybook.application.seo;

import java.util.Optional;
import java.util.UUID;
import net.findmybook.adapters.persistence.BookSeoMetadataRepository;
import net.findmybook.domain.seo.BookSeoMetadataSnapshot;
import net.findmybook.dto.BookDetail;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Generates and persists versioned SEO metadata for book detail pages.
 */
@Service
public class BookSeoMetadataGenerationService {

    private static final Logger log = LoggerFactory.getLogger(BookSeoMetadataGenerationService.class);
    private static final String FALLBACK_PROVIDER = "deterministic-fallback";
    private static final int MIN_DESCRIPTION_LENGTH = 50;

    private final BookSeoMetadataRepository repository;
    private final BookSearchService bookSearchService;
    private final BookDataOrchestrator bookDataOrchestrator;
    private final BookSeoMetadataClient seoMetadataClient;
    private final SeoMetadataNormalizationPolicy normalizationPolicy;

    /**
     * Creates the SEO generation service.
     */
    public BookSeoMetadataGenerationService(
        BookSeoMetadataRepository repository,
        BookSearchService bookSearchService,
        BookDataOrchestrator bookDataOrchestrator,
        BookSeoMetadataClient seoMetadataClient,
        SeoMetadataNormalizationPolicy normalizationPolicy
    ) {
        this.repository = repository;
        this.bookSearchService = bookSearchService;
        this.bookDataOrchestrator = bookDataOrchestrator;
        this.seoMetadataClient = seoMetadataClient;
        this.normalizationPolicy = normalizationPolicy;
    }

    /**
     * Indicates whether SEO generation is currently configured.
     */
    public boolean isAvailable() {
        return seoMetadataClient.isAvailable();
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
     * @return generation outcome details; snapshot is empty when skipped
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
        try {
            SeoMetadataCandidate generatedMetadata = seoMetadataClient.generate(bookId, prompt);
            String normalizedTitle = normalizationPolicy.normalizeSeoTitle(generatedMetadata.seoTitle(), promptContext.bookTitle());
            String normalizedDescription = normalizationPolicy.normalizeSeoDescription(
                generatedMetadata.seoDescription(),
                promptContext.description()
            );
            return repository.insertNewCurrentVersion(
                bookId,
                normalizedTitle,
                normalizedDescription,
                seoMetadataClient.configuredModel(),
                seoMetadataClient.provider(),
                promptHash
            );
        } catch (IllegalStateException generationFailure) {
            String fallbackTitle = normalizationPolicy.buildDeterministicTitle(promptContext.bookTitle());
            String fallbackDescription = normalizationPolicy.buildDeterministicDescription(promptContext.description());
            log.error(
                "Book SEO metadata generation failed for bookId={} model={}: {}; persisting deterministic fallback metadata",
                bookId,
                seoMetadataClient.configuredModel(),
                generationFailure.getMessage(),
                generationFailure
            );
            return repository.insertNewCurrentVersion(
                bookId,
                fallbackTitle,
                fallbackDescription,
                seoMetadataClient.configuredModel(),
                FALLBACK_PROVIDER,
                promptHash
            );
        }
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
        if (!seoMetadataClient.isAvailable()) {
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
                                    Optional<BookSeoMetadataSnapshot> snapshot) {
        private static GenerationOutcome skipped(UUID bookId, String promptHash) {
            return new GenerationOutcome(bookId, false, promptHash, Optional.empty());
        }

        private static GenerationOutcome generated(UUID bookId, String promptHash, BookSeoMetadataSnapshot snapshot) {
            return new GenerationOutcome(bookId, true, promptHash, Optional.of(snapshot));
        }
    }

    private record PromptContext(String bookTitle,
                                 String authors,
                                 String publisher,
                                 String publishedDate,
                                 String description) {
    }
}
