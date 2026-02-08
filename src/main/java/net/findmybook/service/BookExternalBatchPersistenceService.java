package net.findmybook.service;

import jakarta.annotation.PreDestroy;
import net.findmybook.dto.BookAggregate;
import net.findmybook.model.Book;
import net.findmybook.support.retry.AdvisoryLockRetrySupport;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.util.ExternalApiLogger;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.SlugGenerator;
import net.findmybook.util.UrlUtils;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.SocketException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persists batches of externally sourced books into Postgres via the canonical upsert service.
 */
@Service
class BookExternalBatchPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(BookExternalBatchPersistenceService.class);

    private static final String ALPHANUMERIC_ONLY_PATTERN = "[^a-z0-9]";

    private static final String ERR_CONNECTION = "connection";
    private static final String ERR_REFUSED = "refused";
    private static final String ERR_CLOSED = "closed";
    private static final String ERR_RESET = "reset";
    private static final String ERR_TIMEOUT = "timeout";
    private static final String ERR_AUTH_FAILED = "authentication failed";
    private static final String ERR_TOO_MANY_CONNECTIONS = "too many connections";
    private static final String ERR_DATABASE = "database";
    private static final String ERR_DOES_NOT_EXIST = "does not exist";

    private final ObjectMapper objectMapper;
    private final GoogleBooksMapper googleBooksMapper;
    private final BookUpsertService bookUpsertService;
    private volatile boolean shutdownInProgress = false;

    BookExternalBatchPersistenceService(ObjectMapper objectMapper, GoogleBooksMapper googleBooksMapper, BookUpsertService bookUpsertService) {
        this.objectMapper = objectMapper;
        this.googleBooksMapper = googleBooksMapper;
        this.bookUpsertService = bookUpsertService;
    }

    void persistBooksAsync(List<Book> books, String context, Runnable searchViewRefreshTrigger) {
        if (shutdownInProgress) {
            logger.info("[EXTERNAL-API] [{}] Skipping async persistence because shutdown is in progress", context);
            return;
        }
        if (books == null) {
            throw new IllegalArgumentException("persistBooksAsync requires a non-null books list (context=" + context + ")");
        }
        if (books.isEmpty()) {
            logger.debug("[EXTERNAL-API] [{}] persistBooksAsync called with empty books list; nothing to persist", context);
            return;
        }

        int originalSize = books.size();
        logger.info("[EXTERNAL-API] [{}] persistBooksAsync INVOKED with {} books", context, originalSize);

        List<Book> uniqueBooks = filterDuplicatesById(books);
        int duplicateCount = originalSize - uniqueBooks.size();
        if (duplicateCount > 0) {
            logger.info("[EXTERNAL-API] [{}] Filtered {} duplicate book(s) by ID, {} candidates remain", context, duplicateCount, uniqueBooks.size());
        }

        if (uniqueBooks.isEmpty()) {
            logger.warn("[EXTERNAL-API] [{}] No valid books to persist after deduplication", context);
            return;
        }

        List<Book> dedupedByIdentifiers = deduplicateByIdentifiers(uniqueBooks);
        int identifierDuplicateCount = uniqueBooks.size() - dedupedByIdentifiers.size();
        if (identifierDuplicateCount > 0) {
            logger.info("[EXTERNAL-API] [{}] Removed {} duplicate book(s) by ISBN/title after ID filtering (final count={})",
                context, identifierDuplicateCount, dedupedByIdentifiers.size());
        }

        Mono.fromRunnable(() -> executeBatchPersistence(dedupedByIdentifiers, context, searchViewRefreshTrigger))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSubscribe(sub -> logger.info("[EXTERNAL-API] [{}] Mono subscribed for persistence", context))
            .subscribe(
                ignored -> logger.info("[EXTERNAL-API] [{}] Persistence Mono completed", context),
                error -> logger.error("[EXTERNAL-API] [{}] Background persistence failed: {}", context, error.getMessage(), error)
            );

        logger.info("[EXTERNAL-API] [{}] persistBooksAsync setup complete, async execution scheduled", context);
    }

    private void executeBatchPersistence(List<Book> books, String context, Runnable searchViewRefreshTrigger) {
        logger.info("[EXTERNAL-API] [{}] Persisting {} unique books to Postgres", context, books.size());
        long start = System.currentTimeMillis();

        BatchResult result = processBooks(books, context, searchViewRefreshTrigger);

        long elapsed = System.currentTimeMillis() - start;
        if (result.failureCount() > 0) {
            String summary = String.format("[EXTERNAL-API] [%s] Batch persistence completed with failures: %d succeeded, %d failed (%d ms)",
                context, result.successCount(), result.failureCount(), elapsed);
            if (shutdownInProgress) {
                logger.info("[EXTERNAL-API] [{}] {} (suppressed during shutdown)", context, summary);
                return;
            }
            logger.warn(summary);
            return;
        }
        logger.info("[EXTERNAL-API] [{}] Batch persistence complete: {} succeeded ({} ms)", context, result.successCount(), elapsed);
    }

    private record BatchResult(int successCount, int failureCount) {}

    private BatchResult processBooks(List<Book> books, String context, Runnable searchViewRefreshTrigger) {
        int successCount = 0;
        int failureCount = 0;

        for (Book book : books) {
            if (shutdownInProgress) {
                logger.info("[EXTERNAL-API] [{}] Shutdown in progress; ending batch early ({} succeeded, {} failed)",
                    context, successCount, failureCount);
                break;
            }
            if (isInvalidBook(book, context)) {
                continue;
            }

            PersistenceOutcome outcome = attemptPersistBook(book, context, searchViewRefreshTrigger);
            successCount += outcome.successes();
            failureCount += outcome.failures();

            if (outcome.shouldAbort()) {
                if (shutdownInProgress) {
                    logger.info("[EXTERNAL-API] [{}] Stopping batch due to systemic error during shutdown ({} succeeded, {} failed)",
                        context, successCount, failureCount);
                    break;
                }
                throw new IllegalStateException(
                    "Systemic database error during batch persistence (" + successCount + " succeeded, " + failureCount + " failed before abort)");
            }
        }

        return new BatchResult(successCount, failureCount);
    }

    private boolean isInvalidBook(Book book, String context) {
        if (book == null || book.getId() == null) {
            logger.warn("[EXTERNAL-API] [{}] Skipping book with null reference or null ID: title={}",
                context, book != null ? book.getTitle() : "null");
            return true;
        }
        return false;
    }

    private record PersistenceOutcome(int successes, int failures, boolean shouldAbort) {}

    private PersistenceOutcome attemptPersistBook(Book book, String context, Runnable searchViewRefreshTrigger) {
        try {
            boolean persisted = persistSingleBook(book, context, searchViewRefreshTrigger);
            return persisted
                ? new PersistenceOutcome(1, 0, false)
                : new PersistenceOutcome(0, 1, false);
        } catch (RuntimeException ex) {
            if (shutdownInProgress && isSystemicDatabaseError(ex)) {
                logger.info("[EXTERNAL-API] [{}] Skipping book {} during shutdown after systemic error: {}",
                    context, book.getId(), ex.getMessage());
                return new PersistenceOutcome(0, 0, true);
            }
            if (isSystemicDatabaseError(ex)) {
                logger.error("[EXTERNAL-API] [{}] Aborting batch due to systemic database error while persisting book {}: {}",
                    context,
                    book.getId(),
                    ex.getMessage(),
                    ex);
                return new PersistenceOutcome(0, 1, true);
            }
            logger.warn("[EXTERNAL-API] [{}] Skipping book {} due to non-systemic persistence failure: {}",
                context,
                book.getId(),
                ex.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[EXTERNAL-API] [{}] Non-systemic persistence failure stack trace for book {}",
                    context,
                    book.getId(),
                    ex);
            }
            return new PersistenceOutcome(0, 1, false);
        }
    }

    private boolean persistSingleBook(Book book, String context, Runnable searchViewRefreshTrigger) {
        ExternalApiLogger.logHydrationStart(logger, context, book.getId(), context);

        JsonNode bookJson;
        if (book.getRawJsonResponse() != null && !book.getRawJsonResponse().isBlank()) {
            bookJson = objectMapper.readTree(book.getRawJsonResponse());
        } else {
            bookJson = objectMapper.valueToTree(book);
        }

        boolean ok = persistBook(book, bookJson, searchViewRefreshTrigger);
        if (ok) {
            ExternalApiLogger.logHydrationSuccess(logger, context, book.getId(), book.getId(), "POSTGRES_UPSERT");
        }
        return ok;
    }

    boolean persistBook(Book book, JsonNode sourceJson, Runnable searchViewRefreshTrigger) {
        String bookIdForLogging = book != null ? book.getId() : "null";
        try {
            if (book == null) {
                throw new IllegalArgumentException("persistBook invoked with null book reference");
            }

            BookAggregate aggregate = googleBooksMapper.map(sourceJson);
            if (aggregate == null) {
                logger.warn("GoogleBooksMapper returned null for book {}; attempting fallback aggregate", book.getId());
                aggregate = buildFallbackAggregate(book);
                if (aggregate == null) {
                    throw new IllegalStateException("Unable to map external payload for book " + book.getId());
                }
            }
            BookAggregate aggregateToPersist = aggregate;

            AdvisoryLockRetrySupport.execute(
                AdvisoryLockRetrySupport.RetryConfig.forBookUpsert(logger),
                "book upsert " + book.getId(),
                () -> bookUpsertService.upsert(aggregateToPersist)
            );
            if (searchViewRefreshTrigger != null) {
                searchViewRefreshTrigger.run();
            }
            logger.debug("Persisted book via BookUpsertService: {}", book.getId());
            return true;
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException ex) {
            if (isSystemicDatabaseError(ex)) {
                if (shutdownInProgress) {
                    logger.info("Skipping persistence for book {} during shutdown because database connectivity is unavailable",
                        bookIdForLogging);
                    return false;
                }
                if (ex instanceof IllegalStateException stateException) {
                    throw stateException;
                }
                throw new IllegalStateException("Systemic database error during upsert", ex);
            }
            logger.warn("Skipping persistence for book {} due to non-systemic upsert failure: {}",
                bookIdForLogging,
                ex.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("Non-systemic upsert failure stack trace for book {}", bookIdForLogging, ex);
            }
            return false;
        }
    }

    @PreDestroy
    void markShutdownInProgress() {
        shutdownInProgress = true;
        logger.info("[EXTERNAL-API] Marked batch persistence service as shutting down");
    }

    boolean isSystemicDatabaseError(Throwable ex) {
        if (ex == null) {
            return false;
        }
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConnectException
                || current instanceof SocketException
                || current instanceof CannotGetJdbcConnectionException
                || current instanceof DataAccessResourceFailureException) {
                return true;
            }
            if ((current instanceof SQLException || current instanceof DataAccessException)
                    && isSystemicErrorMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isSystemicErrorMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        boolean isConnectionError = lower.contains(ERR_CONNECTION) && (
                lower.contains(ERR_REFUSED) || lower.contains(ERR_CLOSED)
                || lower.contains(ERR_RESET) || lower.contains(ERR_TIMEOUT));
        boolean isAuthError = lower.contains(ERR_AUTH_FAILED);
        boolean isPoolExhausted = lower.contains(ERR_TOO_MANY_CONNECTIONS);
        boolean isDatabaseMissing = lower.contains(ERR_DATABASE) && lower.contains(ERR_DOES_NOT_EXIST);
        return isConnectionError || isAuthError || isPoolExhausted || isDatabaseMissing;
    }

    private List<Book> filterDuplicatesById(List<Book> books) {
        if (books == null || books.isEmpty()) {
            return List.of();
        }
        Set<String> seenIds = java.util.HashSet.newHashSet(books.size());
        List<Book> unique = new ArrayList<>(books.size());
        for (Book book : books) {
            if (book == null || !StringUtils.hasText(book.getId())) {
                continue;
            }
            if (seenIds.add(book.getId())) {
                unique.add(book);
            }
        }
        return unique;
    }

    private List<Book> deduplicateByIdentifiers(List<Book> books) {
        if (books == null || books.isEmpty()) {
            return List.of();
        }
        Map<String, Book> deduped = new LinkedHashMap<>();
        for (Book book : books) {
            if (book == null) {
                continue;
            }
            String key = extractDedupeKey(book);
            deduped.putIfAbsent(key, book);
        }
        return new ArrayList<>(deduped.values());
    }

    private String extractDedupeKey(Book book) {
        String isbn13 = IsbnUtils.sanitize(book.getIsbn13());
        if (StringUtils.hasText(isbn13)) {
            return "ISBN13:" + isbn13;
        }
        String isbn10 = IsbnUtils.sanitize(book.getIsbn10());
        if (StringUtils.hasText(isbn10)) {
            return "ISBN10:" + isbn10;
        }
        String normalizedTitle = normalizeTitleForDedupe(book.getTitle());
        if (normalizedTitle != null) {
            return "TITLE:" + normalizedTitle;
        }
        return "FALLBACK:" + book.getId();
    }

    private String normalizeTitleForDedupe(String title) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        return title.toLowerCase(Locale.ROOT).replaceAll(ALPHANUMERIC_ONLY_PATTERN, "");
    }

    private BookAggregate buildFallbackAggregate(Book book) {
        if (book == null || book.getTitle() == null || book.getTitle().isBlank()) {
            return null;
        }

        LocalDate publishedDate = null;
        if (book.getPublishedDate() != null) {
            publishedDate = book.getPublishedDate().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }

        return BookAggregate.builder()
            .title(book.getTitle())
            .description(book.getDescription())
            .isbn13(IsbnUtils.sanitize(book.getIsbn13()))
            .isbn10(IsbnUtils.sanitize(book.getIsbn10()))
            .publishedDate(publishedDate)
            .language(book.getLanguage())
            .publisher(book.getPublisher())
            .pageCount(book.getPageCount())
            .authors(book.getAuthors())
            .categories(book.getCategories())
            .identifiers(buildFallbackIdentifiers(book))
            .slugBase(SlugGenerator.generateBookSlug(book.getTitle(), book.getAuthors()))
            .editionNumber(book.getEditionNumber())
            .build();
    }

    private BookAggregate.ExternalIdentifiers buildFallbackIdentifiers(Book book) {
        Map<String, String> imageLinks = resolveFallbackImageLinks(book);
        String source = book.getRetrievedFrom() != null ? book.getRetrievedFrom() : "OPEN_LIBRARY";
        return BookAggregate.ExternalIdentifiers.builder()
            .source(source)
            .externalId(book.getId())
            .infoLink(book.getInfoLink())
            .previewLink(book.getPreviewLink())
            .purchaseLink(book.getPurchaseLink())
            .webReaderLink(book.getWebReaderLink())
            .averageRating(book.getAverageRating())
            .ratingsCount(book.getRatingsCount())
            .imageLinks(imageLinks)
            .build();
    }

    private Map<String, String> resolveFallbackImageLinks(Book book) {
        Map<String, String> imageLinks = new LinkedHashMap<>();
        if (book.getCoverImages() != null) {
            if (book.getCoverImages().getPreferredUrl() != null && !book.getCoverImages().getPreferredUrl().isBlank()) {
                imageLinks.put("large", UrlUtils.normalizeToHttps(book.getCoverImages().getPreferredUrl()));
            }
            if (book.getCoverImages().getFallbackUrl() != null && !book.getCoverImages().getFallbackUrl().isBlank()) {
                imageLinks.putIfAbsent("small", UrlUtils.normalizeToHttps(book.getCoverImages().getFallbackUrl()));
            }
        }
        if (book.getExternalImageUrl() != null && !book.getExternalImageUrl().isBlank()) {
            imageLinks.putIfAbsent("thumbnail", UrlUtils.normalizeToHttps(book.getExternalImageUrl()));
        }
        return imageLinks.isEmpty() ? Map.of() : Map.copyOf(imageLinks);
    }
}
