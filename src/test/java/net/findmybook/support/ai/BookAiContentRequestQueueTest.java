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
import org.junit.jupiter.api.Test;

class BookAiContentRequestQueueTest {

    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void should_DequeueHigherPriorityFirst_When_MultiplePendingTasksExist() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

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

        assertThat(first.started().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNull();
        releaseFirstTask.countDown();

        assertThat(first.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(higherPriority.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("higher");
        assertThat(lowerPriority.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("lower");
        assertThat(executionOrder).containsExactly("first", "higher", "lower");
    }

    @Test
    void should_RunForegroundBeforePendingBackground_When_BackgroundWasQueuedFirst() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseRunningBackground = new CountDownLatch(1);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        BookAiContentRequestQueue.EnqueuedTask<String> runningBackground = queue.enqueueBackground(0, () -> {
            awaitLatch(releaseRunningBackground);
            executionOrder.add("running-background");
            return "running-background";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> pendingBackground = queue.enqueueBackground(0, () -> {
            executionOrder.add("pending-background");
            return "pending-background";
        });
        BookAiContentRequestQueue.EnqueuedTask<String> foregroundTask = queue.enqueueForeground(0, () -> {
            executionOrder.add("foreground");
            return "foreground";
        });

        assertThat(runningBackground.started().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNull();
        releaseRunningBackground.countDown();

        assertThat(runningBackground.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("running-background");
        assertThat(foregroundTask.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("foreground");
        assertThat(pendingBackground.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("pending-background");
        assertThat(executionOrder).containsExactly("running-background", "foreground", "pending-background");
    }

    @Test
    void should_ReportQueuePosition_When_TaskIsPending() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);

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

        assertThatCode(() -> first.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .doesNotThrowAnyException();
    }

    @Test
    void should_CancelPendingTask_When_TaskHasNotStarted() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);

        queue.enqueue(0, () -> {
            awaitLatch(releaseFirstTask);
            return "first";
        });

        BookAiContentRequestQueue.EnqueuedTask<String> pending = queue.enqueue(0, () -> "second");

        boolean cancelled = queue.cancelPending(pending.id());

        assertThat(cancelled).isTrue();
        assertThat(queue.getPosition(pending.id()).inQueue()).isFalse();
        assertThatThrownBy(() -> pending.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);

        releaseFirstTask.countDown();
    }

    @Test
    void should_ReportSnapshot_When_TasksAreRunningAndPending() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(1);
        CountDownLatch blocker = new CountDownLatch(1);

        queue.enqueue(0, () -> {
            awaitLatch(blocker);
            return "running";
        });
        queue.enqueue(0, () -> "pending");

        BookAiContentRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.running()).isEqualTo(1);
        assertThat(snapshot.pending()).isEqualTo(1);
        assertThat(snapshot.maxParallel()).isEqualTo(1);

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
        assertThat(queue.cancelPending("nonexistent-id")).isFalse();
    }

    @Test
    void should_CoerceParallelism_When_ConfiguredBelowOne() throws Exception {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(0);
        BookAiContentRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.maxParallel()).isEqualTo(1);
    }

    @Test
    void should_CapParallelism_When_ConfiguredAboveMax() {
        BookAiContentRequestQueue queue = new BookAiContentRequestQueue(100);
        BookAiContentRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.maxParallel()).isEqualTo(20);
    }

    @Test
    void should_RejectBackgroundAfterPendingCapButStillAcceptForeground() throws Exception {
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
}
