package net.findmybook.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.findmybook.application.seo.BookSeoGenerationException;
import net.findmybook.application.seo.BookSeoMetadataGenerationService;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.ai.BookAiQueueCapacityExceededException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

@ExtendWith(MockitoExtension.class)
class BookAiIngestionMetadataCoordinatorTest {

    @Mock
    private BookAiContentRequestQueue requestQueue;
    @Mock
    private BookAiContentService bookAiContentService;
    @Mock
    private BookSeoMetadataGenerationService bookSeoMetadataGenerationService;

    @Test
    void should_ListenAfterCommitWithoutFallback_When_BookUpsertEventIsPublished() throws NoSuchMethodException {
        TransactionalEventListener listener = BookAiIngestionMetadataCoordinator.class
            .getDeclaredMethod("handleBookUpsert", BookUpsertEvent.class)
            .getAnnotation(TransactionalEventListener.class);

        assertNotNull(listener);
        assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase());
        assertFalse(listener.fallbackExecution());
    }

    @Test
    void should_EnqueueOnlyAfterCommit_When_TransactionPublishesBookUpsertEvent() {
        UUID bookId = UUID.randomUUID();
        BookUpsertEvent event = bookUpsertEvent(bookId);
        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenReturn(new BookAiContentRequestQueue.EnqueuedTask<>(
                "after-commit-task",
                CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(null)
            ));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(BookAiIngestionMetadataCoordinator.class, this::newCoordinator);
            context.refresh();

            beginTransactionSynchronization();
            context.publishEvent(event);
            verify(requestQueue, never()).enqueueBackground(anyInt(), any());
            completeTransactionSynchronization(TransactionSynchronization.STATUS_COMMITTED);
            verify(requestQueue).enqueueBackground(eq(0), any());

            beginTransactionSynchronization();
            context.publishEvent(event);
            completeTransactionSynchronization(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(requestQueue, times(1)).enqueueBackground(anyInt(), any());
        } finally {
            clearTransactionSynchronization();
        }
    }

    @Test
    void should_EnqueueBackgroundGeneration_When_BookUpsertEventHasValidUuid() {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = bookUpsertEvent(bookId);

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
        when(bookAiContentService.generateAndPersistIfPromptChanged(eq(bookId), any(), any()))
            .thenReturn(new BookAiContentService.GenerationOutcome(bookId, true, "hash-ai", Optional.empty()));
        when(bookSeoMetadataGenerationService.generateAndPersistIfPromptChanged(bookId))
            .thenReturn(new BookSeoMetadataGenerationService.GenerationOutcome(bookId, true, "hash-seo", Optional.empty()));

        coordinator.handleBookUpsert(event);

        verify(requestQueue).enqueueBackground(eq(0), any());
        verify(bookAiContentService).generateAndPersistIfPromptChanged(eq(bookId), any(), any());
        verify(bookSeoMetadataGenerationService).generateAndPersistIfPromptChanged(bookId);
    }

    @Test
    void should_ReleaseBookForRetry_When_BackgroundQueueIsAtCapacity() {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = bookUpsertEvent(bookId);

        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenThrow(new BookAiQueueCapacityExceededException(100_000, 100_000))
            .thenReturn(pendingTask("capacity-retry", new CompletableFuture<>()));

        coordinator.handleBookUpsert(event);
        coordinator.handleBookUpsert(event);

        verify(requestQueue, times(2)).enqueueBackground(eq(0), any());
    }

    @Test
    void should_ReleaseBookForRetry_When_BackgroundEnqueueThrows() {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = bookUpsertEvent(bookId);

        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenThrow(new IllegalStateException("background queue unavailable"))
            .thenReturn(pendingTask("enqueue-retry", new CompletableFuture<>()));

        assertThatThrownBy(() -> coordinator.handleBookUpsert(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("background queue unavailable");

        coordinator.handleBookUpsert(event);

        verify(requestQueue, times(2)).enqueueBackground(eq(0), any());
    }

    @Test
    void should_CoalesceConcurrentEnqueuesAndAllowRetry_When_SameBookTaskCompletes()
        throws InterruptedException, ExecutionException, TimeoutException {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = bookUpsertEvent(bookId);
        CompletableFuture<Void> firstResult = new CompletableFuture<>();
        CountDownLatch firstEnqueueEntered = new CountDownLatch(1);
        CountDownLatch allowFirstEnqueueReturn = new CountDownLatch(1);
        Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(BookAiIngestionMetadataCoordinator.class);
        ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
        boolean originalAdditivity = coordinatorLogger.isAdditive();
        Level originalLevel = coordinatorLogger.getLevel();

        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenAnswer(invocation -> {
                firstEnqueueEntered.countDown();
                assertThat(allowFirstEnqueueReturn.await(5, TimeUnit.SECONDS)).isTrue();
                return pendingTask("coalesced-task", firstResult);
            })
            .thenReturn(pendingTask("retry-task", new CompletableFuture<>()));
        coordinatorLogger.setAdditive(false);
        coordinatorLogger.setLevel(Level.DEBUG);
        logEvents.start();
        coordinatorLogger.addAppender(logEvents);

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                Future<?> firstUpsert = executorService.submit(() -> coordinator.handleBookUpsert(event));
                assertThat(firstEnqueueEntered.await(5, TimeUnit.SECONDS)).isTrue();

                Future<?> duplicateUpsert = executorService.submit(() -> coordinator.handleBookUpsert(event));
                duplicateUpsert.get(5, TimeUnit.SECONDS);
                verify(requestQueue).enqueueBackground(eq(0), any());

                allowFirstEnqueueReturn.countDown();
                firstUpsert.get(5, TimeUnit.SECONDS);
                firstResult.complete(null);
                coordinator.handleBookUpsert(event);

                verify(requestQueue, times(2)).enqueueBackground(eq(0), any());
                assertThat(logEvents.list).hasSize(1);
                assertThat(logEvents.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
                assertThat(logEvents.list.get(0).getFormattedMessage())
                    .contains(bookId.toString(), "already pending or running");
            } finally {
                allowFirstEnqueueReturn.countDown();
            }
        } finally {
            coordinatorLogger.detachAppender(logEvents);
            logEvents.stop();
            coordinatorLogger.setAdditive(originalAdditivity);
            coordinatorLogger.setLevel(originalLevel);
        }
    }

    @Test
    void should_KeepBookActiveUntilExecutionFinishes_When_RunningTaskIsCancelled() {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = bookUpsertEvent(bookId);
        CompletableFuture<Void> cancelledResult = new CompletableFuture<>();
        CompletableFuture<Void> executionFinished = new CompletableFuture<>();
        BookAiContentRequestQueue.EnqueuedTask<Void> cancelledTask = new BookAiContentRequestQueue.EnqueuedTask<>(
            "cancelled-running-task",
            CompletableFuture.completedFuture(null),
            cancelledResult,
            executionFinished
        );

        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenReturn(cancelledTask)
            .thenReturn(pendingTask("post-cancellation-retry", new CompletableFuture<>()));

        coordinator.handleBookUpsert(event);
        cancelledResult.completeExceptionally(new CancellationException("Task cancelled during execution"));
        coordinator.handleBookUpsert(event);

        verify(requestQueue).enqueueBackground(eq(0), any());

        executionFinished.complete(null);
        coordinator.handleBookUpsert(event);

        verify(requestQueue, times(2)).enqueueBackground(eq(0), any());
    }

    @Test
    void should_ClassifyBackgroundCompletionFailuresAndReleaseBookForRetry_When_TaskFinishesExceptionally() {
        UUID bookId = UUID.randomUUID();
        Logger coordinatorLogger = (Logger) LoggerFactory.getLogger(BookAiIngestionMetadataCoordinator.class);
        ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
        boolean originalAdditivity = coordinatorLogger.isAdditive();
        Level originalLevel = coordinatorLogger.getLevel();
        coordinatorLogger.setAdditive(false);
        coordinatorLogger.setLevel(Level.DEBUG);
        logEvents.start();
        coordinatorLogger.addAppender(logEvents);

        try {
            BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
            when(bookAiContentService.isAvailable()).thenReturn(true);
            when(bookSeoMetadataGenerationService.isAvailable()).thenReturn(true);
            when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
                .thenReturn(failedTask("expected-failure", new BookAiGenerationException(
                    BookAiGenerationException.ErrorCode.INVALID_RESPONSE,
                    "AI response did not include a valid JSON object"
                )))
                .thenAnswer(invocation -> executeTask("dual-failure", invocation.getArgument(1)))
                .thenReturn(failedTask("cancelled", new CancellationException("deployment shutdown")))
                .thenReturn(failedTask("seo-invalid", new BookSeoGenerationException(
                    BookSeoGenerationException.ErrorCode.INVALID_RESPONSE, "invalid SEO JSON")))
                .thenReturn(failedTask("seo-api", new BookSeoGenerationException(
                    BookSeoGenerationException.ErrorCode.API_CALL_FAILED, "SEO upstream unavailable")))
                .thenReturn(failedTask("enrichment-failure", new BookAiGenerationException(
                    BookAiGenerationException.ErrorCode.ENRICHMENT_FAILED,
                    "Description enrichment failed for book: " + bookId
                )))
                .thenReturn(failedTask("all-provider-failure", new BookAiGenerationException(
                    BookAiGenerationException.ErrorCode.GENERATION_FAILED,
                    "All configured AI providers failed"
                )));
            when(bookAiContentService.generateAndPersistIfPromptChanged(eq(bookId), any(), any()))
                .thenThrow(new BookAiGenerationException(
                    BookAiGenerationException.ErrorCode.INVALID_RESPONSE,
                    "AI response did not include a valid JSON object"
                ));
            when(bookSeoMetadataGenerationService.generateAndPersistIfPromptChanged(bookId))
                .thenThrow(new DataAccessResourceFailureException("database enrichment failed"));

            coordinator.handleBookUpsert(bookUpsertEvent(bookId));
            coordinator.handleBookUpsert(bookUpsertEvent(bookId));
            coordinator.handleBookUpsert(bookUpsertEvent(bookId));
            coordinator.handleBookUpsert(bookUpsertEvent(bookId));
            coordinator.handleBookUpsert(bookUpsertEvent(bookId));
            coordinator.handleBookUpsert(bookUpsertEvent(bookId));
            coordinator.handleBookUpsert(bookUpsertEvent(bookId));

            assertThat(logEvents.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.ERROR, Level.DEBUG, Level.WARN, Level.ERROR, Level.WARN, Level.ERROR);
            assertThat(logEvents.list.get(0).getFormattedMessage()).contains(bookId.toString(), "did not complete");
            assertThat(logEvents.list.get(1).getThrowableProxy().getClassName())
                .contains("DataAccessResourceFailureException");
            assertThat(logEvents.list.get(1).getThrowableProxy().getSuppressed()).hasSize(1);
            assertThat(logEvents.list.get(3).getFormattedMessage()).contains("invalid SEO JSON");
            assertThat(logEvents.list.get(4).getThrowableProxy().getClassName()).contains("BookSeoGenerationException");
            assertThat(logEvents.list.get(5).getFormattedMessage()).contains("Description enrichment failed");
            assertThat(logEvents.list.get(6).getThrowableProxy().getClassName()).contains("BookAiGenerationException");
            verify(requestQueue, times(7)).enqueueBackground(eq(0), any());
        } finally {
            coordinatorLogger.detachAppender(logEvents);
            logEvents.stop();
            coordinatorLogger.setAdditive(originalAdditivity);
            coordinatorLogger.setLevel(originalLevel);
        }
    }

    @Test
    void should_ContinueSeoGeneration_When_AiDescriptionIsTooShort() {
        UUID bookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();
        BookUpsertEvent event = bookUpsertEvent(bookId);

        when(bookAiContentService.isAvailable()).thenReturn(true);
        when(bookSeoMetadataGenerationService.isAvailable()).thenReturn(true);
        when(requestQueue.<Void>enqueueBackground(anyInt(), any()))
            .thenAnswer(invocation -> {
                Supplier<Void> supplier = invocation.getArgument(1);
                supplier.get();
                return new BookAiContentRequestQueue.EnqueuedTask<>(
                    "task-description-too-short",
                    CompletableFuture.completedFuture(null),
                    CompletableFuture.completedFuture(null)
                );
            });
        when(bookAiContentService.generateAndPersistIfPromptChanged(eq(bookId), any(), any()))
            .thenThrow(new BookAiGenerationException(
                BookAiGenerationException.ErrorCode.DESCRIPTION_TOO_SHORT,
                "Book description is missing or too short"
            ));
        when(bookSeoMetadataGenerationService.generateAndPersistIfPromptChanged(bookId))
            .thenReturn(new BookSeoMetadataGenerationService.GenerationOutcome(
                bookId, true, "hash-seo", Optional.empty()));

        coordinator.handleBookUpsert(event);

        verify(bookAiContentService).generateAndPersistIfPromptChanged(eq(bookId), any(), any());
        verify(bookSeoMetadataGenerationService).generateAndPersistIfPromptChanged(bookId);
    }

    @Test
    void should_DisableSeoIngestionGeneration_When_SeoMetadataTableIsMissing() {
        UUID firstBookId = UUID.randomUUID();
        UUID secondBookId = UUID.randomUUID();
        BookAiIngestionMetadataCoordinator coordinator = newCoordinator();

        BookUpsertEvent firstEvent = bookUpsertEvent(firstBookId);
        BookUpsertEvent secondEvent = bookUpsertEvent(secondBookId);

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
        when(bookAiContentService.generateAndPersistIfPromptChanged(eq(firstBookId), any(), any()))
            .thenReturn(new BookAiContentService.GenerationOutcome(firstBookId, true, "hash-ai-1", Optional.empty()));
        when(bookAiContentService.generateAndPersistIfPromptChanged(eq(secondBookId), any(), any()))
            .thenReturn(new BookAiContentService.GenerationOutcome(secondBookId, true, "hash-ai-2", Optional.empty()));

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
        verify(bookAiContentService).generateAndPersistIfPromptChanged(eq(firstBookId), any(), any());
        verify(bookAiContentService).generateAndPersistIfPromptChanged(eq(secondBookId), any(), any());
    }

    private BookAiIngestionMetadataCoordinator newCoordinator() {
        return new BookAiIngestionMetadataCoordinator(
            requestQueue,
            bookAiContentService,
            bookSeoMetadataGenerationService
        );
    }

    private BookUpsertEvent bookUpsertEvent(UUID bookId) {
        return new BookUpsertEvent(
            bookId.toString(), "book-slug", "Book title", true,
            "GOOGLE_BOOKS", null, null, "GOOGLE_BOOKS"
        );
    }

    private BookAiContentRequestQueue.EnqueuedTask<Void> failedTask(String taskId, RuntimeException failure) {
        CompletableFuture<Void> failedResult = new CompletableFuture<>();
        failedResult.completeExceptionally(failure);
        return new BookAiContentRequestQueue.EnqueuedTask<>(taskId, CompletableFuture.completedFuture(null), failedResult);
    }

    private BookAiContentRequestQueue.EnqueuedTask<Void> pendingTask(String taskId, CompletableFuture<Void> result) {
        return new BookAiContentRequestQueue.EnqueuedTask<>(taskId, CompletableFuture.completedFuture(null), result);
    }

    private BookAiContentRequestQueue.EnqueuedTask<Void> executeTask(String taskId, Supplier<Void> supplier) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            result.complete(supplier.get());
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return new BookAiContentRequestQueue.EnqueuedTask<>(taskId, CompletableFuture.completedFuture(null), result);
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void completeTransactionSynchronization(int completionStatus) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (completionStatus == TransactionSynchronization.STATUS_COMMITTED) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(completionStatus));
        clearTransactionSynchronization();
    }

    private void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
