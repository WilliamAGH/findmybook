package net.findmybook.boot.scheduler;

import net.findmybook.application.similarity.BookSimilarityEmbeddingService;
import net.findmybook.boot.BookSimilarityEmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lightweight catch-up scheduler for missing or stale book similarity embeddings.
 */
@Component
public class BookSimilarityEmbeddingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookSimilarityEmbeddingScheduler.class);

    private final BookSimilarityEmbeddingService embeddingService;
    private final BookSimilarityEmbeddingProperties properties;

    public BookSimilarityEmbeddingScheduler(BookSimilarityEmbeddingService embeddingService,
                                            BookSimilarityEmbeddingProperties properties) {
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    /**
     * Enqueues a small bounded batch of missing or stale vectors.
     */
    @Scheduled(
        fixedDelayString = "${app.similarity.embeddings.fixed-delay-ms:60000}",
        initialDelayString = "${app.similarity.embeddings.initial-delay-ms:30000}"
    )
    public void enqueueMissingOrStaleEmbeddings() {
        if (!properties.isEnabled()) {
            return;
        }
        int enqueued = embeddingService.enqueueRefreshCandidates(
            properties.refreshBatchSize(),
            properties.schedulerEnqueueLimit(),
            properties.schedulerMaxPending()
        );
        if (enqueued > 0) {
            log.info("Queued {} book similarity embedding refresh task(s).", enqueued);
        }
    }
}
