package net.findmybook.support.ai;

import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Global in-memory queue for outbound AI generation requests.
 *
 * <p>The queue mirrors the queue-position semantics used by frontend SSE clients:
 * pending position, running count, and bounded parallel execution.</p>
 */
@Service
public class BookAiContentRequestQueue {

    private static final Logger log = LoggerFactory.getLogger(BookAiContentRequestQueue.class);
    private static final int MAX_ALLOWED_PARALLEL = 20;
    private static final int DEFAULT_MAX_BACKGROUND_PENDING = 100_000;

    private final ExecutorService executorService;
    private final TreeMap<Integer, Deque<QueuedTask<?>>> pendingForegroundByPriority;
    private final TreeMap<Integer, Deque<QueuedTask<?>>> pendingBackgroundByPriority;
    private final Map<String, QueuedTask<?>> pendingById;
    private final Map<String, QueuedTask<?>> runningById;
    private final int maxParallel;
    private final int maxBackgroundPending;

    private int runningCount;
    private int pendingForegroundCount;
    private int pendingBackgroundCount;

    @Autowired
    public BookAiContentRequestQueue(@Value("${AI_DEFAULT_MAX_PARALLEL:1}") int configuredParallelism,
                                     @Value("${app.ai.queue.background-max-pending:100000}") int configuredBackgroundPending) {
        this.maxParallel = coerceParallelism(configuredParallelism);
        this.maxBackgroundPending = coerceBackgroundPending(configuredBackgroundPending);
        this.pendingForegroundByPriority = new TreeMap<>(Comparator.reverseOrder());
        this.pendingBackgroundByPriority = new TreeMap<>(Comparator.reverseOrder());
        this.pendingById = new HashMap<>();
        this.runningById = new HashMap<>();
        this.executorService = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("book-ai-queue-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
        this.runningCount = 0;
        this.pendingForegroundCount = 0;
        this.pendingBackgroundCount = 0;
    }

    BookAiContentRequestQueue(int configuredParallelism) {
        this(configuredParallelism, DEFAULT_MAX_BACKGROUND_PENDING);
    }

    /**
     * Returns queue depth and concurrency metrics.
     */
    public synchronized QueueSnapshot snapshot() {
        return new QueueSnapshot(runningCount, pendingForegroundCount + pendingBackgroundCount, maxParallel);
    }

    /**
     * Returns a task's current queue position when pending.
     */
    public synchronized QueuePosition getPosition(String taskId) {
        QueueSnapshot snapshot = snapshot();
        if (!pendingById.containsKey(taskId)) {
            return new QueuePosition(false, null, snapshot.running(), snapshot.pending(), snapshot.maxParallel());
        }
        PositionLookup foregroundPosition = findPendingPosition(pendingForegroundByPriority, taskId, 0);
        if (foregroundPosition.inQueue) {
            return new QueuePosition(true, foregroundPosition.position, snapshot.running(), snapshot.pending(), snapshot.maxParallel());
        }

        PositionLookup backgroundPosition = findPendingPosition(
            pendingBackgroundByPriority,
            taskId,
            pendingForegroundCount
        );
        if (backgroundPosition.inQueue) {
            return new QueuePosition(true, backgroundPosition.position, snapshot.running(), snapshot.pending(), snapshot.maxParallel());
        }

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
        return enqueueForeground(priority, supplier);
    }

    /**
     * Enqueues an interactive foreground task. Foreground tasks always execute before
     * any pending background ingestion tasks.
     */
    public synchronized <T> EnqueuedTask<T> enqueueForeground(int priority, Supplier<T> supplier) {
        return enqueueInternal(BookAiQueueLane.FOREGROUND_SVELTE, priority, supplier, ignored -> { });
    }

    /**
     * Enqueues an interactive task after its owner has atomically installed lifecycle handlers.
     *
     * <p>The setup callback runs after the task is visible as pending and before the queue can
     * dequeue it. This guarantees that an immediately available execution slot cannot run the
     * supplier before its started/result handlers are wired.</p>
     *
     * @param priority higher values run earlier
     * @param supplier task execution callback
     * @param lifecycleSetup callback that wires the returned handle before execution becomes eligible
     * @return queued task metadata (id + lifecycle futures)
     */
    public synchronized <T> EnqueuedTask<T> enqueueForeground(
        int priority,
        Supplier<T> supplier,
        Consumer<EnqueuedTask<T>> lifecycleSetup
    ) {
        return enqueueInternal(BookAiQueueLane.FOREGROUND_SVELTE, priority, supplier, lifecycleSetup);
    }

    /**
     * Enqueues a background ingestion task subject to the configured background pending cap.
     */
    public synchronized <T> EnqueuedTask<T> enqueueBackground(int priority, Supplier<T> supplier) {
        if (pendingBackgroundCount >= maxBackgroundPending) {
            throw new BookAiQueueCapacityExceededException(maxBackgroundPending, pendingBackgroundCount);
        }
        return enqueueInternal(BookAiQueueLane.BACKGROUND_INGESTION, priority, supplier, ignored -> { });
    }

    private <T> EnqueuedTask<T> enqueueInternal(
        BookAiQueueLane lane,
        int priority,
        Supplier<T> supplier,
        Consumer<EnqueuedTask<T>> lifecycleSetup
    ) {
        if (supplier == null) {
            throw new IllegalArgumentException("supplier is required");
        }
        if (lifecycleSetup == null) {
            throw new IllegalArgumentException("lifecycleSetup is required");
        }
        String taskId = UUID.randomUUID().toString();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<T> result = new CompletableFuture<>();
        QueuedTask<T> queuedTask = new QueuedTask<>(taskId, lane, priority, supplier, started, result);
        EnqueuedTask<T> enqueuedTask = new EnqueuedTask<>(taskId, started, result);

        TreeMap<Integer, Deque<QueuedTask<?>>> targetQueue = lane == BookAiQueueLane.FOREGROUND_SVELTE
            ? pendingForegroundByPriority
            : pendingBackgroundByPriority;
        targetQueue.computeIfAbsent(priority, key -> new ArrayDeque<>()).addLast(queuedTask);
        pendingById.put(taskId, queuedTask);
        if (lane == BookAiQueueLane.FOREGROUND_SVELTE) {
            pendingForegroundCount += 1;
        } else {
            pendingBackgroundCount += 1;
        }

        try {
            lifecycleSetup.accept(enqueuedTask);
        } catch (RuntimeException setupFailure) {
            removePendingTask(queuedTask);
            queuedTask.started.completeExceptionally(setupFailure);
            queuedTask.result.completeExceptionally(setupFailure);
            drain();
            throw setupFailure;
        }
        drain();
        return enqueuedTask;
    }

    /**
     * Cancels a pending or running task by ID.
     *
     * <p>Running work receives a thread interrupt through its executor future. Its concurrency
     * slot remains occupied until the supplier wrapper exits, preserving the configured parallelism
     * ceiling even when a supplier needs time to respond to interruption.</p>
     *
     * @return true when cancellation was claimed for a pending or running task
     */
    public synchronized boolean cancel(String taskId) {
        QueuedTask<?> queuedTask = pendingById.get(taskId);
        if (queuedTask != null) {
            removePendingTask(queuedTask);
            CancellationException cancellation = cancellationBeforeStart();
            queuedTask.started.completeExceptionally(cancellation);
            queuedTask.result.completeExceptionally(cancellation);
            drain();
            return true;
        }

        QueuedTask<?> runningTask = runningById.get(taskId);
        if (runningTask == null || runningTask.cancellationRequested) {
            return false;
        }
        runningTask.cancellationRequested = true;
        CancellationException cancellation = cancellationDuringExecution();
        runningTask.result.completeExceptionally(cancellation);
        Future<?> executionFuture = runningTask.executionFuture.orElseThrow(
            () -> new IllegalStateException("Running queue task has no execution future: " + taskId)
        );
        boolean interruptRequested = executionFuture.cancel(true);
        if (!runningTask.executionStarted) {
            releaseRunningSlot(runningTask);
        }
        return interruptRequested;
    }

    private boolean removePendingTask(QueuedTask<?> queuedTask) {
        if (pendingById.remove(queuedTask.id) == null) {
            return false;
        }
        TreeMap<Integer, Deque<QueuedTask<?>>> pendingByPriority = queuedTask.lane == BookAiQueueLane.FOREGROUND_SVELTE
            ? pendingForegroundByPriority
            : pendingBackgroundByPriority;
        Deque<QueuedTask<?>> priorityQueue = pendingByPriority.get(queuedTask.priority);
        if (priorityQueue != null) {
            priorityQueue.removeIf(task -> task.id.equals(queuedTask.id));
            if (priorityQueue.isEmpty()) {
                pendingByPriority.remove(queuedTask.priority);
            }
        }
        if (queuedTask.lane == BookAiQueueLane.FOREGROUND_SVELTE) {
            pendingForegroundCount = Math.max(0, pendingForegroundCount - 1);
        } else {
            pendingBackgroundCount = Math.max(0, pendingBackgroundCount - 1);
        }
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
            FutureTask<Void> executionFuture = new FutureTask<>(() -> {
                runTask(next);
                return null;
            });
            next.executionFuture = Optional.of(executionFuture);
            next.started.complete(null);
            if (runningById.containsKey(next.id) && !next.cancellationRequested) {
                executorService.execute(executionFuture);
            }
        }
    }

