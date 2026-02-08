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

class BookAiRequestQueueTest {

    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void should_DequeueHigherPriorityFirst_When_MultiplePendingTasksExist() throws Exception {
        BookAiRequestQueue queue = new BookAiRequestQueue(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        BookAiRequestQueue.EnqueuedTask<String> first = queue.enqueue(0, () -> {
            awaitLatch(releaseFirstTask);
            executionOrder.add("first");
            return "first";
        });

        BookAiRequestQueue.EnqueuedTask<String> lowerPriority = queue.enqueue(0, () -> {
            executionOrder.add("lower");
            return "lower";
        });

        BookAiRequestQueue.EnqueuedTask<String> higherPriority = queue.enqueue(5, () -> {
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
    void should_ReportQueuePosition_When_TaskIsPending() {
        BookAiRequestQueue queue = new BookAiRequestQueue(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);

        BookAiRequestQueue.EnqueuedTask<String> first = queue.enqueue(0, () -> {
            awaitLatch(releaseFirstTask);
            return "first";
        });

        BookAiRequestQueue.EnqueuedTask<String> second = queue.enqueue(0, () -> "second");
        BookAiRequestQueue.EnqueuedTask<String> third = queue.enqueue(0, () -> "third");

        BookAiRequestQueue.QueuePosition secondPosition = queue.getPosition(second.id());
        BookAiRequestQueue.QueuePosition thirdPosition = queue.getPosition(third.id());

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
        BookAiRequestQueue queue = new BookAiRequestQueue(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);

        queue.enqueue(0, () -> {
            awaitLatch(releaseFirstTask);
            return "first";
        });

        BookAiRequestQueue.EnqueuedTask<String> pending = queue.enqueue(0, () -> "second");

        boolean cancelled = queue.cancelPending(pending.id());

        assertThat(cancelled).isTrue();
        assertThat(queue.getPosition(pending.id()).inQueue()).isFalse();
        assertThatThrownBy(() -> pending.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);

        releaseFirstTask.countDown();
    }

    @Test
    void should_ReportSnapshot_When_TasksAreRunningAndPending() {
        BookAiRequestQueue queue = new BookAiRequestQueue(1);
        CountDownLatch blocker = new CountDownLatch(1);

        queue.enqueue(0, () -> {
            awaitLatch(blocker);
            return "running";
        });
        queue.enqueue(0, () -> "pending");

        BookAiRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.running()).isEqualTo(1);
        assertThat(snapshot.pending()).isEqualTo(1);
        assertThat(snapshot.maxParallel()).isEqualTo(1);

        blocker.countDown();
    }

    @Test
    void should_PropagateException_When_TaskFails() {
        BookAiRequestQueue queue = new BookAiRequestQueue(1);

        BookAiRequestQueue.EnqueuedTask<String> task = queue.enqueue(0, () -> {
            throw new IllegalStateException("AI generation failed");
        });

        assertThatThrownBy(() -> task.result().get(TASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI generation failed");
    }

    @Test
    void should_ReturnFalse_When_CancellingNonExistentTask() {
        BookAiRequestQueue queue = new BookAiRequestQueue(1);
        assertThat(queue.cancelPending("nonexistent-id")).isFalse();
    }

    @Test
    void should_CoerceParallelism_When_ConfiguredBelowOne() throws Exception {
        BookAiRequestQueue queue = new BookAiRequestQueue(0);
        BookAiRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.maxParallel()).isEqualTo(1);
    }

    @Test
    void should_CapParallelism_When_ConfiguredAboveMax() {
        BookAiRequestQueue queue = new BookAiRequestQueue(100);
        BookAiRequestQueue.QueueSnapshot snapshot = queue.snapshot();
        assertThat(snapshot.maxParallel()).isEqualTo(20);
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
