///usr/bin/env java --enable-preview --source 25 "$0" "$@"; exit $?

// Backfill AI summary content + SEO metadata for existing books.
//
// Usage:
//   ./gradlew -q compileJava
//   CP="$(./gradlew -q --no-configuration-cache --init-script /tmp/print-cp.gradle printCp):$(pwd)/build/classes/java/main:$(pwd)/src/main/resources"
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillBookAiSeoMetadata.java <bookIdentifier>
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillBookAiSeoMetadata.java --id-file=/tmp/book-ids.txt
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillBookAiSeoMetadata.java <bookIdentifier> --force
//   java --enable-preview --source 25 -cp "$CP" scripts/BackfillBookAiSeoMetadata.java <bookIdentifier> --model=<model> --base-url=<url> --api-key=<key>
//
// Notes:
// - <bookIdentifier> can be UUID, slug, or ISBN.
// - Default mode generates only when prompt hash has changed.
// - --force always regenerates AI summary + SEO metadata versions.
// - --id-file accepts newline-delimited identifiers.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.FindmybookApplication;
import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.application.seo.BookSeoMetadataGenerationService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.StringUtils;

static final String OPENAI_BASE_URL_PROPERTY = "AI_DEFAULT_OPENAI_BASE_URL";
static final String OPENAI_MODEL_PROPERTY = "AI_DEFAULT_LLM_MODEL";
static final String OPENAI_SEO_MODEL_PROPERTY = "AI_DEFAULT_SEO_LLM_MODEL";
static final String OPENAI_API_KEY_PROPERTY = "AI_DEFAULT_OPENAI_API_KEY";

void main(String[] args) {
    if (args.length < 1) {
        printUsageAndExit();
    }

    ParsedOptions options = parseOptions(args);
    applyRuntimeOverrides(options);

    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(FindmybookApplication.class)
        .web(WebApplicationType.NONE)
        .logStartupInfo(false)
        .run("--spring.main.banner-mode=off")) {

        BookAiContentService aiContentService = context.getBean(BookAiContentService.class);
        BookSeoMetadataGenerationService seoMetadataGenerationService =
            context.getBean(BookSeoMetadataGenerationService.class);
        List<String> identifiers = resolveIdentifiers(options);
        if (identifiers.isEmpty()) {
            System.out.println("No identifiers were provided for backfill.");
            return;
        }

        int failures = 0;
        int skipped = 0;
        int processed = 0;
        for (String identifier : identifiers) {
            processed += 1;
            UUID bookId;
            try {
                System.out.printf("Backfilling %s (%d)...%n", identifier, processed);
                bookId = resolveBookId(aiContentService, identifier);
                System.out.printf("Resolved identifier '%s' to bookId=%s%n", identifier, bookId);
            } catch (IllegalArgumentException resolveFailure) {
                failures += 1;
                System.err.printf("Backfill failed for identifier '%s': %s%n", identifier, resolveFailure.getMessage());
                continue;
            }
            try {
                runAiContentBackfill(aiContentService, bookId, options.force());
            } catch (BookAiGenerationException generationFailure) {
                if (generationFailure.errorCode() == BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT) {
                    skipped += 1;
                    System.out.printf("AI summary skipped (description too short): bookId=%s%n", bookId);
                } else {
                    failures += 1;
                    System.err.printf("AI backfill failed for bookId=%s: %s%n", bookId, generationFailure.getMessage());
                    generationFailure.printStackTrace(System.err);
                }
            } catch (RuntimeException aiFailure) {
                failures += 1;
                System.err.printf("AI backfill failed for bookId=%s: %s%n", bookId, aiFailure.getMessage());
                aiFailure.printStackTrace(System.err);
            }
            try {
                runSeoMetadataBackfill(seoMetadataGenerationService, bookId, options.force());
            } catch (RuntimeException seoFailure) {
                failures += 1;
                System.err.printf("SEO backfill failed for bookId=%s: %s%n", bookId, seoFailure.getMessage());
                seoFailure.printStackTrace(System.err);
            }
        }
        System.out.printf("Backfill complete. processed=%d skipped=%d failures=%d%n", processed, skipped, failures);
        if (failures > 0) {
            System.exit(1);
        }
    } catch (RuntimeException runtimeException) {
        System.err.printf("Backfill failed: %s%n", runtimeException.getMessage());
        System.exit(1);
    }
}

List<String> resolveIdentifiers(ParsedOptions options) {
    if (StringUtils.hasText(options.identifier())) {
        return List.of(options.identifier().trim());
    }
    if (!StringUtils.hasText(options.idFilePath())) {
        return List.of();
    }
    Path path = Path.of(options.idFilePath().trim());
    try {
        return Files.readAllLines(path).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
    } catch (IOException ioException) {
        throw new IllegalStateException("Could not read id file: " + path, ioException);
    }
}