    private synchronized QueuedTask<?> shiftNext() {
        QueuedTask<?> foregroundTask = shiftNextFromLane(pendingForegroundByPriority);
        if (foregroundTask != null) {
            pendingForegroundCount = Math.max(0, pendingForegroundCount - 1);
            return foregroundTask;
        }

        QueuedTask<?> backgroundTask = shiftNextFromLane(pendingBackgroundByPriority);
        if (backgroundTask != null) {
            pendingBackgroundCount = Math.max(0, pendingBackgroundCount - 1);
            return backgroundTask;
        }
        return null;
    }

    private QueuedTask<?> shiftNextFromLane(TreeMap<Integer, Deque<QueuedTask<?>>> laneQueue) {
        Iterator<Map.Entry<Integer, Deque<QueuedTask<?>>>> iterator = laneQueue.entrySet().iterator();
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

    private <T> void runTask(QueuedTask<T> task) {
        if (!markExecutionStarted(task)) {
            return;
        }
        try {
            T supplierResult = task.supplier.get();
            finishSuccessfully(task, supplierResult);
        } catch (CancellationException cancellation) {
            finishExceptionally(task, cancellation);
        } catch (RuntimeException runtimeFailure) {
            finishExceptionally(task, runtimeFailure);
        } catch (Error fatalFailure) {
            finishExceptionally(task, fatalFailure);
            throw fatalFailure;
        }
    }

    private synchronized boolean markExecutionStarted(QueuedTask<?> task) {
        if (task.cancellationRequested || runningById.get(task.id) != task) {
            return false;
        }
        task.executionStarted = true;
        return true;
    }

    private <T> void finishSuccessfully(QueuedTask<T> task, T supplierResult) {
        boolean cancelled;
        synchronized (this) {
            cancelled = task.cancellationRequested;
            releaseRunningSlot(task);
        }
        if (cancelled) {
            task.result.completeExceptionally(cancellationDuringExecution());
            return;
        }
        task.result.complete(supplierResult);
    }

    private void finishExceptionally(QueuedTask<?> task, Throwable failure) {
        boolean cancelled;
        synchronized (this) {
            cancelled = task.cancellationRequested;
            releaseRunningSlot(task);
        }
        Throwable terminalFailure = cancelled ? cancellationDuringExecution() : failure;
        if (terminalFailure instanceof CancellationException) {
            log.debug("AI queue task cancelled [id={}, lane={}, priority={}]",
                task.id, task.lane, task.priority);
        } else {
            log.warn("AI queue task failed [id={}, lane={}, priority={}]",
                task.id, task.lane, task.priority, terminalFailure);
        }
        task.result.completeExceptionally(terminalFailure);
    }

    private synchronized void releaseRunningSlot(QueuedTask<?> task) {
        if (task.slotReleased) {
            return;
        }
        task.slotReleased = true;
        runningById.remove(task.id, task);
        runningCount = Math.max(0, runningCount - 1);
        drain();
    }

    private CancellationException cancellationBeforeStart() {
        return new CancellationException("Queue task cancelled before start");
    }

    private CancellationException cancellationDuringExecution() {
        return new CancellationException("Queue task cancelled during execution");
    }

    private static int coerceParallelism(int configuredParallelism) {
        if (configuredParallelism <= 0) {
            return 1;
        }
        return Math.min(configuredParallelism, MAX_ALLOWED_PARALLEL);
    }

    private static int coerceBackgroundPending(int configuredBackgroundPending) {
        return Math.max(1, configuredBackgroundPending);
    }

    private PositionLookup findPendingPosition(TreeMap<Integer, Deque<QueuedTask<?>>> queueByPriority,
                                               String taskId,
                                               int offsetSeed) {
        int offset = offsetSeed;
        for (Map.Entry<Integer, Deque<QueuedTask<?>>> entry : queueByPriority.entrySet()) {
            int index = 0;
            for (QueuedTask<?> task : entry.getValue()) {
                if (task.id.equals(taskId)) {
                    return new PositionLookup(true, offset + index + 1);
                }
                index += 1;
            }
            offset += entry.getValue().size();
        }
        return PositionLookup.notFound();
    }

    private record PositionLookup(boolean inQueue, Integer position) {
        private static PositionLookup notFound() {
            return new PositionLookup(false, null);
        }
    }

    private static final class QueuedTask<T> {
        private final String id;
        private final BookAiQueueLane lane;
        private final int priority;
        private final Supplier<T> supplier;
        private final CompletableFuture<Void> started;
        private final CompletableFuture<T> result;
        private Optional<Future<?>> executionFuture;
        private boolean executionStarted;
        private boolean cancellationRequested;
        private boolean slotReleased;

        private QueuedTask(String id,
                           BookAiQueueLane lane,
                           int priority,
                           Supplier<T> supplier,
                           CompletableFuture<Void> started,
                           CompletableFuture<T> result) {
            this.id = id;
            this.lane = lane;
            this.priority = priority;
            this.supplier = supplier;
            this.started = started;
            this.result = result;
            this.executionFuture = Optional.empty();
            this.executionStarted = false;
            this.cancellationRequested = false;
            this.slotReleased = false;
        }
    }

    /**
     * Point-in-time snapshot of queue depth and concurrency limits.
     *
     * @param running  number of tasks currently executing
     * @param pending  number of tasks waiting to start
     * @param maxParallel configured concurrency ceiling
     */
    public record QueueSnapshot(int running, int pending, int maxParallel) {
    }

    /**
     * A task's current position within the queue.
     *
     * @param inQueue     true when the task is still pending
     * @param position    one-based queue position, null when not pending
     * @param running     current running count
     * @param pending     current pending count
     * @param maxParallel configured concurrency ceiling
     */
    public record QueuePosition(boolean inQueue,
                                Integer position,
                                int running,
                                int pending,
                                int maxParallel) {
    }

    /**
     * Handle returned to callers after enqueue, providing lifecycle futures.
     *
     * @param id      unique task identifier
     * @param started completes when the task begins execution
     * @param result  completes with the task result or exception
     */
    public record EnqueuedTask<T>(String id,
                                  CompletableFuture<Void> started,
                                  CompletableFuture<T> result) {
    }
}
