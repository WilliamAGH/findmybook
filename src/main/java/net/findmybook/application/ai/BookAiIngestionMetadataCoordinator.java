package net.findmybook.application.ai;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.findmybook.application.seo.BookSeoMetadataGenerationService;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.ai.BookAiQueueCapacityExceededException;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Enqueues AI summary and SEO metadata generation after canonical book upserts.
 *
 * <p>Uses the central AI request queue so ingestion work runs behind foreground
 * Svelte-triggered requests while still allowing continuous background processing.</p>
 */
@Component
public class BookAiIngestionMetadataCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BookAiIngestionMetadataCoordinator.class);
    private static final int BACKGROUND_INGESTION_PRIORITY = 0;

    private final BookAiContentRequestQueue requestQueue;
    private final BookAiContentService bookAiContentService;
    private final BookSeoMetadataGenerationService bookSeoMetadataGenerationService;
    private final AtomicBoolean seoGenerationEnabled;

    public BookAiIngestionMetadataCoordinator(BookAiContentRequestQueue requestQueue,
                                              BookAiContentService bookAiContentService,
                                              BookSeoMetadataGenerationService bookSeoMetadataGenerationService) {
        this.requestQueue = requestQueue;
        this.bookAiContentService = bookAiContentService;
        this.bookSeoMetadataGenerationService = bookSeoMetadataGenerationService;
        this.seoGenerationEnabled = new AtomicBoolean(true);
    }

    /**
     * Enqueues background metadata generation when a canonical book upsert is published.
     */
    @EventListener
    public void handleBookUpsert(BookUpsertEvent event) {
        if (event == null || !StringUtils.hasText(event.getBookId())) {
            log.warn("Skipping ingestion metadata enqueue: missing bookId in BookUpsertEvent");
            return;
        }
        boolean aiAvailable = bookAiContentService.isAvailable();
        boolean seoAvailable = bookSeoMetadataGenerationService.isAvailable() && seoGenerationEnabled.get();
        if (!aiAvailable && !seoAvailable) {
            return;
        }

        UUID bookId = parseBookId(event.getBookId());
        if (bookId == null) {
            return;
        }

        try {
            requestQueue.enqueueBackground(BACKGROUND_INGESTION_PRIORITY, () -> {
                processIngestionMetadata(bookId);
                return null;
            }).result().whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    log.error("Background ingestion metadata generation failed for book {}", bookId, throwable);
                }
            });
        } catch (BookAiQueueCapacityExceededException queueOverflowException) {
            log.warn(
                "Background ingestion metadata enqueue skipped for book {} because queue cap was reached (pending={}, max={})",
                bookId,
                queueOverflowException.currentPending(),
                queueOverflowException.maxPending()
            );
        }
    }

    private void processIngestionMetadata(UUID bookId) {
        RuntimeException firstFailure = null;
        if (bookAiContentService.isAvailable()) {
            try {
                BookAiContentService.GenerationOutcome aiOutcome =
                    bookAiContentService.generateAndPersistIfPromptChanged(bookId, ignoredDelta -> {
                    });
                if (aiOutcome.generated()) {
                    log.info("Generated ingestion AI summary for book {}", bookId);
                } else {
                    log.debug("Skipped ingestion AI summary for unchanged prompt hash book {}", bookId);
                }
            } catch (BookAiGenerationException | IllegalStateException aiFailure) {
                log.error("Failed generating ingestion AI summary for book {}", bookId, aiFailure);
                firstFailure = aiFailure;
            }
        }

        if (bookSeoMetadataGenerationService.isAvailable() && seoGenerationEnabled.get()) {
            try {
                BookSeoMetadataGenerationService.GenerationOutcome seoOutcome =
                    bookSeoMetadataGenerationService.generateAndPersistIfPromptChanged(bookId);
                if (seoOutcome.generated()) {
                    log.info("Generated ingestion SEO metadata for book {}", bookId);
                } else {
                    log.debug("Skipped ingestion SEO metadata for unchanged prompt hash book {}", bookId);
                }
            } catch (IllegalStateException | DataAccessException seoFailure) {
                if (isMissingSeoMetadataRelation(seoFailure)) {
                    if (seoGenerationEnabled.compareAndSet(true, false)) {
                        log.error(
                            "Disabling ingestion SEO metadata generation because relation book_seo_metadata is missing. "
                                + "Apply migration 47_book_seo_metadata.sql and restart to re-enable.",
                            seoFailure
                        );
                    } else {
                        log.debug("Skipping ingestion SEO metadata generation because relation book_seo_metadata is unavailable.");
                    }
                } else {
                    log.error("Failed generating ingestion SEO metadata for book {}", bookId, seoFailure);
                    if (firstFailure == null) {
                        firstFailure = seoFailure;
                    }
                }
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private UUID parseBookId(String rawBookId) {
        try {
            return UUID.fromString(rawBookId);
        } catch (IllegalArgumentException invalidBookId) {
            log.warn("Skipping ingestion metadata enqueue for non-UUID bookId '{}'", rawBookId, invalidBookId);
            return null;
        }
    }

    private boolean isMissingSeoMetadataRelation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("relation \"book_seo_metadata\" does not exist")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
