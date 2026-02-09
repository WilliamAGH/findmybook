package net.findmybook.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.stereotype.Service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.dto.BookDetail;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.model.Book;
import net.findmybook.support.search.GoogleExternalSearchFlow;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.SlugGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

@Service
public class BookDataOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(BookDataOrchestrator.class);

    private final BookSearchService bookSearchService;
    private final PostgresBookRepository postgresBookRepository;
    private final BookExternalBatchPersistenceService bookExternalBatchPersistenceService;
    private final Optional<OpenLibraryBookDataService> openLibraryBookDataService;
    private final GoogleExternalSearchFlow googleExternalSearchFlow;
    private final BookUpsertService bookUpsertService;
    private static final long SEARCH_VIEW_REFRESH_INTERVAL_MS = 60_000L;
    private static final int DESCRIPTION_ENRICHMENT_LIMIT = 6;
    private static final Duration DESCRIPTION_ENRICHMENT_TIMEOUT = Duration.ofSeconds(8);
    private final AtomicLong lastSearchViewRefresh = new AtomicLong(0L);
    private final AtomicBoolean searchViewRefreshInProgress = new AtomicBoolean(false);

    public BookDataOrchestrator(BookSearchService bookSearchService,
                                @Nullable PostgresBookRepository postgresBookRepository,
                                BookExternalBatchPersistenceService bookExternalBatchPersistenceService,
                                Optional<OpenLibraryBookDataService> openLibraryBookDataService,
                                Optional<GoogleApiFetcher> googleApiFetcher,
                                Optional<GoogleBooksMapper> googleBooksMapper,
                                BookUpsertService bookUpsertService) {
        this.bookSearchService = bookSearchService;
        this.postgresBookRepository = postgresBookRepository;
        this.bookExternalBatchPersistenceService = bookExternalBatchPersistenceService;
        this.openLibraryBookDataService = openLibraryBookDataService != null ? openLibraryBookDataService : Optional.empty();
        this.googleExternalSearchFlow = new GoogleExternalSearchFlow(googleApiFetcher, googleBooksMapper);
        this.bookUpsertService = bookUpsertService;
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

    /** Reads a canonical book directly from Postgres without external fallback. */
    public Optional<Book> getBookFromDatabase(String bookId) {
        return findInDatabaseById(bookId);
    }

    /** Reads a canonical book directly from Postgres by slug. */
    public Optional<Book> getBookFromDatabaseBySlug(String slug) {
        return findInDatabaseBySlug(slug);
    }

    /**
     * Enriches short descriptions for AI generation via Open Library/Google Books and
     * upserts canonical data only when a longer matching description is found.
     */
    public String enrichDescriptionForAiIfNeeded(UUID bookId,
                                                 BookDetail detail,
                                                 String currentDescription,
                                                 int minimumLength) {
        if (descriptionLength(currentDescription) >= minimumLength) {
            return currentDescription;
        }

        String query = buildDescriptionEnrichmentQuery(detail);
        if (!StringUtils.hasText(query)) {
            return currentDescription;
        }

        List<Book> candidates = fetchDescriptionEnrichmentCandidates(bookId, query);
        String bestDescription = candidates.stream()
            .filter(candidate -> candidateMatchesDetail(candidate, detail))
            .map(Book::getDescription)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .filter(candidateDescription -> candidateDescription.length() > descriptionLength(currentDescription))
            .max(Comparator.comparingInt(String::length))
            .orElse(currentDescription);
        if (!StringUtils.hasText(bestDescription) || Objects.equals(bestDescription, currentDescription)) {
            return currentDescription;
        }
        return persistEnrichedDescription(bookId, detail, bestDescription, currentDescription);
    }

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

    private List<Book> fetchDescriptionEnrichmentCandidates(UUID bookId, String query) {
        List<Book> candidates = new ArrayList<>();
        String openLibraryQuery = SearchExternalProviderUtils.normalizeExternalQuery(query);

        if (openLibraryBookDataService.isPresent()
            && StringUtils.hasText(openLibraryQuery)
            && !SearchQueryUtils.isWildcard(openLibraryQuery)) {
            candidates.addAll(openLibraryBookDataService.get()
                .queryBooksByEverything(openLibraryQuery, "relevance", 0, DESCRIPTION_ENRICHMENT_LIMIT)
                .timeout(DESCRIPTION_ENRICHMENT_TIMEOUT)
                .collectList()
                .block());
        }

        candidates.addAll(googleExternalSearchFlow.streamCandidates(query, "relevance", null, DESCRIPTION_ENRICHMENT_LIMIT)
            .timeout(DESCRIPTION_ENRICHMENT_TIMEOUT)
            .collectList()
            .block());

        return candidates;
    }

    private String persistEnrichedDescription(UUID bookId,
                                              BookDetail detail,
                                              String bestDescription,
                                              String currentDescription) {
        String isbn13 = IsbnUtils.sanitize(detail.isbn13());
        String isbn10 = IsbnUtils.sanitize(detail.isbn10());
        if (!StringUtils.hasText(isbn13) && !StringUtils.hasText(isbn10)) {
            logger.info("Skipping description enrichment upsert for bookId={} because canonical ISBN is unavailable", bookId);
            return currentDescription;
        }
        if (!StringUtils.hasText(detail.title())) {
            logger.info("Skipping description enrichment upsert for bookId={} because title is unavailable", bookId);
            return currentDescription;
        }

        String title = detail.title().trim();
        BookAggregate aggregate = BookAggregate.builder()
            .title(title)
            .description(bestDescription)
            .isbn13(isbn13)
            .isbn10(isbn10)
            .publishedDate(detail.publishedDate())
            .language(detail.language())
            .publisher(detail.publisher())
            .pageCount(detail.pageCount())
            .authors(detail.authors())
            .categories(detail.categories())
            .slugBase(SlugGenerator.generateBookSlug(title, detail.authors()))
            .build();

        BookUpsertService.UpsertResult upsertResult = bookUpsertService.upsert(aggregate);
        if (!bookId.equals(upsertResult.getBookId())) {
            logger.warn("Description enrichment upsert resolved to a different book (requested={}, resolved={})",
                bookId, upsertResult.getBookId());
            return currentDescription;
        }
        return bookSearchService.fetchBookDetail(bookId)
            .map(BookDetail::description)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .orElse(currentDescription);
    }

    private String buildDescriptionEnrichmentQuery(BookDetail detail) {
        String isbn13 = IsbnUtils.sanitize(detail.isbn13());
        if (StringUtils.hasText(isbn13)) {
            return "isbn:" + isbn13;
        }
        String isbn10 = IsbnUtils.sanitize(detail.isbn10());
        if (StringUtils.hasText(isbn10)) {
            return "isbn:" + isbn10;
        }
        if (!StringUtils.hasText(detail.title())) {
            return null;
        }
        if (detail.authors() == null || detail.authors().isEmpty() || !StringUtils.hasText(detail.authors().getFirst())) {
            return detail.title().trim();
        }
        return detail.title().trim() + " " + detail.authors().getFirst().trim();
    }

    private boolean candidateMatchesDetail(Book candidate, BookDetail detail) {
        if (candidate == null || !StringUtils.hasText(candidate.getTitle())) {
            return false;
        }

        String isbn13 = IsbnUtils.sanitize(detail.isbn13());
        String isbn10 = IsbnUtils.sanitize(detail.isbn10());
        if (StringUtils.hasText(isbn13) && isbn13.equals(IsbnUtils.sanitize(candidate.getIsbn13()))) {
            return true;
        }
        if (StringUtils.hasText(isbn10) && isbn10.equals(IsbnUtils.sanitize(candidate.getIsbn10()))) {
            return true;
        }

        String detailTitle = normalizeToken(detail.title());
        if (!StringUtils.hasText(detailTitle) || !detailTitle.equals(normalizeToken(candidate.getTitle()))) {
            return false;
        }

        String detailAuthor = detail.authors() == null || detail.authors().isEmpty()
            ? ""
            : normalizeToken(detail.authors().getFirst());
        String candidateAuthor = candidate.getAuthors() == null || candidate.getAuthors().isEmpty()
            ? ""
            : normalizeToken(candidate.getAuthors().getFirst());
        return detailAuthor.isEmpty() || detailAuthor.equals(candidateAuthor);
    }

    private String normalizeToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private int descriptionLength(String description) {
        return description == null ? 0 : description.trim().length();
    }

    public Mono<Book> fetchCanonicalBookReactive(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            if (postgresBookRepository == null) {
                throw new IllegalStateException("PostgresBookRepository is not available — database lookups cannot proceed");
            }

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

    private void triggerSearchViewRefresh(boolean force) {
        if (bookSearchService == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastSearchViewRefresh.get();

        if (!force && last != 0 && now - last < SEARCH_VIEW_REFRESH_INTERVAL_MS) {
            return;
        }

        if (!searchViewRefreshInProgress.compareAndSet(false, true)) {
            logger.debug("Skipping materialized view refresh - another thread is handling it");
            return;
        }

        lastSearchViewRefresh.set(now);

        try {
            bookSearchService.refreshMaterializedView();
        } finally {
            searchViewRefreshInProgress.set(false);
        }
    }

}
