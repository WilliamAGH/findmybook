package net.findmybook.support.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BookAiContentRequestQueueTest {

    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void should_DequeueHigherPriorityFirst_When_MultiplePendingTasksExist() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseBackgroundTask = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        BookAiContentRequestQueue.EnqueuedTask<String> background = queue.enqueueBackground(0, () -> {
            awaitLatch(releaseBackgroundTask);
            return "background";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> first = queue.enqueue(0, () -> {
            awaitLatch(releaseFirstTask);
            executionOrder.add("first");
            return "first";
        });

        BookAiContentRequestQueue.EnqueuedTask<String> lowerPriority = queue.enqueue(0, () -> {
            executionOrder.add("lower");
            return "lower";
        });

        BookAiContentRequestQueue.EnqueuedTask<String> higherPriority = queue.enqueue(5, () -> {
            executionOrder.add("higher");
            return "higher";
        });

        assertThat(background.started().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNull();
        assertThat(first.started().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNull();
        releaseFirstTask.countDown();

        assertThat(first.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(higherPriority.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("higher");
        assertThat(lowerPriority.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("lower");
        assertThat(executionOrder).containsExactly("first", "higher", "lower");
        releaseBackgroundTask.countDown();
        assertThat(background.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("background");
    }

    @Test
    void should_RunForegroundWhileBackgroundLaneIsAtConfiguredParallelism_When_BackgroundWasQueuedFirst() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseRunningBackground = new CountDownLatch(1);
        CountDownLatch releaseForeground = new CountDownLatch(1);

        BookAiContentRequestQueue.EnqueuedTask<String> runningBackground = queue.enqueueBackground(0, () -> {
            awaitLatch(releaseRunningBackground);
            return "running-background";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> pendingBackground = queue.enqueueBackground(
            0,
            () -> "pending-background"
        );
        BookAiContentRequestQueue.EnqueuedTask<String> foregroundTask = queue.enqueueForeground(0, () -> {
            awaitLatch(releaseForeground);
            return "foreground";
        });

        assertThat(runningBackground.started().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNull();
        assertThat(foregroundTask.started().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNull();
        assertThat(queue.snapshot()).isEqualTo(new BookAiContentRequestQueue.QueueSnapshot(2, 1, 2));

        releaseForeground.countDown();

        assertThat(foregroundTask.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("foreground");
        assertThat(runningBackground.result()).isNotDone();
        assertThat(pendingBackground.started()).isNotDone();

        releaseRunningBackground.countDown();

        assertThat(runningBackground.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("running-background");
        assertThat(pendingBackground.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("pending-background");
    }

    @Test
    void should_RunTwoForegroundTasksConcurrently_When_BackgroundCapacityIsIdle() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch suppliersStarted = new CountDownLatch(2);
        CountDownLatch releaseSuppliers = new CountDownLatch(1);

        BookAiContentRequestQueue.EnqueuedTask<String> first = queue.enqueueForeground(0, () -> {
            suppliersStarted.countDown();
            awaitLatch(releaseSuppliers);
            return "first";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> second = queue.enqueueForeground(0, () -> {
            suppliersStarted.countDown();
            awaitLatch(releaseSuppliers);
            return "second";
        });

        assertThat(suppliersStarted.await(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(queue.snapshot()).isEqualTo(new BookAiContentRequestQueue.QueueSnapshot(2, 0, 2));

        releaseSuppliers.countDown();

        assertThat(first.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(second.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("second");
    }

    @Test
    void should_ReportQueuePosition_When_TaskIsPending() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseBackgroundTask = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);

        BookAiContentRequestQueue.EnqueuedTask<String> background = queue.enqueueBackground(0, () -> {
            awaitLatch(releaseBackgroundTask);
            return "background";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> first = queue.enqueue(0, () -> {
            awaitLatch(releaseFirstTask);
            return "first";
        });

        BookAiContentRequestQueue.EnqueuedTask<String> second = queue.enqueue(0, () -> "second");
        BookAiContentRequestQueue.EnqueuedTask<String> third = queue.enqueue(0, () -> "third");

        BookAiContentRequestQueue.QueuePosition secondPosition = queue.getPosition(second.id());
        BookAiContentRequestQueue.QueuePosition thirdPosition = queue.getPosition(third.id());

        assertThat(secondPosition.inQueue()).isTrue();
        assertThat(secondPosition.position()).isEqualTo(1);
        assertThat(thirdPosition.inQueue()).isTrue();
        assertThat(thirdPosition.position()).isEqualTo(2);

        releaseFirstTask.countDown();
        releaseBackgroundTask.countDown();

        assertThatCode(() -> first.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .doesNotThrowAnyException();
        assertThatCode(() -> background.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .doesNotThrowAnyException();
    }

    @Test
    void should_CancelPendingTask_When_TaskHasNotStarted() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseBackgroundTask = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);

        BookAiContentRequestQueue.EnqueuedTask<String> background = queue.enqueueBackground(0, () -> {
            awaitLatch(releaseBackgroundTask);
            return "background";
        });
        queue.enqueue(0, () -> {
            awaitLatch(releaseFirstTask);
            return "first";
        });

        BookAiContentRequestQueue.EnqueuedTask<String> pending = queue.enqueue(0, () -> "second");

        boolean cancelled = queue.cancel(pending.id());

        assertThat(cancelled).isTrue();
        assertThat(queue.getPosition(pending.id()).inQueue()).isFalse();
        assertThatThrownBy(() -> pending.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);
        assertThatCode(() -> pending.finished().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .doesNotThrowAnyException();

        releaseFirstTask.countDown();
        releaseBackgroundTask.countDown();
        assertThatCode(() -> background.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .doesNotThrowAnyException();
    }

    @Test
    void should_ReportTaskAsNotPending_When_TaskHasStarted() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseRunningTask = new CountDownLatch(1);
        BookAiContentRequestQueue.EnqueuedTask<String> runningTask = queue.enqueue(0, () -> {
            awaitLatch(releaseRunningTask);
            return "running";
        });

        assertThat(runningTask.started().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNull();

        BookAiContentRequestQueue.QueuePosition position = queue.getPosition(runningTask.id());

        assertThat(position.inQueue()).isFalse();
        assertThat(position.position()).isNull();
        releaseRunningTask.countDown();
        assertThat(runningTask.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("running");
    }

    @Test
    void should_ReportSnapshot_When_TasksAreRunningAndPending() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch blocker = new CountDownLatch(1);

        queue.enqueueBackground(0, () -> {
            awaitLatch(blocker);
            return "running";
        });
        queue.enqueueBackground(0, () -> "pending");

        BookAiContentRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.running()).isEqualTo(1);
        assertThat(snapshot.pending()).isEqualTo(1);
        assertThat(snapshot.maxParallel()).isEqualTo(2);

        blocker.countDown();
    }

    @Test
    void should_PropagateException_When_TaskFails() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);

        BookAiContentRequestQueue.EnqueuedTask<String> task = queue.enqueue(0, () -> {
            throw new IllegalStateException("AI generation failed");
        });

        assertThatThrownBy(() -> task.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI generation failed");
    }

    @Test
    void should_ReturnFalse_When_CancellingNonExistentTask() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        assertThat(queue.cancel("nonexistent-id")).isFalse();
    }

    @Test
    void should_CoerceGlobalParallelism_When_ConfiguredBelowMinimum() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(0);
        BookAiContentRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.maxParallel()).isEqualTo(2);
    }

    @Test
    void should_CapGlobalParallelism_When_ConfiguredAboveMax() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(100);
        BookAiContentRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.maxParallel()).isEqualTo(20);
    }

    @Test
    void should_RejectBackgroundButAcceptForeground_When_BackgroundAtPendingCap() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1, 1);
        CountDownLatch releaseRunningBackground = new CountDownLatch(1);

        BookAiContentRequestQueue.EnqueuedTask<String> runningBackground = queue.enqueueBackground(0, () -> {
            awaitLatch(releaseRunningBackground);
            return "running-background";
        });
        queue.enqueueBackground(0, () -> "pending-background");

        assertThatThrownBy(() -> queue.enqueueBackground(0, () -> "overflow-background"))
            .isInstanceOf(BookAiQueueCapacityExceededException.class);

        BookAiContentRequestQueue.EnqueuedTask<String> foregroundTask = queue.enqueueForeground(0, () -> "foreground");
        releaseRunningBackground.countDown();

        assertThat(runningBackground.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("running-background");
        assertThat(foregroundTask.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("foreground");
    }

    @Test
    void should_WireLifecycleBeforeSupplier_When_ExecutionSlotIsImmediatelyAvailable() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        AtomicBoolean startedHandlerRan = new AtomicBoolean(false);

        BookAiContentRequestQueue.EnqueuedTask<String> task = queue.enqueueForeground(
            0,
            () -> {
                if (!startedHandlerRan.get()) {
                    throw new IllegalStateException("supplier ran before started handler");
                }
                return "generated";
            },
            enqueuedTask -> enqueuedTask.started().thenRun(() -> startedHandlerRan.set(true))
        );

        assertThat(task.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("generated");
        assertThat(startedHandlerRan).isTrue();
    }

    @Test
    void should_InterruptRunningSupplierAndReleaseSlotAfterWrapperExits_When_TaskIsCancelled() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseBackgroundTask = new CountDownLatch(1);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        CountDownLatch allowSupplierExit = new CountDownLatch(1);
        CountDownLatch nextSupplierStarted = new CountDownLatch(1);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        BookAiContentRequestQueue.EnqueuedTask<String> backgroundTask = queue.enqueueBackground(0, () -> {
            awaitLatch(releaseBackgroundTask);
            return "background";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> runningTask = queue.enqueue(0, () -> {
            supplierStarted.countDown();
            try {
                allowSupplierExit.await();
            } catch (InterruptedException interruptedException) {
                interruptionObserved.countDown();
                awaitLatchIgnoringInterrupt(allowSupplierExit);
            }
            executionOrder.add("cancelled-exit");
            return "ignored";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> nextTask = queue.enqueue(0, () -> {
            executionOrder.add("next-start");
            nextSupplierStarted.countDown();
            return "next";
        });

        assertThat(supplierStarted.await(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(queue.cancel(runningTask.id())).isTrue();
        assertThat(interruptionObserved.await(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(queue.snapshot()).isEqualTo(new BookAiContentRequestQueue.QueueSnapshot(2, 1, 2));
        assertThat(nextSupplierStarted.getCount()).isEqualTo(1L);
        assertThat(runningTask.finished()).isNotDone();

        allowSupplierExit.countDown();

        assertThatCode(() -> runningTask.finished().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .doesNotThrowAnyException();
        assertThat(nextTask.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("next");
        assertThatThrownBy(() -> runningTask.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);
        assertThat(executionOrder).containsExactly("cancelled-exit", "next-start");
        releaseBackgroundTask.countDown();
        assertThat(backgroundTask.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("background");
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            boolean released = latch.await(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("Latch wait timed out");
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Latch wait interrupted", interruptedException);
        }
    }

    private void awaitLatchIgnoringInterrupt(CountDownLatch latch) {
        boolean released = false;
        boolean interrupted = false;
        while (!released) {
            try {
                released = latch.await(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("Latch wait timed out");
                }
            } catch (InterruptedException interruptedException) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
