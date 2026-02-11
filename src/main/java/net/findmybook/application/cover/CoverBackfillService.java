package net.findmybook.application.cover;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Batch-fetches cover images from external APIs for books that lack
 * a valid (or color) cover. Operates in two modes:
 * <ul>
 *   <li>{@code MISSING} — books with no usable cover at all</li>
 *   <li>{@code GRAYSCALE} — books whose only covers are grayscale</li>
 * </ul>
 *
 * <p>Tries sources in order: Open Library → Google Books → Longitood,
 * stopping at the first success per book. Downloaded images flow through
 * {@link net.findmybook.service.image.S3BookCoverService} which handles processing, grayscale detection,
 * S3 upload, and persistence.</p>
 */
@Service
public class CoverBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CoverBackfillService.class);

    // ── Pacing ──────────────────────────────────────────────────────────
    private static final Duration API_CALL_INTERVAL = Duration.ofSeconds(6);
    private static final Duration INTER_BOOK_DELAY = Duration.ofSeconds(2);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final Duration BATCH_COOLDOWN = Duration.ofSeconds(60);

    // ── Backoff ─────────────────────────────────────────────────────────
    private static final int CONSECUTIVE_FAILURES_PAUSE_THRESHOLD = 5;
    private static final Duration API_PAUSE_DURATION = Duration.ofMinutes(10);

    private final BackfillCandidateQuery candidateQuery;
    private final CoverSourceFetcher sourceFetcher;
    private final BackfillProgressTracker progressTracker;

    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private volatile boolean cancelled;

    /** Per-source consecutive failure counter; reset on success. */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    /** Per-source pause-until timestamp. */
    private final Map<String, Instant> pausedUntil = new ConcurrentHashMap<>();

    public CoverBackfillService(BackfillCandidateQuery candidateQuery,
                                CoverSourceFetcher sourceFetcher,
                                BackfillProgressTracker progressTracker) {
        this.candidateQuery = candidateQuery;
        this.sourceFetcher = sourceFetcher;
        this.progressTracker = progressTracker;
    }

    /** Returns the latest aggregate backfill progress snapshot for admin status polling. */
    public BackfillProgress getProgress() {
        return progressTracker.getProgress();
    }

    /**
     * Indicates whether a backfill run is currently executing.
     * Thread-safe: backed by an {@link AtomicBoolean} that is set atomically
     * at the start of {@link #runBackfill} and cleared in its {@code finally} block.
     */
    public boolean isRunning() {
        return backfillRunning.get();
    }

    /** Requests graceful cancellation of the current backfill run. Thread-safe. */
    public void cancel() {
        cancelled = true;
        log.info("Cover backfill cancellation requested");
    }

    /**
     * Runs the backfill asynchronously on the shared task executor.
     *
     * @param mode  which books to target
     * @param limit maximum number of candidates to process (0 = unlimited)
     */
    @Async("taskExecutor")
    public void runBackfill(BackfillMode mode, int limit) {
        if (!backfillRunning.compareAndSet(false, true)) {
            log.warn("Cover backfill already running; ignoring new request (mode={}, limit={})", mode, limit);
            return;
        }

        int processed = 0;
        try {
            cancelled = false;
            consecutiveFailures.clear();
            pausedUntil.clear();

            List<BackfillCandidate> candidates = candidateQuery.queryCandidates(mode, limit > 0 ? limit : 10_000);
            log.info("Cover backfill starting: mode={}, candidates={}", mode, candidates.size());
            if (candidates.isEmpty()) {
                log.info("Cover backfill has no candidates for mode={}; verify candidate query and ISBN availability.", mode);
            }
            progressTracker.reset(candidates.size());

            int found = 0, notFound = 0;
            BackfillCandidate lastCompletedCandidate = null;
            Boolean lastCompletedFound = null;
            List<SourceAttemptStatus> lastCompletedAttempts = List.of();

            for (int i = 0; i < candidates.size(); i++) {
                if (cancelled) {
                    log.info("Cover backfill cancelled after {} books", processed);
                    break;
                }

                // Batch cooldown
                if (i > 0 && i % DEFAULT_BATCH_SIZE == 0) {
                    log.info("Batch cooldown after {} books ({} covers found)", processed, found);
                    sleepSafely(BATCH_COOLDOWN);
                }

                BackfillCandidate candidate = candidates.get(i);
                BackfillCandidate previousCompletedCandidate = lastCompletedCandidate;
                Boolean previousCompletedFound = lastCompletedFound;
                List<SourceAttemptStatus> previousCompletedAttempts = lastCompletedAttempts;
                int processedBeforeBook = processed;
                int foundBeforeBook = found;
                int notFoundBeforeBook = notFound;

                progressTracker.updateProgress(
                    candidates.size(),
                    processedBeforeBook,
                    foundBeforeBook,
                    notFoundBeforeBook,
                    true,
                    candidate,
                    List.of(),
                    previousCompletedCandidate,
                    previousCompletedFound,
                    previousCompletedAttempts
                );

                ProcessBookResult processResult = processBook(
                    candidate,
                    currentAttempts -> progressTracker.updateProgress(
                        candidates.size(),
                        processedBeforeBook,
                        foundBeforeBook,
                        notFoundBeforeBook,
                        true,
                        candidate,
                        currentAttempts,
                        previousCompletedCandidate,
                        previousCompletedFound,
                        previousCompletedAttempts
                    )
                );

                boolean success = processResult.coverFound();
                processed++;
                if (success) {
                    found++;
                } else {
                    notFound++;
                }
                lastCompletedCandidate = candidate;
                lastCompletedFound = success;
                lastCompletedAttempts = processResult.attempts();

                progressTracker.updateProgress(
                    candidates.size(),
                    processed,
                    found,
                    notFound,
                    true,
                    null,
                    List.of(),
                    lastCompletedCandidate,
                    lastCompletedFound,
                    lastCompletedAttempts
                );

                if (i < candidates.size() - 1) {
                    sleepSafely(INTER_BOOK_DELAY);
                }
            }

            log.info("Cover backfill complete: processed={}, found={}, notFound={}", processed, found, notFound);
            progressTracker.updateProgress(
                candidates.size(),
                processed,
                found,
                notFound,
                false,
                null,
                List.of(),
                lastCompletedCandidate,
                lastCompletedFound,
                lastCompletedAttempts
            );
        } catch (RuntimeException unexpectedException) {
            log.error("Cover backfill failed unexpectedly: mode={}, limit={}, processed={}", mode, limit, processed, unexpectedException);
            progressTracker.updateProgress(0, processed, 0, 0, false, null, List.of(), null, null, List.of());
            throw unexpectedException;
        } finally {
            backfillRunning.set(false);
        }
    }

    // ── Per-book processing ─────────────────────────────────────────────

    private ProcessBookResult processBook(BackfillCandidate candidate, Consumer<List<SourceAttemptStatus>> attemptPublisher) {
        String isbn = candidate.preferredIsbn();
        String bookId = candidate.id().toString();
        String title = candidate.displayTitle();
        List<SourceAttemptStatus> attempts = new ArrayList<>();
        if (!StringUtils.hasText(isbn)) {
            log.info("Skipping cover backfill for book {} title=\"{}\" because ISBN is missing", bookId, title);
            SourceAttemptResult skippedResult = SourceAttemptResult.skipped("local validation: missing ISBN");
            recordAttempt(attempts, "LOCAL_VALIDATION", skippedResult);
            attemptPublisher.accept(List.copyOf(attempts));
            return new ProcessBookResult(false, attempts);
        }

        log.info("Cover backfill started for book {} title=\"{}\" isbn={}", bookId, title, isbn);

        // Try sources in priority order, sleeping between attempts.
        SourceAttemptResult openLibraryResult =
            trySource(CoverSourceFetcher.SRC_OPEN_LIBRARY, bookId, title, isbn, () -> sourceFetcher.tryOpenLibrary(isbn, bookId));
        recordAttempt(attempts, CoverSourceFetcher.SRC_OPEN_LIBRARY, openLibraryResult);
        attemptPublisher.accept(List.copyOf(attempts));
        if (openLibraryResult.success()) {
            return new ProcessBookResult(true, attempts);
        }
        sleepSafely(API_CALL_INTERVAL);

        SourceAttemptResult googleBooksResult =
            trySource(CoverSourceFetcher.SRC_GOOGLE_BOOKS, bookId, title, isbn, () -> sourceFetcher.tryGoogleBooks(isbn, bookId));
        recordAttempt(attempts, CoverSourceFetcher.SRC_GOOGLE_BOOKS, googleBooksResult);
        attemptPublisher.accept(List.copyOf(attempts));
        if (googleBooksResult.success()) {
            return new ProcessBookResult(true, attempts);
        }
        sleepSafely(API_CALL_INTERVAL);

        SourceAttemptResult longitoodResult =
            trySource(CoverSourceFetcher.SRC_LONGITOOD, bookId, title, isbn, () -> sourceFetcher.tryLongitood(isbn, bookId));
        recordAttempt(attempts, CoverSourceFetcher.SRC_LONGITOOD, longitoodResult);
        attemptPublisher.accept(List.copyOf(attempts));
        if (longitoodResult.success()) {
            return new ProcessBookResult(true, attempts);
        }

        log.info("No cover found for book {} title=\"{}\" isbn={}. OpenLibrary={}, GoogleBooks={}, Longitood={}",
            bookId,
            title,
            isbn,
            openLibraryResult.detail(),
            googleBooksResult.detail(),
            longitoodResult.detail());
        return new ProcessBookResult(false, attempts);
    }

    /**
     * Wraps an API attempt with pause-checking and consecutive-failure tracking.
     */
    private SourceAttemptResult trySource(
        String source,
        String bookId,
        String title,
        String isbn,
        Supplier<SourceAttemptResult> attempt
    ) {
        Instant pauseEnd = pausedUntil.get(source);
        if (pauseEnd != null && Instant.now().isBefore(pauseEnd)) {
            String detail = "skipped: source paused until " + pauseEnd;
            log.info("Cover backfill {} for book {} title=\"{}\" isbn={} from {}",
                detail, bookId, title, isbn, source);
            return SourceAttemptResult.skipped(detail);
        }

        log.info("Trying {} cover API for book {} title=\"{}\" isbn={}", source, bookId, title, isbn);

        SourceAttemptResult result;
        try {
            result = attempt.get();
        } catch (RuntimeException ex) {
            log.error("Unexpected exception from {} for book {} isbn={}: {}", source, bookId, isbn, ex.getMessage(), ex);
            String detail = "failure: unexpected exception while invoking source: " + CoverSourceFetcher.summarizeThrowable(ex);
            result = SourceAttemptResult.failure(detail);
        }

        switch (result.outcome()) {
            case SUCCESS -> {
                consecutiveFailures.put(source, 0);
                log.info("{} succeeded for book {} title=\"{}\" isbn={}: {}",
                    source, bookId, title, isbn, result.detail());
            }
            case NOT_FOUND -> {
                consecutiveFailures.put(source, 0);
                log.info("{} returned no usable cover for book {} title=\"{}\" isbn={}: {}",
                    source, bookId, title, isbn, result.detail());
            }
            case FAILURE -> {
                int failures = consecutiveFailures.merge(source, 1, Integer::sum);
                log.warn("{} failed for book {} title=\"{}\" isbn={} (consecutiveFailures={}): {}",
                    source, bookId, title, isbn, failures, result.detail());
                if (failures >= CONSECUTIVE_FAILURES_PAUSE_THRESHOLD) {
                    pausedUntil.put(source, Instant.now().plus(API_PAUSE_DURATION));
                    consecutiveFailures.put(source, 0);
                    log.warn("{} paused for {} after {} consecutive failures",
                        source, API_PAUSE_DURATION, failures);
                }
            }
            case SKIPPED -> log.info("{} skipped for book {} title=\"{}\" isbn={}: {}",
                source, bookId, title, isbn, result.detail());
        }
        return result;
    }

    private void sleepSafely(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelled = true;
            log.info("Cover backfill sleep interrupted; treating as cancellation");
        }
    }

    private static void recordAttempt(List<SourceAttemptStatus> attempts, String source, SourceAttemptResult result) {
        attempts.add(new SourceAttemptStatus(source, result.outcome().name(), result.detail(), Instant.now()));
    }
}
