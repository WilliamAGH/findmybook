/**
 * Service responsible for tracking, managing, and providing recently viewed books
 * 
 * This component provides functionality for:
 * - Maintaining a thread-safe list of recently viewed books
 * - Handling canonical book ID resolution to prevent duplicates
 * - Providing fallback recommendations when history is empty
 * - Supporting both synchronous and reactive APIs
 * - Ensuring memory efficiency through size limits
 * 
 * Used throughout the application to enhance user experience by enabling
 * "recently viewed" sections and personalized recommendations
 *
 * @author William Callahan
 */
package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.UuidUtils;
import org.springframework.util.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Service for tracking and managing recently viewed books
 * 
 * Features:
 * - Maintains a thread-safe list of recently viewed books
 * - Limits the number of books in the history to avoid memory issues
 * - Provides fallback recommendations when no books have been viewed
 * - Avoids duplicate entries by removing existing books before re-adding
 * - Sorts fallback books by publication date for relevance
 */
@Service
@Slf4j
public class RecentlyViewedService {

    private final JdbcTemplate jdbcTemplate;
    private final RecentBookViewRepository recentBookViewRepository;

    // In-memory storage for recently viewed books (lock-free for better concurrency)
    private final ConcurrentLinkedDeque<Book> recentlyViewedBooks = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_BOOKS = ApplicationConstants.Paging.DEFAULT_TIERED_LIMIT / 2;

    /**
     * Constructs a RecentlyViewedService with required dependencies.
     *
     * @param jdbcTemplate JDBC helper used to resolve canonical book IDs within a work cluster
     * @param recentBookViewRepository repository for persisting and retrieving view statistics
     *
     * @implNote Initializes the in-memory linked list for storing recently viewed books.
     */
    public RecentlyViewedService(JdbcTemplate jdbcTemplate,
                                 RecentBookViewRepository recentBookViewRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.recentBookViewRepository = recentBookViewRepository;
    }

    /**
     * Adds a book to the recently viewed list, ensuring canonical ID is used.
     *
     * @param book the book to add to recently viewed history
     *
     * @implNote Resolves the canonical identifier through {@link #resolveCanonicalBookId(String)},
     * creates a defensive copy when necessary, and maintains the max-size deque without locks.
     */
    public void addToRecentlyViewed(Book book) {
        if (book == null) {
            log.warn("RECENT_VIEWS_DEBUG: Attempted to add a null book to recently viewed.");
            return;
        }

        String originalBookId = book.getId();
        log.info("RECENT_VIEWS_DEBUG: Attempting to add book. Original ID: '{}', Title: '{}'", originalBookId, book.getTitle());

        String canonicalId = resolveCanonicalBookId(originalBookId);
        if (!Objects.equals(originalBookId, canonicalId)) {
            log.info("RECENT_VIEWS_DEBUG: Resolved original ID '{}' to canonical ID '{}' for book title '{}'",
                originalBookId, canonicalId, book.getTitle());
        }
        
        if (!StringUtils.hasText(canonicalId)) {
            log.warn("RECENT_VIEWS_DEBUG: Null or empty canonical ID determined for book title '{}' (original ID '{}'). Skipping add.", book.getTitle(), originalBookId);
            return;
        }

        final String finalCanonicalId = canonicalId;

        Book bookToAdd = book;
        // If the canonical ID is different from the book's current ID,
        // create a new Book object (or clone) for storage in the list with the canonical ID
        // This avoids modifying the original 'book' object which might be used elsewhere
        if (!Objects.equals(originalBookId, finalCanonicalId)) {
            log.info("RECENT_VIEWS_DEBUG: Book ID mismatch. Original: '{}', Canonical: '{}'. Creating new Book instance for recent views.", originalBookId, finalCanonicalId);
            bookToAdd = new Book();
            // Copy essential properties for display in recent views
            bookToAdd.setId(finalCanonicalId);
            bookToAdd.setTitle(book.getTitle());
            bookToAdd.setAuthors(book.getAuthors());
            bookToAdd.setS3ImagePath(book.getS3ImagePath());
            bookToAdd.setPublishedDate(book.getPublishedDate());
            // Add other fields if they are displayed in the "Recent Views" section
        }

        if (!StringUtils.hasText(bookToAdd.getSlug())) {
            bookToAdd.setSlug(finalCanonicalId);
        }

        // Use a final reference for lambda capture below
        final Book bookRef = bookToAdd;

        // Lock-free operations using ConcurrentLinkedDeque
        // Remove existing entry for this book
        recentlyViewedBooks.removeIf(b ->
            b != null && Objects.equals(b.getId(), finalCanonicalId)
        );

        // Add to front
        recentlyViewedBooks.addFirst(bookToAdd);
        log.info("RECENT_VIEWS_DEBUG: Added book with canonical ID '{}'. List size now: {}", finalCanonicalId, recentlyViewedBooks.size());

        // Trim to max size
        while (recentlyViewedBooks.size() > MAX_RECENT_BOOKS) {
            Book removedLastBook = recentlyViewedBooks.pollLast();
            if (removedLastBook != null) {
                log.debug("RECENT_VIEWS_DEBUG: Trimmed book. ID: '{}'", removedLastBook.getId());
            }
        }

        if (recentBookViewRepository != null && recentBookViewRepository.isEnabled()) {
            recentBookViewRepository.recordView(finalCanonicalId, Instant.now(), "web");
            recentBookViewRepository.fetchStatsForBook(finalCanonicalId)
                    .ifPresent(stats -> applyViewStats(bookRef, stats));
        }
    }