UUID resolveBookId(BookAiContentService aiContentService, String identifier) {
    Optional<UUID> resolvedBookId = aiContentService.resolveBookId(identifier);
    if (resolvedBookId.isEmpty()) {
        throw new IllegalArgumentException("Could not resolve identifier '%s' to a canonical book UUID.".formatted(identifier));
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
        int version = outcome.snapshot().map(s -> s.version())
            .orElseThrow(() -> new IllegalStateException("Generated outcome missing snapshot for bookId=" + bookId));
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
        int version = outcome.snapshot().map(s -> s.version())
            .orElseThrow(() -> new IllegalStateException("Generated outcome missing snapshot for bookId=" + bookId));
        System.out.printf("SEO metadata generated: version=%d promptHash=%s%n", version, outcome.promptHash());
    } else {
        System.out.printf("SEO metadata skipped: prompt hash unchanged (%s)%n", outcome.promptHash());
    }
}

ParsedOptions parseOptions(String[] args) {
    String identifier = null;
    String idFilePath = null;
    boolean force = Arrays.stream(args).anyMatch("--force"::equals);
    String baseUrl = null;
    String model = null;
    String apiKey = null;

    for (String rawArg : args) {
        String arg = rawArg.trim();
        if (!arg.startsWith("--")) {
            if (identifier != null) {
                System.err.println("Only one positional identifier is allowed.");
                printUsageAndExit();
            }
            identifier = firstArg(arg);
            continue;
        }
        if (arg.equals("--force")) {
            continue;
        }
        if (arg.startsWith("--id-file=")) {
            idFilePath = requiredOptionValue(arg, "--id-file");
            continue;
        }
        if (arg.startsWith("--base-url=")) {
            baseUrl = requiredOptionValue(arg, "--base-url");
            continue;
        }
        if (arg.startsWith("--model=")) {
            model = requiredOptionValue(arg, "--model");
            continue;
        }
        if (arg.startsWith("--api-key=")) {
            apiKey = requiredOptionValue(arg, "--api-key");
            continue;
        }
        System.err.printf("Unknown option: %s%n", arg);
        printUsageAndExit();
    }
    if (!StringUtils.hasText(identifier) && !StringUtils.hasText(idFilePath)) {
        System.err.println("Provide either a positional <bookIdentifier> or --id-file=<path>.");
        printUsageAndExit();
    }
    if (StringUtils.hasText(identifier) && StringUtils.hasText(idFilePath)) {
        System.err.println("Use either positional <bookIdentifier> or --id-file=<path>, not both.");
        printUsageAndExit();
    }
    return new ParsedOptions(identifier, idFilePath, force, baseUrl, model, apiKey);
}

String firstArg(String value) {
    String first = value == null ? "" : value.trim();
    if (first.isBlank()) {
        printUsageAndExit();
    }
    return first;
}

String requiredOptionValue(String rawOption, String optionName) {
    int equalsIndex = rawOption.indexOf('=');
    if (equalsIndex < 0 || equalsIndex == rawOption.length() - 1) {
        System.err.printf("Option %s requires a non-empty value.%n", optionName);
        printUsageAndExit();
    }
    String value = rawOption.substring(equalsIndex + 1).trim();
    if (!StringUtils.hasText(value)) {
        System.err.printf("Option %s requires a non-empty value.%n", optionName);
        printUsageAndExit();
    }
    return value;
}

void applyRuntimeOverrides(ParsedOptions options) {
    if (StringUtils.hasText(options.baseUrl())) {
        System.setProperty(OPENAI_BASE_URL_PROPERTY, options.baseUrl().trim());
        System.out.printf("Applied runtime override %s=%s%n", OPENAI_BASE_URL_PROPERTY, options.baseUrl().trim());
    }

    if (StringUtils.hasText(options.model())) {
        String model = options.model().trim();
        System.setProperty(OPENAI_MODEL_PROPERTY, model);
        System.setProperty(OPENAI_SEO_MODEL_PROPERTY, model);
        System.out.printf("Applied runtime override %s=%s%n", OPENAI_MODEL_PROPERTY, model);
        System.out.printf("Applied runtime override %s=%s%n", OPENAI_SEO_MODEL_PROPERTY, model);
    }

    if (StringUtils.hasText(options.apiKey())) {
        String apiKey = options.apiKey().trim();
        System.setProperty(OPENAI_API_KEY_PROPERTY, apiKey);
        System.out.printf(
            "Applied runtime override %s=%s%n",
            OPENAI_API_KEY_PROPERTY,
            redactSecret(apiKey)
        );
    }
}

String redactSecret(String value) {
    if (!StringUtils.hasText(value) || value.length() <= 8) {
        return "****";
    }
    return value.substring(0, 4) + "...(redacted)..."
        + value.substring(value.length() - 4);
}

void printUsageAndExit() {
    System.err.println(
        "Usage: BackfillBookAiSeoMetadata.java (<bookIdentifier> | --id-file=<path>) [--force] [--base-url=<url>] [--model=<model>] [--api-key=<key>]"
    );
    System.err.println("  <bookIdentifier>: UUID, slug, or ISBN");
    System.err.println("  --id-file: newline-delimited identifiers (UUID, slug, or ISBN)");
    System.exit(1);
}

record ParsedOptions(String identifier, String idFilePath, boolean force, String baseUrl, String model, String apiKey) {}
