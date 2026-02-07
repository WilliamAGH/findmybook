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
package net.findmybook.service;

import org.springframework.stereotype.Service;

import net.findmybook.model.Book;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Service
public class BookDataOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(BookDataOrchestrator.class);

    private final BookSearchService bookSearchService;
    private final PostgresBookRepository postgresBookRepository;
    private final BookExternalBatchPersistenceService bookExternalBatchPersistenceService;
    private static final long SEARCH_VIEW_REFRESH_INTERVAL_MS = 60_000L;
    private final AtomicLong lastSearchViewRefresh = new AtomicLong(0L);
    private final AtomicBoolean searchViewRefreshInProgress = new AtomicBoolean(false);

    public BookDataOrchestrator(BookSearchService bookSearchService,
                                @Nullable PostgresBookRepository postgresBookRepository,
                                BookExternalBatchPersistenceService bookExternalBatchPersistenceService) {
        this.bookSearchService = bookSearchService;
        this.postgresBookRepository = postgresBookRepository;
        this.bookExternalBatchPersistenceService = bookExternalBatchPersistenceService;
        if (postgresBookRepository == null) {
            logger.warn("BookDataOrchestrator initialized without PostgresBookRepository — all database lookups will return empty");
        }
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
            throw new IllegalStateException("PostgresBookRepository is not available — database lookups cannot proceed");
        }
        return resolver.apply(postgresBookRepository);
    }

    public Mono<Book> fetchCanonicalBookReactive(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Mono.empty();
        }

        // Optimized: Single Postgres query checks all possible lookups
        // Prevents cascading fallbacks that could trigger API calls unnecessarily
        return Mono.fromCallable(() -> {
            if (postgresBookRepository == null) {
                throw new IllegalStateException("PostgresBookRepository is not available — database lookups cannot proceed");
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
        .doOnError(e -> logger.error("fetchCanonicalBookReactive failed for {}: {}", identifier, e.getMessage(), e));
    }

    /**
     * Persists books that were fetched from external APIs during search/recommendations.
     * This ensures opportunistic upsert: books returned from API calls get saved to Postgres.
     * @param books List of books to persist
     * @param context Context string for logging (e.g., "SEARCH", "RECOMMENDATION")
     */
    public void persistBooksAsync(List<Book> books, String context) {
        bookExternalBatchPersistenceService.persistBooksAsync(books, context, () -> triggerSearchViewRefresh(false));
    }

    /**
     * Triggers materialized view refresh with rate limiting to prevent concurrent refreshes.
     * Uses AtomicBoolean in-flight guard for mutual exclusion plus timestamp for rate limiting.
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

        // In-flight guard to prevent concurrent refreshes
        // Only one thread will succeed the CAS from false -> true
        if (!searchViewRefreshInProgress.compareAndSet(false, true)) {
            logger.debug("Skipping materialized view refresh - another thread is handling it");
            return;
        }

        // Update timestamp after acquiring the lock
        lastSearchViewRefresh.set(now);

        try {
            bookSearchService.refreshMaterializedView();
        } finally {
            searchViewRefreshInProgress.set(false);
        }
    }

    // Edition relationships are now handled by work_cluster_members table
    // See schema.sql for clustering logic
}
