package net.findmybook.application.ai;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.findmybook.application.seo.BookSeoGenerationException;
import net.findmybook.application.seo.BookSeoMetadataGenerationService;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.ai.BookAiQueueCapacityExceededException;
import net.findmybook.support.llm.LlmGatewayTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
    private final Set<UUID> activeBackgroundBookIds;
    private final AtomicBoolean seoGenerationEnabled;

    public BookAiIngestionMetadataCoordinator(BookAiContentRequestQueue requestQueue,
                                              BookAiContentService bookAiContentService,
                                              BookSeoMetadataGenerationService bookSeoMetadataGenerationService) {
        this.requestQueue = requestQueue;
        this.bookAiContentService = bookAiContentService;
        this.bookSeoMetadataGenerationService = bookSeoMetadataGenerationService;
        this.activeBackgroundBookIds = ConcurrentHashMap.newKeySet();
        this.seoGenerationEnabled = new AtomicBoolean(true);
    }

    /**
     * Enqueues background metadata generation after a transactional canonical book upsert commits.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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
        if (!activeBackgroundBookIds.add(bookId)) {
            log.debug(
                "Skipping ingestion metadata enqueue for book {} because background metadata generation is already pending or running",
                bookId
            );
            return;
        }

        try {
            BookAiContentRequestQueue.EnqueuedTask<Void> backgroundTask = requestQueue.enqueueBackground(
                BACKGROUND_INGESTION_PRIORITY,
                () -> {
                    processIngestionMetadata(bookId);
                    return null;
                }
            );
            backgroundTask.finished().whenComplete((ignored, throwable) -> activeBackgroundBookIds.remove(bookId));
            backgroundTask.result().whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    logBackgroundFailure(bookId, throwable);
                }
            });
        } catch (BookAiQueueCapacityExceededException queueOverflowException) {
            activeBackgroundBookIds.remove(bookId);
            log.warn(
                "Background ingestion metadata enqueue skipped for book {} because queue cap was reached (pending={}, max={})",
                bookId,
                queueOverflowException.currentPending(),
                queueOverflowException.maxPending()
            );
        } catch (RuntimeException enqueueFailure) {
            activeBackgroundBookIds.remove(bookId);
            throw enqueueFailure;
        }
    }

    private void logBackgroundFailure(UUID bookId, Throwable failure) {
        if (failure instanceof CancellationException) {
            log.debug("Background ingestion metadata generation cancelled for book {}", bookId);
            return;
        }
        if (isExpectedGenerationFailure(failure)) {
            log.warn("Background ingestion metadata generation did not complete for book {}: {}",
                bookId, failure.getMessage(), failure);
            return;
        }
        log.error("Background ingestion metadata generation failed for book {}", bookId, failure);
    }

    private boolean isExpectedGenerationFailure(Throwable failure) {
        if (failure instanceof BookAiGenerationException aiFailure) {
            return switch (aiFailure.errorCode()) {
                case INVALID_RESPONSE, INCOMPLETE_RESPONSE, DEGENERATE_CONTENT, DESCRIPTION_TOO_SHORT,
                    ENRICHMENT_FAILED -> true;
                case GENERATION_FAILED -> false;
            };
        }
        if (failure instanceof BookSeoGenerationException seoFailure) {
            return switch (seoFailure.errorCode()) {
                case INVALID_RESPONSE, DESCRIPTION_TOO_SHORT -> true;
                case GENERATION_FAILED, API_CALL_FAILED -> false;
            };
        }
        return false;
    }

    private void processIngestionMetadata(UUID bookId) {
        RuntimeException firstFailure = null;
        if (bookAiContentService.isAvailable()) {
            try {
                BookAiContentService.GenerationOutcome aiOutcome =
                    bookAiContentService.generateAndPersistIfPromptChanged(bookId, ignoredDelta -> {
                    }, LlmGatewayTier.BACKGROUND_BATCH);
                if (aiOutcome.generated()) {
                    log.info("Generated ingestion AI summary for book {}", bookId);
                } else {
                    log.debug("Skipped ingestion AI summary for unchanged prompt hash book {}", bookId);
                }
            } catch (BookAiGenerationException aiFailure) {
                if (aiFailure.errorCode() == BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT) {
                    log.debug("Skipped ingestion AI summary for ineligible book {}: {}", bookId, aiFailure.getMessage());
                } else {
                    firstFailure = aiFailure;
                }
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
            } catch (BookSeoGenerationException | DataAccessException seoFailure) {
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
                    firstFailure = retainHighestSeverityFailure(firstFailure, seoFailure);
                }
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private RuntimeException retainHighestSeverityFailure(
        RuntimeException currentFailure,
        RuntimeException nextFailure
    ) {
        if (currentFailure == null || currentFailure == nextFailure) {
            return nextFailure;
        }
        if (isExpectedGenerationFailure(currentFailure) && !isExpectedGenerationFailure(nextFailure)) {
            nextFailure.addSuppressed(currentFailure);
            return nextFailure;
        }
        currentFailure.addSuppressed(nextFailure);
        return currentFailure;
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
