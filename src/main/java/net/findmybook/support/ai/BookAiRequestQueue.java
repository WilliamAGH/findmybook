package net.findmybook.support.ai;

import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Global in-memory queue for outbound AI generation requests.
 *
 * <p>The queue mirrors the queue-position semantics used by frontend SSE clients:
 * pending position, running count, and bounded parallel execution.</p>
 */
@Service
public class BookAiRequestQueue {

    private static final int MAX_ALLOWED_PARALLEL = 20;

    private final ExecutorService executorService;
    private final TreeMap<Integer, Deque<QueuedTask<?>>> pendingByPriority;
    private final Map<String, QueuedTask<?>> pendingById;
    private final Map<String, QueuedTask<?>> runningById;
    private final int maxParallel;

    private int runningCount;

    public BookAiRequestQueue(@Value("${AI_DEFAULT_MAX_PARALLEL:1}") int configuredParallelism) {
        this.maxParallel = coerceParallelism(configuredParallelism);
        this.pendingByPriority = new TreeMap<>(Comparator.reverseOrder());
        this.pendingById = new HashMap<>();
        this.runningById = new HashMap<>();
        this.executorService = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("book-ai-queue-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
        this.runningCount = 0;
    }

    /**
     * Returns queue depth and concurrency metrics.
     */
    public synchronized QueueSnapshot snapshot() {
        return new QueueSnapshot(runningCount, pendingById.size(), maxParallel);
    }

    /**
     * Returns a task's current queue position when pending.
     */
    public synchronized QueuePosition getPosition(String taskId) {
        int offset = 0;
        for (Map.Entry<Integer, Deque<QueuedTask<?>>> entry : pendingByPriority.entrySet()) {
            int index = 0;
            for (QueuedTask<?> task : entry.getValue()) {
                if (task.id.equals(taskId)) {
                    QueueSnapshot snapshot = snapshot();
                    return new QueuePosition(true, offset + index + 1, snapshot.running(), snapshot.pending(), snapshot.maxParallel());
                }
                index += 1;
            }
            offset += entry.getValue().size();
        }

        QueueSnapshot snapshot = snapshot();
        return new QueuePosition(false, null, snapshot.running(), snapshot.pending(), snapshot.maxParallel());
    }

    /**
     * Enqueues a task for execution with optional priority.
     *
     * @param priority higher values run earlier
     * @param supplier task execution callback
     * @return queued task metadata (id + lifecycle futures)
     */
    public synchronized <T> EnqueuedTask<T> enqueue(int priority, Supplier<T> supplier) {
        String taskId = UUID.randomUUID().toString();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<T> result = new CompletableFuture<>();
        QueuedTask<T> queuedTask = new QueuedTask<>(taskId, priority, supplier, started, result);

        pendingByPriority.computeIfAbsent(priority, key -> new ArrayDeque<>()).addLast(queuedTask);
        pendingById.put(taskId, queuedTask);

        drain();
        return new EnqueuedTask<>(taskId, started, result);
    }

    /**
     * Cancels a pending task by ID.
     *
     * @return true when the task was pending and removed
     */
    public synchronized boolean cancelPending(String taskId) {
        QueuedTask<?> queuedTask = pendingById.remove(taskId);
        if (queuedTask == null) {
            return false;
        }

        Deque<QueuedTask<?>> priorityQueue = pendingByPriority.get(queuedTask.priority);
        if (priorityQueue != null) {
            priorityQueue.removeIf(task -> task.id.equals(taskId));
            if (priorityQueue.isEmpty()) {
                pendingByPriority.remove(queuedTask.priority);
            }
        }

        CancellationException cancellation = new CancellationException("Queue task cancelled before start");
        queuedTask.started.completeExceptionally(cancellation);
        queuedTask.result.completeExceptionally(cancellation);
        return true;
    }

    @PreDestroy
    void shutdown() {
        executorService.shutdownNow();
    }

    private synchronized void drain() {
        while (runningCount < maxParallel) {
            QueuedTask<?> next = shiftNext();
            if (next == null) {
                return;
            }

            runningCount += 1;
            runningById.put(next.id, next);
            next.started.complete(null);
            executorService.submit(() -> executeTask(next));
        }
    }

    private synchronized QueuedTask<?> shiftNext() {
        Iterator<Map.Entry<Integer, Deque<QueuedTask<?>>>> iterator = pendingByPriority.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Deque<QueuedTask<?>>> entry = iterator.next();
            Deque<QueuedTask<?>> queue = entry.getValue();
            QueuedTask<?> next = queue.pollFirst();
            if (next == null) {
                continue;
            }
            pendingById.remove(next.id);
            if (queue.isEmpty()) {
                iterator.remove();
            }
            return next;
        }
        return null;
    }

    private <T> void executeTask(QueuedTask<T> task) {
        try {
            T value = task.supplier.get();
            task.result.complete(value);
        } catch (Exception exception) {
            task.result.completeExceptionally(exception);
        } finally {
            synchronized (this) {
                runningById.remove(task.id);
                runningCount -= 1;
                drain();
            }
        }
    }

    private static int coerceParallelism(int configuredParallelism) {
        if (configuredParallelism <= 0) {
            return 1;
        }
        return Math.min(configuredParallelism, MAX_ALLOWED_PARALLEL);
    }

    private static final class QueuedTask<T> {
        private final String id;
        private final int priority;
        private final Supplier<T> supplier;
        private final CompletableFuture<Void> started;
        private final CompletableFuture<T> result;

        private QueuedTask(String id,
                           int priority,
                           Supplier<T> supplier,
                           CompletableFuture<Void> started,
                           CompletableFuture<T> result) {
            this.id = id;
            this.priority = priority;
            this.supplier = supplier;
            this.started = started;
            this.result = result;
        }
    }

    public record QueueSnapshot(int running, int pending, int maxParallel) {
    }

    public record QueuePosition(boolean inQueue,
                                Integer position,
                                int running,
                                int pending,
                                int maxParallel) {
    }

    public record EnqueuedTask<T>(String id,
                                  CompletableFuture<Void> started,
                                  CompletableFuture<T> result) {
    }
}
