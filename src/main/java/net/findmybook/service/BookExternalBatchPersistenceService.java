package net.findmybook.service;

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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persists books retrieved from external APIs (Google Books, Open Library) into Postgres.
 * <p>
 * Handles deduplication by ID and by ISBN/title, maps external JSON payloads to
 * {@link BookAggregate}, and delegates upsert to {@link BookUpsertService} with
 * advisory-lock retry protection.
 */
@Service
class BookExternalBatchPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(BookExternalBatchPersistenceService.class);

    private static final String ALPHANUMERIC_ONLY_PATTERN = "[^a-z0-9]";

    // Systemic error message fragments for SQLException fallback detection
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

    BookExternalBatchPersistenceService(ObjectMapper objectMapper,
                                        GoogleBooksMapper googleBooksMapper,
                                        BookUpsertService bookUpsertService) {
        this.objectMapper = objectMapper;
        this.googleBooksMapper = googleBooksMapper;
        this.bookUpsertService = bookUpsertService;
    }

    void persistBooksAsync(List<Book> books, String context, Runnable searchViewRefreshTrigger) {
        if (books == null || books.isEmpty()) {
            logger.info("[EXTERNAL-API] [{}] persistBooksAsync called but books list is null or empty", context);
            return;
        }

        int originalSize = books.size();
        logger.info("[EXTERNAL-API] [{}] persistBooksAsync INVOKED with {} books", context, originalSize);

        List<Book> uniqueBooks = filterDuplicatesById(books);
        int duplicateCount = originalSize - uniqueBooks.size();
        if (duplicateCount > 0) {
            logger.info("[EXTERNAL-API] [{}] Filtered {} duplicate book(s) by ID, {} candidates remain",
                context, duplicateCount, uniqueBooks.size());
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
                ignored -> logger.info("[EXTERNAL-API] [{}] Persistence Mono completed successfully", context),
                error -> logger.error("[EXTERNAL-API] [{}] Background persistence failed: {}", context, error.getMessage(), error)
            );

        logger.info("[EXTERNAL-API] [{}] persistBooksAsync setup complete, async execution scheduled", context);
    }

    private void executeBatchPersistence(List<Book> books, String context, Runnable searchViewRefreshTrigger) {
        logger.info("[EXTERNAL-API] [{}] Persisting {} unique books to Postgres", context, books.size());
        long start = System.currentTimeMillis();
        int successCount = 0;
        int failureCount = 0;

        for (Book book : books) {
            if (book == null || book.getId() == null) {
                logger.warn("[EXTERNAL-API] [{}] Skipping book with null reference or null ID: title={}",
                    context, book != null ? book.getTitle() : "null");
                continue;
            }

            try {
                boolean ok = persistSingleBook(book, context, searchViewRefreshTrigger);
                if (ok) {
                    successCount++;
                } else {
                    failureCount++;
                    logger.warn("[EXTERNAL-API] [{}] Persist returned false for id={}", context, book.getId());
                }
            } catch (RuntimeException ex) {
                failureCount++;
                logger.error("[EXTERNAL-API] [{}] Failed to persist book {}: {}", context, book.getId(), ex.getMessage(), ex);

                if (isSystemicDatabaseError(ex)) {
                    logger.error("[EXTERNAL-API] [{}] Aborting batch due to systemic database error ({} succeeded, {} failed before abort)",
                        context, successCount, failureCount);
                    throw ex instanceof IllegalStateException ? ex
                        : new IllegalStateException("Systemic database error during batch persistence", ex);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        if (failureCount > 0) {
            String summary = String.format(
                "[EXTERNAL-API] [%s] Batch persistence completed with failures: %d succeeded, %d failed (%d ms)",
                context, successCount, failureCount, elapsed);
            logger.warn(summary);
            throw new IllegalStateException(summary);
        }
        logger.info("[EXTERNAL-API] [{}] Batch persistence complete: {} succeeded ({} ms)",
            context, successCount, elapsed);
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
        try {
            if (book == null) {
                throw new IllegalArgumentException("persistBook invoked with null book reference");
            }

            BookAggregate aggregate = googleBooksMapper.map(sourceJson);
            if (aggregate == null) {
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
            logger.error("Error persisting via BookUpsertService for book {}: {}",
                book.getId(), ex.getMessage(), ex);
            if (isSystemicDatabaseError(ex)) {
                if (ex instanceof IllegalStateException stateException) {
                    throw stateException;
                }
                throw new IllegalStateException("Systemic database error during upsert", ex);
            }
            throw new IllegalStateException("Error persisting via BookUpsertService for book " +
                book.getId(), ex);
        }
    }

    /**
     * Detects systemic database failures (connection loss, pool exhaustion, auth) that
     * should abort the batch. Checks both Spring's exception hierarchy and JDBC-level
     * {@link SQLException} messages for connection-related failures that surface before
     * Spring can wrap them.
     */
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
        Set<String> seenIds = new java.util.HashSet<>(books.size());
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
            String isbn13 = IsbnUtils.sanitize(book.getIsbn13());
            if (StringUtils.hasText(isbn13)) {
                deduped.putIfAbsent("ISBN13:" + isbn13, book);
                continue;
            }
            String isbn10 = IsbnUtils.sanitize(book.getIsbn10());
            if (StringUtils.hasText(isbn10)) {
                deduped.putIfAbsent("ISBN10:" + isbn10, book);
                continue;
            }
            String normalizedTitle = normalizeTitleForDedupe(book.getTitle());
            if (normalizedTitle != null) {
                deduped.putIfAbsent("TITLE:" + normalizedTitle, book);
                continue;
            }
            deduped.putIfAbsent("FALLBACK:" + book.getId(), book);
        }
        return new ArrayList<>(deduped.values());
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
            publishedDate = book.getPublishedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

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

        Map<String, String> immutableImageLinks = imageLinks.isEmpty() ? Map.of() : Map.copyOf(imageLinks);
        String source = book.getRetrievedFrom() != null ? book.getRetrievedFrom() : "OPEN_LIBRARY";

        BookAggregate.ExternalIdentifiers identifiers = BookAggregate.ExternalIdentifiers.builder()
            .source(source)
            .externalId(book.getId())
            .infoLink(book.getInfoLink())
            .previewLink(book.getPreviewLink())
            .purchaseLink(book.getPurchaseLink())
            .webReaderLink(book.getWebReaderLink())
            .averageRating(book.getAverageRating())
            .ratingsCount(book.getRatingsCount())
            .imageLinks(immutableImageLinks)
            .build();

        String sanitizedIsbn13 = IsbnUtils.sanitize(book.getIsbn13());
        String sanitizedIsbn10 = IsbnUtils.sanitize(book.getIsbn10());

        return BookAggregate.builder()
            .title(book.getTitle())
            .description(book.getDescription())
            .isbn13(sanitizedIsbn13)
            .isbn10(sanitizedIsbn10)
            .publishedDate(publishedDate)
            .language(book.getLanguage())
            .publisher(book.getPublisher())
            .pageCount(book.getPageCount())
            .authors(book.getAuthors())
            .categories(book.getCategories())
            .identifiers(identifiers)
            .slugBase(SlugGenerator.generateBookSlug(book.getTitle(), book.getAuthors()))
            .editionNumber(book.getEditionNumber())
            .build();
    }
}
