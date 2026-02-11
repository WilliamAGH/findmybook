///usr/bin/env java --enable-preview --source 25 "$0" "$@"; exit $?

// Backfill AI summary content + SEO metadata for an existing book.
//
// Usage:
//   CP=$(./gradlew -q --no-configuration-cache --init-script /tmp/print-cp.gradle printCp 2>/dev/null | tr -d '\n')
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillBookAiSeoMetadata.java <bookIdentifier>
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillBookAiSeoMetadata.java <bookIdentifier> --force
//
// Notes:
// - <bookIdentifier> can be UUID, slug, or ISBN.
// - Default mode generates only when prompt hash has changed.
// - --force always regenerates AI summary + SEO metadata versions.

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.FindmybookApplication;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.application.seo.BookSeoMetadataGenerationService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

static final int SEO_MAX_ATTEMPTS = 3;
static final long SEO_RETRY_BASE_DELAY_MILLIS = 1500L;

void main(String[] args) {
    if (args.length < 1 || args[0].isBlank()) {
        printUsageAndExit();
    }

    String identifier = args[0].trim();
    boolean force = Arrays.stream(args).anyMatch("--force"::equals);

    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(FindmybookApplication.class)
        .web(WebApplicationType.NONE)
        .logStartupInfo(false)
        .run("--spring.main.banner-mode=off")) {

        BookAiContentService aiContentService = context.getBean(BookAiContentService.class);
        BookSeoMetadataGenerationService seoMetadataGenerationService =
            context.getBean(BookSeoMetadataGenerationService.class);

        UUID bookId = resolveBookIdOrExit(aiContentService, identifier);
        System.out.printf("Resolved identifier '%s' to bookId=%s%n", identifier, bookId);

        runAiContentBackfill(aiContentService, bookId, force);
        runSeoMetadataBackfill(seoMetadataGenerationService, bookId, force);

        System.out.println("Backfill complete.");
    } catch (RuntimeException runtimeException) {
        System.err.printf("Backfill failed: %s%n", runtimeException.getMessage());
        runtimeException.printStackTrace(System.err);
        System.exit(1);
    }
}

UUID resolveBookIdOrExit(BookAiContentService aiContentService, String identifier) {
    Optional<UUID> resolvedBookId = aiContentService.resolveBookId(identifier);
    if (resolvedBookId.isEmpty()) {
        System.err.printf("Could not resolve identifier '%s' to a canonical book UUID.%n", identifier);
        System.exit(1);
    }
    return resolvedBookId.get();
}

void runAiContentBackfill(BookAiContentService aiContentService, UUID bookId, boolean force) {
    if (!aiContentService.isAvailable()) {
        System.out.println("AI summary generation skipped: BookAiContentService is not configured.");
        return;
    }

    if (force) {
        BookAiContentService.GeneratedContent result = aiContentService.generateAndPersist(bookId, ignoredDelta -> {
        });
        System.out.printf(
            "AI summary generated (force=true): version=%d model=%s provider=%s%n",
            result.snapshot().version(),
            result.snapshot().model(),
            result.snapshot().provider()
        );
        return;
    }

    BookAiContentService.GenerationOutcome outcome = aiContentService.generateAndPersistIfPromptChanged(
        bookId,
        ignoredDelta -> {
        }
    );
    if (outcome.generated()) {
        int version = outcome.snapshot() != null ? outcome.snapshot().version() : -1;
        System.out.printf("AI summary generated: version=%d promptHash=%s%n", version, outcome.promptHash());
    } else {
        System.out.printf("AI summary skipped: prompt hash unchanged (%s)%n", outcome.promptHash());
    }
}

void runSeoMetadataBackfill(BookSeoMetadataGenerationService seoMetadataGenerationService, UUID bookId, boolean force) {
    if (!seoMetadataGenerationService.isAvailable()) {
        System.out.println("SEO metadata generation skipped: BookSeoMetadataGenerationService is not configured.");
        return;
    }

    BookSeoMetadataGenerationService.GenerationOutcome outcome = generateSeoOutcomeWithRetries(
        seoMetadataGenerationService,
        bookId,
        force
    );

    if (outcome.generated()) {
        int version = outcome.snapshot() != null ? outcome.snapshot().version() : -1;
        System.out.printf("SEO metadata generated: version=%d promptHash=%s%n", version, outcome.promptHash());
    } else {
        System.out.printf("SEO metadata skipped: prompt hash unchanged (%s)%n", outcome.promptHash());
    }
}

BookSeoMetadataGenerationService.GenerationOutcome generateSeoOutcomeWithRetries(
    BookSeoMetadataGenerationService seoMetadataGenerationService,
    UUID bookId,
    boolean force
) {
    int attempt = 1;
    while (attempt <= SEO_MAX_ATTEMPTS) {
        try {
            return force
                ? seoMetadataGenerationService.generateAndPersist(bookId)
                : seoMetadataGenerationService.generateAndPersistIfPromptChanged(bookId);
        } catch (IllegalStateException exception) {
            boolean retryableFailure = isRetryableSeoFailure(exception);
            if (!retryableFailure || attempt == SEO_MAX_ATTEMPTS) {
                throw exception;
            }
            System.err.printf(
                "SEO metadata generation attempt %d/%d failed for bookId=%s: %s%n",
                attempt,
                SEO_MAX_ATTEMPTS,
                bookId,
                exception.getMessage()
            );
            sleepBeforeRetry(attempt);
            attempt++;
        }
    }
    throw new IllegalStateException("SEO metadata generation exhausted retries unexpectedly");
}

boolean isRetryableSeoFailure(IllegalStateException exception) {
    if (exception.getCause() != null) {
        return true;
    }
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
        return false;
    }
    return message.contains("response was empty")
        || message.contains("did not include a valid JSON object")
        || message.contains("JSON parsing failed")
        || message.contains("generation failed");
}

void sleepBeforeRetry(int attempt) {
    long delayMillis = SEO_RETRY_BASE_DELAY_MILLIS * Math.max(1, attempt);
    try {
        Thread.sleep(delayMillis);
    } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
    }
}

void printUsageAndExit() {
    System.err.println("Usage: BackfillBookAiSeoMetadata.java <bookIdentifier> [--force]");
    System.err.println("  <bookIdentifier>: UUID, slug, or ISBN");
    System.exit(1);
}
