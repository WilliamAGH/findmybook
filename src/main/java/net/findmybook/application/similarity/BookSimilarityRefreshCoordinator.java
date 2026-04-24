package net.findmybook.application.similarity;

import java.util.UUID;
import net.findmybook.service.event.BookUpsertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Enqueues embedding refreshes when canonical book data changes.
 */
@Component
public class BookSimilarityRefreshCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BookSimilarityRefreshCoordinator.class);

    private final BookSimilarityEmbeddingService embeddingService;

    public BookSimilarityRefreshCoordinator(BookSimilarityEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * Handles canonical book upsert notifications with an idempotent refresh enqueue.
     *
     * @param event book upsert event
     */
    @EventListener
    public void handleBookUpsert(BookUpsertEvent event) {
        if (event == null || event.getBookId() == null || event.getBookId().isBlank()) {
            log.warn("Skipping similarity embedding refresh enqueue: missing bookId in BookUpsertEvent");
            return;
        }
        UUID bookId = parseBookId(event.getBookId());
        if (bookId == null) {
            return;
        }
        embeddingService.enqueueDemandRefresh(bookId);
    }

    private UUID parseBookId(String rawBookId) {
        try {
            return UUID.fromString(rawBookId);
        } catch (IllegalArgumentException invalidBookId) {
            log.warn("Skipping similarity embedding refresh enqueue for non-UUID bookId '{}'", rawBookId, invalidBookId);
            return null;
        }
    }
}
