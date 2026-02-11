package net.findmybook.application.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.findmybook.application.seo.BookSeoMetadataGenerationService;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.ai.BookAiQueueCapacityExceededException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookAiIngestionMetadataCoordinatorTest {

    @Mock
    private BookAiContentRequestQueue requestQueue;
    @Mock
    private BookAiContentService bookAiContentService;
    @Mock
    private BookSeoMetadataGenerationService bookSeoMetadataGenerationService;

    @Test
    void should_EnqueueBackgroundGeneration_When_BookUpsertEventHasValidUuid() {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = new BookUpsertEvent(
            bookId.toString(),
            "book-slug",
            "Book title",
            true,
            "GOOGLE_BOOKS",
            null,
            null,
            "GOOGLE_BOOKS"
        );

        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(bookSeoMetadataGenerationService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenAnswer(invocation -> {
                Supplier<Void> supplier = invocation.getArgument(1);
                supplier.get();
                return new BookAiContentRequestQueue.EnqueuedTask<>(
                    "task-1",
                    CompletableFuture.completedFuture(null),
                    CompletableFuture.completedFuture(null)
                );
            });
        when(bookAiContentService.generateAndPersistIfPromptChanged(eq(bookId), any()))
            .thenReturn(new BookAiContentService.GenerationOutcome(bookId, true, "hash-ai", null));
        when(bookSeoMetadataGenerationService.generateAndPersistIfPromptChanged(bookId))
            .thenReturn(new BookSeoMetadataGenerationService.GenerationOutcome(bookId, true, "hash-seo", null));

        coordinator.handleBookUpsert(event);

        verify(requestQueue).enqueueBackground(eq(0), any());
        verify(bookAiContentService).generateAndPersistIfPromptChanged(eq(bookId), any());
        verify(bookSeoMetadataGenerationService).generateAndPersistIfPromptChanged(bookId);
    }

    @Test
    void should_SkipEnqueue_When_BackgroundQueueIsAtCapacity() {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = new BookUpsertEvent(
            bookId.toString(),
            "book-slug",
            "Book title",
            true,
            "GOOGLE_BOOKS",
            null,
            null,
            "GOOGLE_BOOKS"
        );

        when(bookAiContentService.isAvailable()).thenReturn(true);
        doThrow(new BookAiQueueCapacityExceededException(100_000, 100_000))
            .when(requestQueue).enqueueBackground(anyInt(), any());

        coordinator.handleBookUpsert(event);

        verify(requestQueue).enqueueBackground(eq(0), any());
    }

    @Test
    void should_DisableSeoIngestionGeneration_When_SeoMetadataTableIsMissing() {
        UUID firstBookId = UUID.randomUUID();
        UUID secondBookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();

        BookUpsertEvent firstEvent = new BookUpsertEvent(
            firstBookId.toString(),
            "first-book",
            "First book",
            true,
            "GOOGLE_BOOKS",
            null,
            null,
            "GOOGLE_BOOKS"
        );
        BookUpsertEvent secondEvent = new BookUpsertEvent(
            secondBookId.toString(),
            "second-book",
            "Second book",
            true,
            "GOOGLE_BOOKS",
            null,
            null,
            "GOOGLE_BOOKS"
        );

        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(bookSeoMetadataGenerationService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenAnswer(invocation -> {
                Supplier<Void> supplier = invocation.getArgument(1);
                supplier.get();
                return new BookAiContentRequestQueue.EnqueuedTask<>(
                    "task",
                    CompletableFuture.completedFuture(null),
                    CompletableFuture.completedFuture(null)
                );
            });
        when(bookAiContentService.generateAndPersistIfPromptChanged(eq(firstBookId), any()))
            .thenReturn(new BookAiContentService.GenerationOutcome(firstBookId, true, "hash-ai-1", null));
        when(bookAiContentService.generateAndPersistIfPromptChanged(eq(secondBookId), any()))
            .thenReturn(new BookAiContentService.GenerationOutcome(secondBookId, true, "hash-ai-2", null));

        DataAccessResourceFailureException missingRelation = new DataAccessResourceFailureException(
            "PreparedStatementCallback; bad SQL grammar",
            new RuntimeException("ERROR: relation \"book_seo_metadata\" does not exist")
        );
        when(bookSeoMetadataGenerationService.generateAndPersistIfPromptChanged(firstBookId))
            .thenThrow(missingRelation);

        coordinator.handleBookUpsert(firstEvent);
        coordinator.handleBookUpsert(secondEvent);

        verify(bookSeoMetadataGenerationService, times(1)).generateAndPersistIfPromptChanged(firstBookId);
        verify(bookSeoMetadataGenerationService, never()).generateAndPersistIfPromptChanged(secondBookId);
        verify(bookAiContentService).generateAndPersistIfPromptChanged(eq(firstBookId), any());
        verify(bookAiContentService).generateAndPersistIfPromptChanged(eq(secondBookId), any());
    }

    private BookAiIngestionMetadataCoordinator newCoordinator() {
        return new BookAiIngestionMetadataCoordinator(
            requestQueue,
            bookAiContentService,
            bookSeoMetadataGenerationService
        );
    }
}