    /**
     * Gets list of recently viewed book IDs for use with BookQueryRepository.
     * This is THE SINGLE SOURCE for recently viewed book IDs.
     * 
     * Performance: Returns only UUIDs, caller fetches as BookCard DTOs with single query.
     * 
     * @param limit Maximum number of book IDs to return
     * @return List of book UUIDs (as Strings) for recently viewed books
     */
    public List<String> getRecentlyViewedBookIds(int limit) {
        // Check repository first (persistent storage)
        if (recentBookViewRepository != null && recentBookViewRepository.isEnabled()) {
            try {
                List<RecentBookViewRepository.ViewStats> stats = 
                    recentBookViewRepository.fetchMostRecentViews(limit);
                
                if (!stats.isEmpty()) {
                    return stats.stream()
                        .map(RecentBookViewRepository.ViewStats::bookId)
                        .filter(StringUtils::hasText)
                        .limit(limit)
                        .collect(Collectors.toList());
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to fetch recently viewed book IDs from repository", e);
            }
        }
        
        // Fallback to in-memory cache
        return recentlyViewedBooks.stream()
            .filter(b -> b != null && StringUtils.hasText(b.getId()))
            .map(Book::getId)
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Resolves the canonical work-cluster book identifier when possible.
     *
     * @param originalBookId identifier submitted by a caller
     * @return canonical book ID when the cluster lookup succeeds; otherwise the original ID
     */
    private String resolveCanonicalBookId(String originalBookId) {
        if (!StringUtils.hasText(originalBookId) || jdbcTemplate == null) {
            return originalBookId;
        }

        UUID uuid = UuidUtils.parseUuidOrNull(originalBookId);
        if (uuid == null) {
            return originalBookId;
        }

        try {
            String canonical = jdbcTemplate.queryForObject(
                """
                SELECT primary_wcm.book_id::text
                FROM work_cluster_members wcm
                JOIN work_cluster_members primary_wcm
                    ON primary_wcm.cluster_id = wcm.cluster_id
                   AND primary_wcm.is_primary = true
                WHERE wcm.book_id = ?
                LIMIT 1
                """,
                String.class,
                uuid
            );
            return StringUtils.hasText(canonical) ? canonical : originalBookId;
        } catch (EmptyResultDataAccessException ignore) {
            return originalBookId;
        } catch (DataAccessException ex) {
            log.error("Failed to resolve canonical book ID for {}: {}", originalBookId, ex.getMessage(), ex);
            throw new IllegalStateException("Canonical book ID resolution failed for " + originalBookId, ex);
        }
    }


    /**
     * Applies persisted view statistics to the supplied book.
     *
     * @param book target book instance to enrich
     * @param stats view statistics retrieved from the repository
     */
    private void applyViewStats(Book book, RecentBookViewRepository.ViewStats stats) {
        if (book == null || stats == null) {
            return;
        }
        book.addQualifier("recent.views.lastViewedAt", stats.lastViewedAt());
        book.addQualifier("recent.views.24h", stats.viewsLast24h());
        book.addQualifier("recent.views.7d", stats.viewsLast7d());
        book.addQualifier("recent.views.30d", stats.viewsLast30d());
    }

    /**
     * Clears the recently viewed books list
     * 
     * @implNote Thread-safe implementation using synchronized block
     * Logs the action for debugging and audit purposes
     * Does not affect default recommendations which are generated dynamically
     */
    public void clearRecentlyViewedBooks() {
        recentlyViewedBooks.clear();
        log.debug("Recently viewed books cleared.");
    }
}
