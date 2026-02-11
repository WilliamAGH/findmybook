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

    BookSeoMetadataGenerationService.GenerationOutcome outcome = force
        ? seoMetadataGenerationService.generateAndPersist(bookId)
        : seoMetadataGenerationService.generateAndPersistIfPromptChanged(bookId);

    if (outcome.generated()) {
        int version = outcome.snapshot() != null ? outcome.snapshot().version() : -1;
        System.out.printf("SEO metadata generated: version=%d promptHash=%s%n", version, outcome.promptHash());
    } else {
        System.out.printf("SEO metadata skipped: prompt hash unchanged (%s)%n", outcome.promptHash());
    }
}

void printUsageAndExit() {
    System.err.println("Usage: BackfillBookAiSeoMetadata.java <bookIdentifier> [--force]");
    System.err.println("  <bookIdentifier>: UUID, slug, or ISBN");
    System.exit(1);
}
