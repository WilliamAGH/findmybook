package net.findmybook.boot;

import net.findmybook.application.similarity.BookSimilarityEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Boot-time runner that drives similarity embedding backfill through the canonical service.
 *
 * <p>Enables the retired {@code scripts/BackfillBookSimilarityEmbeddings.java} script to be
 * replaced by a standard application run, keeping source-document rendering and hashing
 * logic confined to {@link BookSimilarityEmbeddingService} instead of duplicating it in a
 * standalone tool. Activation requires {@code app.similarity.embeddings.backfill=true} so
 * regular application starts never touch the backfill path.</p>
 */
@Component
@ConditionalOnProperty(name = "app.similarity.embeddings.backfill", havingValue = "true")
public class BookSimilarityBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BookSimilarityBackfillRunner.class);
    private static final String LIMIT_OPTION = "app.similarity.embeddings.backfill-limit";
    private static final int DEFAULT_LIMIT = 120;

    private final BookSimilarityEmbeddingService embeddingService;
    private final ConfigurableApplicationContext applicationContext;

    public BookSimilarityBackfillRunner(BookSimilarityEmbeddingService embeddingService,
                                        ConfigurableApplicationContext applicationContext) {
        this.embeddingService = embeddingService;
        this.applicationContext = applicationContext;
    }

    /**
     * Resolves the candidate limit from CLI arguments, runs one backfill pass, and shuts down.
     *
     * @param args Spring Boot application arguments
     */
    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            int limit = resolveLimit(args);
            log.info("Starting book similarity backfill (limit={})", limit);
            int refreshed = embeddingService.backfillStale(limit);
            log.info("Book similarity backfill finished: {} vectors refreshed.", refreshed);
        } catch (RuntimeException backfillFailure) {
            log.error("Book similarity backfill failed", backfillFailure);
            exitCode = 1;
        } finally {
            int finalExit = exitCode;
            System.exit(SpringApplication.exit(applicationContext, () -> finalExit));
        }
    }

    private int resolveLimit(ApplicationArguments args) {
        if (!args.containsOption(LIMIT_OPTION)) {
            return DEFAULT_LIMIT;
        }
        String rawLimit = args.getOptionValues(LIMIT_OPTION).getFirst();
        int parsed = Integer.parseInt(rawLimit.trim());
        if (parsed < 1) {
            throw new IllegalArgumentException("Backfill limit must be >= 1 but was " + parsed);
        }
        return parsed;
    }
}
