package net.findmybook.application.cover;

import tools.jackson.databind.JsonNode;
import net.findmybook.exception.CoverProcessingException;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.service.GoogleApiFetcher;
import net.findmybook.service.image.S3BookCoverService;
import net.findmybook.support.cover.CoverImageUrlSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
 * {@link S3BookCoverService} which handles processing, grayscale detection,
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
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final int MAX_RETRIES_PER_API = 3;
    private static final int CONSECUTIVE_FAILURES_PAUSE_THRESHOLD = 5;
    private static final Duration API_PAUSE_DURATION = Duration.ofMinutes(10);

    // ── Source labels ───────────────────────────────────────────────────
    private static final String SRC_OPEN_LIBRARY = "OPEN_LIBRARY";
    private static final String SRC_GOOGLE_BOOKS = "GOOGLE_BOOKS";
    private static final String SRC_LONGITOOD = "LONGITOOD";

    /** Selects which candidate books a backfill run should target. */
    public enum BackfillMode { MISSING, GRAYSCALE, REJECTED }

    /**
     * Represents the latest backfill execution state used by admin status polling.
     * Carries aggregate counters plus current/last per-book source attempt diagnostics.
     */
    public record BackfillProgress(
        int totalCandidates,
        int processed,
        int coverFound,
        int noCoverFound,
        boolean running,
        String currentBookId,
        String currentBookTitle,
        String currentBookIsbn,
        List<SourceAttemptStatus> currentBookAttempts,
        String lastCompletedBookId,
        String lastCompletedBookTitle,
        String lastCompletedBookIsbn,
        Boolean lastCompletedBookFound,
        List<SourceAttemptStatus> lastCompletedBookAttempts
    ) {
        public BackfillProgress {
            currentBookAttempts = currentBookAttempts == null ? List.of() : List.copyOf(currentBookAttempts);
            lastCompletedBookAttempts = lastCompletedBookAttempts == null
                ? List.of()
                : List.copyOf(lastCompletedBookAttempts);
        }
    }

    record BackfillCandidate(UUID id, String title, String isbn13, String isbn10) {
        String preferredIsbn() {
            return StringUtils.hasText(isbn13) ? isbn13 : isbn10;
        }

        String displayTitle() {
            return StringUtils.hasText(title) ? title : "<untitled>";
        }
    }

    enum SourceAttemptOutcome {
        SUCCESS,
        NOT_FOUND,
        FAILURE,
        SKIPPED
    }

    /**
     * Captures a single source attempt outcome for status visibility and debugging.
     */
    public record SourceAttemptStatus(
        String source,
        String outcome,
        String detail,
        Instant attemptedAt
    ) {}

    record SourceAttemptResult(SourceAttemptOutcome outcome, String detail) {
        static SourceAttemptResult success(String detail) {
            return new SourceAttemptResult(SourceAttemptOutcome.SUCCESS, detail);
        }

        static SourceAttemptResult notFound(String detail) {
            return new SourceAttemptResult(SourceAttemptOutcome.NOT_FOUND, detail);
        }

        static SourceAttemptResult failure(String detail) {
            return new SourceAttemptResult(SourceAttemptOutcome.FAILURE, detail);
        }

        static SourceAttemptResult skipped(String detail) {
            return new SourceAttemptResult(SourceAttemptOutcome.SKIPPED, detail);
        }

        boolean success() {
            return outcome == SourceAttemptOutcome.SUCCESS;
        }
    }

    record ProcessBookResult(boolean coverFound, List<SourceAttemptStatus> attempts) {
        ProcessBookResult {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final S3BookCoverService s3BookCoverService;
    private final GoogleApiFetcher googleApiFetcher;
    private final WebClient webClient;

    private final AtomicReference<BackfillProgress> progress = new AtomicReference<>(
        new BackfillProgress(
            0,
            0,
            0,
            0,
            false,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            List.of()
        )
    );
    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private volatile boolean cancelled;

    /** Per-source consecutive failure counter; reset on success. */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    /** Per-source pause-until timestamp. */
    private final Map<String, Instant> pausedUntil = new ConcurrentHashMap<>();

    public CoverBackfillService(JdbcTemplate jdbcTemplate,
                                S3BookCoverService s3BookCoverService,
                                GoogleApiFetcher googleApiFetcher,
                                WebClient.Builder webClientBuilder) {
        this.jdbcTemplate = jdbcTemplate;
        this.s3BookCoverService = s3BookCoverService;
        this.googleApiFetcher = googleApiFetcher;
        this.webClient = webClientBuilder.build();
    }

    /** Returns the latest aggregate backfill progress snapshot for admin status polling. */
    public BackfillProgress getProgress() {
        return progress.get();
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

        try {
        cancelled = false;
        consecutiveFailures.clear();
        pausedUntil.clear();

        List<BackfillCandidate> candidates = queryCandidates(mode, limit > 0 ? limit : 10_000);
        log.info("Cover backfill starting: mode={}, candidates={}", mode, candidates.size());
        if (candidates.isEmpty()) {
            log.info("Cover backfill has no candidates for mode={}; verify candidate query and ISBN availability.", mode);
        }
        updateProgress(candidates.size(), 0, 0, 0, true, null, List.of(), null, null, List.of());

        int processed = 0, found = 0, notFound = 0;
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
            updateProgress(
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
                currentAttempts -> updateProgress(
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
            updateProgress(
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
        updateProgress(
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
        } finally {
            backfillRunning.set(false);
        }
    }

    // ── Candidate queries ───────────────────────────────────────────────

    private List<BackfillCandidate> queryCandidates(BackfillMode mode, int limit) {
        String sql = switch (mode) {
            case MISSING -> """
                SELECT b.id, b.title, b.isbn13, b.isbn10
                FROM books b
                WHERE (b.isbn13 IS NOT NULL OR b.isbn10 IS NOT NULL)
                  AND NOT EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NULL
                        AND ((bil.url IS NOT NULL AND bil.url <> '')
                             OR (bil.s3_image_path IS NOT NULL AND bil.s3_image_path <> ''))
                  )
                ORDER BY b.created_at DESC
                LIMIT ?
                """;
            case GRAYSCALE -> """
                SELECT b.id, b.title, b.isbn13, b.isbn10
                FROM books b
                WHERE (b.isbn13 IS NOT NULL OR b.isbn10 IS NOT NULL)
                  AND EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id AND bil.is_grayscale = true
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NULL
                        AND COALESCE(bil.is_grayscale, false) = false
                        AND ((bil.url IS NOT NULL AND bil.url <> '')
                             OR (bil.s3_image_path IS NOT NULL AND bil.s3_image_path <> ''))
                  )
                ORDER BY b.created_at DESC
                LIMIT ?
                """;
            case REJECTED -> """
                SELECT b.id, b.title, b.isbn13, b.isbn10
                FROM books b
                WHERE (b.isbn13 IS NOT NULL OR b.isbn10 IS NOT NULL)
                  AND EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NOT NULL
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM book_image_links bil
                      WHERE bil.book_id = b.id
                        AND bil.download_error IS NULL
                        AND bil.s3_image_path IS NOT NULL
                        AND bil.s3_image_path <> ''
                  )
                ORDER BY b.created_at DESC
                LIMIT ?
                """;
        };

        return jdbcTemplate.query(sql, (rs, rowNum) -> new BackfillCandidate(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("isbn13"),
            rs.getString("isbn10")
        ), limit);
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
            trySource(SRC_OPEN_LIBRARY, bookId, title, isbn, () -> tryOpenLibrary(isbn, bookId));
        recordAttempt(attempts, SRC_OPEN_LIBRARY, openLibraryResult);
        attemptPublisher.accept(List.copyOf(attempts));
        if (openLibraryResult.success()) {
            return new ProcessBookResult(true, attempts);
        }
        sleepSafely(API_CALL_INTERVAL);

        SourceAttemptResult googleBooksResult =
            trySource(SRC_GOOGLE_BOOKS, bookId, title, isbn, () -> tryGoogleBooks(isbn, bookId));
        recordAttempt(attempts, SRC_GOOGLE_BOOKS, googleBooksResult);
        attemptPublisher.accept(List.copyOf(attempts));
        if (googleBooksResult.success()) {
            return new ProcessBookResult(true, attempts);
        }
        sleepSafely(API_CALL_INTERVAL);

        SourceAttemptResult longitoodResult =
            trySource(SRC_LONGITOOD, bookId, title, isbn, () -> tryLongitood(isbn, bookId));
        recordAttempt(attempts, SRC_LONGITOOD, longitoodResult);
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
        java.util.function.Supplier<SourceAttemptResult> attempt
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
            String detail = "failure: unexpected exception while invoking source: " + summarizeThrowable(ex);
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

    // ── Open Library ────────────────────────────────────────────────────

    private SourceAttemptResult tryOpenLibrary(String isbn, String bookId) {
        String url = "https://covers.openlibrary.org/b/isbn/" + isbn + "-L.jpg?default=false";
        return tryUploadWithRetry(url, bookId, SRC_OPEN_LIBRARY);
    }

    // ── Google Books ────────────────────────────────────────────────────

    private SourceAttemptResult tryGoogleBooks(String isbn, String bookId) {
        try {
            JsonNode response = googleApiFetcher
                .searchVolumesAuthenticated("isbn:" + isbn, 0, "relevance", null, 1)
                .onErrorResume(e -> googleApiFetcher.searchVolumesUnauthenticated(
                    "isbn:" + isbn, 0, "relevance", null, 1))
                .block(Duration.ofSeconds(15));

            if (response == null || !response.has("items") || !response.get("items").isArray()
                    || response.get("items").isEmpty()) {
                return SourceAttemptResult.notFound("google-books: no volumes matched isbn query");
            }

            JsonNode volumeInfo = response.get("items").get(0).path("volumeInfo");
            JsonNode imageLinks = volumeInfo.path("imageLinks");
            if (imageLinks.isMissingNode() || imageLinks.isEmpty()) {
                return SourceAttemptResult.notFound("google-books: matched volume has no imageLinks");
            }

            Map<String, String> links = new HashMap<>();
            imageLinks.properties().forEach(entry ->
                links.put(entry.getKey(), entry.getValue().asString()));

            String bestUrl = CoverImageUrlSelector.selectPreferredImageUrl(links);
            if (!StringUtils.hasText(bestUrl)) {
                return SourceAttemptResult.notFound("google-books: imageLinks did not provide a usable URL");
            }

            bestUrl = upgradeGoogleBooksImageUrl(bestUrl);
            return tryUploadWithRetry(bestUrl, bookId, SRC_GOOGLE_BOOKS);
        } catch (RuntimeException ex) {
            return SourceAttemptResult.failure("google-books search failed: " + summarizeThrowable(ex));
        }
    }

    /**
     * Upgrades a Google Books thumbnail URL for maximum resolution:
     * zoom=0 returns the largest available image, edge=curl is cosmetic noise.
     */
    static String upgradeGoogleBooksImageUrl(String url) {
        if (url == null) return null;
        String upgraded = url.replaceAll("zoom=\\d+", "zoom=0")
                             .replace("&edge=curl", "")
                             .replace("?edge=curl&", "?");
        if (upgraded.startsWith("http://")) {
            upgraded = "https://" + upgraded.substring(7);
        }
        return upgraded;
    }

    // ── Longitood ───────────────────────────────────────────────────────

    private SourceAttemptResult tryLongitood(String isbn, String bookId) {
        try {
            Map<String, String> response = webClient.get()
                .uri("https://bookcover.longitood.com/bookcover/" + isbn)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                .block(Duration.ofSeconds(10));

            if (response == null || !StringUtils.hasText(response.get("url"))) {
                return SourceAttemptResult.notFound("longitood: response did not include cover URL");
            }
            return tryUploadWithRetry(response.get("url"), bookId, SRC_LONGITOOD);
        } catch (WebClientResponseException.NotFound ex) {
            return SourceAttemptResult.notFound("longitood: no cover found (404)");
        } catch (RuntimeException ex) {
            return SourceAttemptResult.failure("longitood request failed: " + summarizeThrowable(ex));
        }
    }

    // ── Upload with retry ───────────────────────────────────────────────

    private SourceAttemptResult tryUploadWithRetry(String imageUrl, String bookId, String source) {
        for (int attempt = 0; attempt <= MAX_RETRIES_PER_API; attempt++) {
            try {
                ImageDetails result = s3BookCoverService
                    .uploadCoverToS3Async(imageUrl, bookId, source)
                    .block(Duration.ofSeconds(30));
                if (result != null) {
                    return SourceAttemptResult.success("uploaded from " + source + " using " + imageUrl);
                }
                return SourceAttemptResult.failure("upload returned empty details from " + source + " using " + imageUrl);
            } catch (RuntimeException ex) {
                String msg = summarizeThrowable(ex);
                if (isRateLimited(ex) && attempt < MAX_RETRIES_PER_API) {
                    Duration backoff = calculateBackoff(attempt);
                    log.info("Rate limited by {} for book {}, backing off {}s (attempt {}/{})",
                        source, bookId, backoff.toSeconds(), attempt + 1, MAX_RETRIES_PER_API);
                    sleepSafely(backoff);
                    continue;
                }
                if (isNotFoundResponse(ex) || isLikelyNoCoverImageFailure(ex)) {
                    return SourceAttemptResult.notFound("no usable cover content from " + source + " (" + msg + ")");
                }
                return SourceAttemptResult.failure("upload failed from " + source + ": " + msg);
            }
        }
        return SourceAttemptResult.failure("rate-limit retries exhausted for " + source + " (" + imageUrl + ")");
    }

    private static boolean isRateLimited(Throwable ex) {
        if (containsHttpStatus(ex, 429)) return true;
        String msg = summarizeThrowable(ex);
        return msg != null && (msg.contains("rate-limited") || msg.contains("rate limit")
            || msg.contains("RateLimiter"));
    }

    static boolean isNotFoundResponse(Throwable ex) {
        return containsHttpStatus(ex, 404);
    }

    static boolean isLikelyNoCoverImageFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof CoverProcessingException cpe && cpe.isNoCoverAvailable()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsHttpStatus(Throwable throwable, int statusCode) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException
                && responseException.getStatusCode().value() == statusCode) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static String summarizeThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return throwable != null ? throwable.getClass().getSimpleName() : "unknown";
    }

    private static Duration calculateBackoff(int attempt) {
        long millis = INITIAL_BACKOFF.toMillis() * (1L << attempt);
        return Duration.ofMillis(Math.min(millis, MAX_BACKOFF.toMillis()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

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

    private void updateProgress(int total,
                                int processed,
                                int found,
                                int notFound,
                                boolean running,
                                BackfillCandidate currentBook,
                                List<SourceAttemptStatus> currentBookAttempts,
                                BackfillCandidate lastCompletedBook,
                                Boolean lastCompletedBookFound,
                                List<SourceAttemptStatus> lastCompletedBookAttempts) {
        progress.set(new BackfillProgress(
            total,
            processed,
            found,
            notFound,
            running,
            currentBook == null ? null : currentBook.id().toString(),
            currentBook == null ? null : currentBook.displayTitle(),
            currentBook == null ? null : currentBook.preferredIsbn(),
            currentBookAttempts,
            lastCompletedBook == null ? null : lastCompletedBook.id().toString(),
            lastCompletedBook == null ? null : lastCompletedBook.displayTitle(),
            lastCompletedBook == null ? null : lastCompletedBook.preferredIsbn(),
            lastCompletedBookFound,
            lastCompletedBookAttempts
        ));
    }
}
