/**
 * Orchestrates book data retrieval through a tiered fetch strategy
 *
 * @author William Callahan
 * 
 * Features:
 * - Implements multi-tiered data retrieval from cache and APIs
 * - Manages fetching of individual books by ID or search results  
 * - Coordinates between Postgres persistence and Google Books API
 * - Handles persistence of API responses for performance
 * - Supports both authenticated and unauthenticated API usage
 */
package com.williamcallahan.book_recommendation_engine.service;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.book_recommendation_engine.dto.BookAggregate;
import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.util.ExternalApiLogger;
import com.williamcallahan.book_recommendation_engine.util.IsbnUtils;
import com.williamcallahan.book_recommendation_engine.util.SlugGenerator;
import com.williamcallahan.book_recommendation_engine.util.UrlUtils;
import com.williamcallahan.book_recommendation_engine.util.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Service
public class BookDataOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(BookDataOrchestrator.class);

    private final ObjectMapper objectMapper;
    // private final LongitoodBookDataService longitoodBookDataService; // Removed
    private final BookSearchService bookSearchService;
    private final PostgresBookRepository postgresBookRepository;
    private final BookUpsertService bookUpsertService; // SSOT for all book writes
    private final com.williamcallahan.book_recommendation_engine.mapper.GoogleBooksMapper googleBooksMapper; // For Book->BookAggregate mapping
    private static final long SEARCH_VIEW_REFRESH_INTERVAL_MS = 60_000L;
    private final AtomicLong lastSearchViewRefresh = new AtomicLong(0L);

    public BookDataOrchestrator(ObjectMapper objectMapper,
                                BookSearchService bookSearchService,
                                @Nullable PostgresBookRepository postgresBookRepository,
                                BookUpsertService bookUpsertService,
                                com.williamcallahan.book_recommendation_engine.mapper.GoogleBooksMapper googleBooksMapper) {
        this.objectMapper = objectMapper;
        this.bookSearchService = bookSearchService;
        this.postgresBookRepository = postgresBookRepository;
        this.bookUpsertService = bookUpsertService;
        this.googleBooksMapper = googleBooksMapper;
    }

    public void refreshSearchView() {
        triggerSearchViewRefresh(false);
    }

    public void refreshSearchViewImmediately() {
        triggerSearchViewRefresh(true);
    }

    /**
     * Fetches a canonical book record directly from Postgres without engaging external fallbacks.
     *
     * @param bookId Canonical UUID string for the book
     * @return Optional containing the hydrated {@link Book} when present in Postgres
     */
    public Optional<Book> getBookFromDatabase(String bookId) {
        return findInDatabaseById(bookId);
    }

    /**
     * Retrieves a canonical book from Postgres using its slug.
     *
     * @param slug The slug to resolve
     * @return Optional containing the hydrated {@link Book} when present in Postgres
     */
    public Optional<Book> getBookFromDatabaseBySlug(String slug) {
        return findInDatabaseBySlug(slug);
    }

    // --- Local DB lookup helpers (delegate to PostgresBookRepository when available) ---
    private Optional<Book> findInDatabaseById(String id) {
        return queryDatabase(repo -> repo.fetchByCanonicalId(id));
    }

    private Optional<Book> findInDatabaseBySlug(String slug) {
        return queryDatabase(repo -> repo.fetchBySlug(slug));
    }

    private Optional<Book> findInDatabaseByIsbn13(String isbn13) {
        return queryDatabase(repo -> repo.fetchByIsbn13(isbn13));
    }

    private Optional<Book> findInDatabaseByIsbn10(String isbn10) {
        return queryDatabase(repo -> repo.fetchByIsbn10(isbn10));
    }

    private Optional<Book> findInDatabaseByAnyExternalId(String externalId) {
        return queryDatabase(repo -> repo.fetchByExternalId(externalId));
    }

    private Optional<Book> queryDatabase(Function<PostgresBookRepository, Optional<Book>> resolver) {
        if (postgresBookRepository == null) {
            return Optional.empty();
        }
        Optional<Book> result = resolver.apply(postgresBookRepository);
        return result != null ? result : Optional.empty();
    }

    public Mono<Book> fetchCanonicalBookReactive(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Mono.empty();
        }

        // Optimized: Single Postgres query checks all possible lookups
        // Prevents cascading fallbacks that could trigger API calls unnecessarily
        return Mono.fromCallable(() -> {
            if (postgresBookRepository == null) {
                return null;
            }
            
            // Try all lookup methods in one go
            Book result = findInDatabaseBySlug(identifier).orElse(null);
            if (result != null) return result;
            
            result = findInDatabaseById(identifier).orElse(null);
            if (result != null) return result;
            
            result = findInDatabaseByIsbn13(identifier).orElse(null);
            if (result != null) return result;
            
            result = findInDatabaseByIsbn10(identifier).orElse(null);
            if (result != null) return result;
            
            return findInDatabaseByAnyExternalId(identifier).orElse(null);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(book -> book != null ? Mono.just(book) : Mono.empty())
        .onErrorResume(e -> {
            logger.warn("fetchCanonicalBookReactive failed for {}: {}", identifier, e.getMessage());
            return Mono.empty();
        });
    }

    /**
     * Persists books that were fetched from external APIs during search/recommendations.
     * This ensures opportunistic upsert: books returned from API calls get saved to Postgres.
     *
     * Task #8: Deduplicates books by ID before persistence to avoid redundant operations.
     *
     * @param books List of books to persist
     * @param context Context string for logging (e.g., "SEARCH", "RECOMMENDATION")
     */
    public void persistBooksAsync(List<Book> books, String context) {
        if (books == null || books.isEmpty()) {
            logger.info("[EXTERNAL-API] [{}] persistBooksAsync called but books list is null or empty", context);
            return;
        }

        int originalSize = books.size();
        logger.info("[EXTERNAL-API] [{}] persistBooksAsync INVOKED with {} books", context, originalSize);

        // Task #8: Deduplicate books to prevent redundant persistence operations
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

        Mono.fromRunnable(() -> {
            logger.info("[EXTERNAL-API] [{}] Persisting {} unique books to Postgres - RUNNABLE EXECUTING",
                context, dedupedByIdentifiers.size());
            long start = System.currentTimeMillis();
            int successCount = 0;
            int failureCount = 0;

            for (Book book : dedupedByIdentifiers) {
                if (book == null) {
                    logger.warn("[EXTERNAL-API] [{}] Skipping null book in persistence", context);
                    continue;
                }
                if (book.getId() == null) {
                    logger.warn("[EXTERNAL-API] [{}] Skipping book with null ID: title={}", context, book.getTitle());
                    continue;
                }
                
                try {
                    logger.debug("[EXTERNAL-API] [{}] Attempting to persist book: id={}, title={}", context, book.getId(), book.getTitle());
                    ExternalApiLogger.logHydrationStart(logger, context, book.getId(), context);
                    
                    // Convert book to JSON for storage
                    JsonNode bookJson;
                    if (book.getRawJsonResponse() != null && !book.getRawJsonResponse().isBlank()) {
                        bookJson = objectMapper.readTree(book.getRawJsonResponse());
                    } else {
                        bookJson = objectMapper.valueToTree(book);
                    }
                    
                    logger.debug("[EXTERNAL-API] [{}] Calling persistBook for id={}", context, book.getId());
                    // Persist using the same method as individual fetches
                    boolean ok = persistBook(book, bookJson);
                    
                    if (ok) {
                        ExternalApiLogger.logHydrationSuccess(logger, context, book.getId(), book.getId(), "POSTGRES_UPSERT");
                        successCount++;
                        logger.debug("[EXTERNAL-API] [{}] Successfully persisted book id={}", context, book.getId());
                    } else {
                        failureCount++;
                        logger.warn("[EXTERNAL-API] [{}] Persist returned false for id={}", context, book.getId());
                    }
                } catch (Exception ex) {
                    ExternalApiLogger.logHydrationFailure(logger, context, book.getId(), ex.getMessage());
                    failureCount++;
                    logger.error("[EXTERNAL-API] [{}] Failed to persist book {} from {}: {}", context, book.getId(), context, ex.getMessage(), ex);

                    // Fail fast on systemic errors (connection issues) to avoid hammering a broken database
                    if (isSystemicDatabaseError(ex)) {
                        logger.error("[EXTERNAL-API] [{}] Aborting batch due to systemic database error", context);
                        if (ex instanceof RuntimeException runtimeEx) {
                            throw runtimeEx;
                        }
                        throw new RuntimeException("Systemic database error during batch persistence", ex);
                    }
                }
            }
            
            long elapsed = System.currentTimeMillis() - start;
            logger.info("[EXTERNAL-API] [{}] Persistence complete: {} succeeded, {} failed ({} ms)", 
                context, successCount, failureCount, elapsed);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnSubscribe(sub -> logger.info("[EXTERNAL-API] [{}] Mono subscribed for persistence", context))
        .subscribe(
            ignored -> logger.info("[EXTERNAL-API] [{}] Persistence Mono completed successfully", context),
            error -> logger.error("[EXTERNAL-API] [{}] Background persistence failed: {}", context, error.getMessage(), error)
        );
        
        logger.info("[EXTERNAL-API] [{}] persistBooksAsync setup complete, async execution scheduled", context);
    }

    private List<Book> filterDuplicatesById(List<Book> books) {
        if (books == null || books.isEmpty()) {
            return List.of();
        }
        Set<String> seenIds = ConcurrentHashMap.newKeySet();
        List<Book> unique = new ArrayList<>(books.size());
        for (Book book : books) {
            if (book == null || !ValidationUtils.hasText(book.getId())) {
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
            if (ValidationUtils.hasText(isbn13)) {
                deduped.putIfAbsent("ISBN13:" + isbn13, book);
                continue;
            }
            String isbn10 = IsbnUtils.sanitize(book.getIsbn10());
            if (ValidationUtils.hasText(isbn10)) {
                deduped.putIfAbsent("ISBN10:" + isbn10, book);
                continue;
            }
            String normalizedTitle = normalizeTitleForDedupe(book.getTitle());
            if (normalizedTitle != null) {
                deduped.putIfAbsent("TITLE:" + normalizedTitle, book);
                continue;
            }
            // Fallback: preserve remaining entries without identifiers
            deduped.putIfAbsent("FALLBACK:" + book.getId(), book);
        }
        return new ArrayList<>(deduped.values());
    }

    private String normalizeTitleForDedupe(String title) {
        if (!ValidationUtils.hasText(title)) {
            return null;
        }
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Triggers materialized view refresh with rate limiting to prevent concurrent refreshes.
     * Uses atomic compareAndSet to ensure only one thread wins the race.
     *
     * @param force if true, bypasses the rate limit check (but still prevents concurrent execution)
     */
    private void triggerSearchViewRefresh(boolean force) {
        if (bookSearchService == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastSearchViewRefresh.get();

        // Rate limit check (unless forced)
        if (!force && last != 0 && now - last < SEARCH_VIEW_REFRESH_INTERVAL_MS) {
            return;
        }

        // Atomic check-and-set to prevent concurrent refreshes
        // Only one thread will succeed; others will see the updated timestamp and skip
        if (!lastSearchViewRefresh.compareAndSet(last, now)) {
            logger.debug("Skipping materialized view refresh - another thread is handling it");
            return;
        }

        try {
            bookSearchService.refreshMaterializedView();
        } catch (Exception ex) {
            logger.warn("BookDataOrchestrator: Failed to refresh search materialized view: {}", ex.getMessage());
            // Reset timestamp on failure so next attempt can try again
            lastSearchViewRefresh.compareAndSet(now, last);
        }
    }

    // Edition relationships are now handled by work_cluster_members table
    // See schema.sql for clustering logic

    /**
     * Persists book to database using BookUpsertService (SSOT for all writes).
     */
    private boolean persistBook(Book book, JsonNode sourceJson) {
        try {
            if (book == null) {
                logger.warn("persistBook invoked with null book reference; skipping.");
                return false;
            }

            // Convert provider payload to BookAggregate using the Google mapper first
            BookAggregate aggregate = googleBooksMapper.map(sourceJson);

            if (aggregate == null) {
                aggregate = buildFallbackAggregate(book);
                if (aggregate == null) {
                    logger.warn("Unable to map external payload for book {}. Skipping persistence.", book.getId());
                    return false;
                }
            }

            bookUpsertService.upsert(aggregate);
            triggerSearchViewRefresh(false);
            logger.debug("Persisted book via BookUpsertService: {}", book.getId());
            return true;
        } catch (Exception e) {
            logger.error("Error persisting via BookUpsertService for book {}: {}",
                book != null ? book.getId() : "UNKNOWN", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Determines if an exception indicates a systemic database problem (connection failure,
     * authentication error, etc.) that would cause all subsequent operations to fail.
     * Used to fail-fast on batch operations rather than repeatedly hammering a broken database.
     */
    private boolean isSystemicDatabaseError(Exception ex) {
        if (ex == null) {
            return false;
        }
        String message = ex.getMessage();
        if (message == null) {
            message = "";
        }
        String lowerMessage = message.toLowerCase(Locale.ROOT);

        // Check for connection-related failures
        return lowerMessage.contains("connection") && (
                   lowerMessage.contains("refused") ||
                   lowerMessage.contains("closed") ||
                   lowerMessage.contains("reset") ||
                   lowerMessage.contains("timeout")
               ) ||
               lowerMessage.contains("authentication failed") ||
               lowerMessage.contains("too many connections") ||
               lowerMessage.contains("database") && lowerMessage.contains("does not exist");
    }

    /**
     * Builds a fallback BookAggregate from a Book model object.
     * <p>Task #9: Sanitizes ISBNs to ensure consistent formatting before persistence.</p>
     */
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

        // Task #9: Sanitize ISBNs before building aggregate to prevent duplicate books
        // from formatting differences (e.g., "978-0-545-01022-1" vs "9780545010221")
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
            // Task #6: editionGroupKey removed - replaced by work_clusters system
            .build();
    }

    // Satisfy linter for private helpers referenced by annotations
    @SuppressWarnings("unused")
    private static boolean looksLikeUuid(String value) {
        if (value == null) return false;
        return value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    @SuppressWarnings("unused")
    private static com.fasterxml.jackson.databind.JsonNode parseBookJsonPayload(String payload, String fallbackId) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            try (com.fasterxml.jackson.core.JsonParser parser = mapper.createParser(payload)) {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(parser);
                if (parser.nextToken() != null) {
                    return null;
                }
                return node;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
