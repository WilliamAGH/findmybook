package net.findmybook.support.search;

import net.findmybook.model.Book;
import net.findmybook.service.BookDataOrchestrator;

import java.util.List;
import java.util.Optional;

/**
 * Central guard for async persistence of externally discovered search candidates.
 *
 * <p>This keeps feature-flag and dependency checks in one place so search services do
 * not duplicate persistence gate logic.</p>
 */
public final class SearchCandidatePersistence {

    private final Optional<BookDataOrchestrator> bookDataOrchestrator;
    private final boolean persistSearchResultsEnabled;

    /**
     * Creates a persistence guard for search candidate storage.
     *
     * @param bookDataOrchestrator optional orchestrator that persists books
     * @param persistSearchResultsEnabled feature flag for search-result persistence
     */
    public SearchCandidatePersistence(Optional<BookDataOrchestrator> bookDataOrchestrator,
                                      boolean persistSearchResultsEnabled) {
        this.bookDataOrchestrator = bookDataOrchestrator != null ? bookDataOrchestrator : Optional.empty();
        this.persistSearchResultsEnabled = persistSearchResultsEnabled;
    }

    /**
     * Persists candidates asynchronously when persistence is enabled and dependencies are available.
     *
     * @param books candidate books to persist
     * @param context persistence context label
     */
    public void persist(List<Book> books, String context) {
        if (!persistSearchResultsEnabled || books == null || books.isEmpty() || bookDataOrchestrator.isEmpty()) {
            return;
        }
        bookDataOrchestrator.get().persistBooksAsync(books, context);
    }
}
