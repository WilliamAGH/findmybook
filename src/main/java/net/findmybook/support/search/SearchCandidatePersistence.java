package net.findmybook.support.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import net.findmybook.model.Book;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.util.ApplicationConstants;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Central guard for async persistence of externally discovered search candidates.
 *
 * <p>This keeps feature-flag and dependency checks in one place so search services do
 * not duplicate persistence gate logic.</p>
 */
@Slf4j
public final class SearchCandidatePersistence {

    static final int MAX_CANDIDATES_PER_BATCH =
        ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT
            * ApplicationConstants.Paging.SEARCH_PREFETCH_MULTIPLIER;
    private static final int RECENTLY_ADMITTED_MAXIMUM_SIZE = 100_000;
    private static final Duration RECENTLY_ADMITTED_TTL = Duration.ofMinutes(10);

    private final Optional<BookDataOrchestrator> bookDataOrchestrator;
    private final boolean persistSearchResultsEnabled;
    private final Cache<String, Boolean> recentlyAdmittedCandidateKeys = Caffeine.newBuilder()
        .maximumSize(RECENTLY_ADMITTED_MAXIMUM_SIZE)
        .expireAfterWrite(RECENTLY_ADMITTED_TTL)
        .build();

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
        List<Book> admittedBooks = admitNewCandidates(books);
        if (admittedBooks.isEmpty()) {
            return;
        }
        if (books.size() > admittedBooks.size()) {
            log.info(
                "Search persistence admission coalesced context={} candidates={} admitted={}",
                context,
                books.size(),
                admittedBooks.size()
            );
        }
        bookDataOrchestrator.get().persistBooksAsync(admittedBooks, context);
    }

    private synchronized List<Book> admitNewCandidates(List<Book> books) {
        List<Book> admittedBooks = new ArrayList<>(Math.min(books.size(), MAX_CANDIDATES_PER_BATCH));
        for (Book book : books) {
            List<String> candidateKeys = CandidateKeyResolver.resolveAliases(book);
            if (candidateKeys.isEmpty() || candidateKeys.stream().anyMatch(this::wasRecentlyAdmitted)) {
                continue;
            }
            candidateKeys.forEach(key -> recentlyAdmittedCandidateKeys.put(key, Boolean.TRUE));
            admittedBooks.add(book);
            if (admittedBooks.size() == MAX_CANDIDATES_PER_BATCH) {
                break;
            }
        }
        return List.copyOf(admittedBooks);
    }

    private boolean wasRecentlyAdmitted(String candidateKey) {
        return recentlyAdmittedCandidateKeys.getIfPresent(candidateKey) != null;
    }
}
