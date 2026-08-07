package net.findmybook.service;

import java.io.IOException;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.PrematureCloseException;
import reactor.util.retry.Retry;

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
    private static final int OPEN_LIBRARY_TRANSIENT_RETRY_LIMIT = 1;
    private static final String DESCRIPTION_ENRICHMENT_SORT = "relevance";
    private static final String ISBN_QUERY_PREFIX = "isbn:";
    private static final String OPEN_LIBRARY_PROVIDER = "Open Library";
    private static final String GOOGLE_BOOKS_PROVIDER = "Google Books";
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

    private Optional<Book> queryDatabase(Function<PostgresBookRepository, Optional<Book>> resolver) {
        if (postgresBookRepository == null) {
            throw new IllegalStateException("PostgresBookRepository is not available — database lookups cannot proceed");
        }
        return resolver.apply(postgresBookRepository);
    }

    private List<Book> fetchDescriptionEnrichmentCandidates(UUID bookId, String query) {
        long enrichmentDeadlineNanos = System.nanoTime() + DESCRIPTION_ENRICHMENT_TIMEOUT.toNanos();
        List<Book> candidates = new ArrayList<>();
        RuntimeException firstProviderFailure = null;
        boolean providerSucceeded = false;
        if (openLibraryBookDataService.isPresent()) {
            try {
                candidates.addAll(fetchOpenLibraryCandidates(query, enrichmentDeadlineNanos));
                providerSucceeded = true;
            } catch (RuntimeException openLibraryFailure) {
                firstProviderFailure = openLibraryFailure;
                logger.warn("Open Library description enrichment failed for bookId={} (continuing with Google Books): {}",
                    bookId, openLibraryFailure.getMessage());
            }
        }
        if (googleExternalSearchFlow.isAvailable()) {
            try {
                candidates.addAll(fetchGoogleCandidates(query, enrichmentDeadlineNanos));
                providerSucceeded = true;
            } catch (RuntimeException googleFailure) {
                if (firstProviderFailure != null) {
                    firstProviderFailure.addSuppressed(googleFailure);
                } else {
                    firstProviderFailure = googleFailure;
                }
            }
        }
        if (!providerSucceeded && firstProviderFailure != null) {
            throw firstProviderFailure;
        }
        return candidates;
    }

    private List<Book> fetchOpenLibraryCandidates(String query, long enrichmentDeadlineNanos) {
        String openLibraryQuery = SearchExternalProviderUtils.normalizeExternalQuery(query);
        if (openLibraryBookDataService.isEmpty()
            || !StringUtils.hasText(openLibraryQuery)
            || SearchQueryUtils.isWildcard(openLibraryQuery)) {
            return List.of();
        }
        Flux<Book> candidates = openLibraryBookDataService.get()
            .queryBooksByEverything(openLibraryQuery, DESCRIPTION_ENRICHMENT_SORT, 0, DESCRIPTION_ENRICHMENT_LIMIT);
        return collectOpenLibraryCandidates(candidates, enrichmentDeadlineNanos);
    }

    private List<Book> fetchGoogleCandidates(String query, long enrichmentDeadlineNanos) {
        Flux<Book> candidates = googleExternalSearchFlow
            .streamCandidates(query, DESCRIPTION_ENRICHMENT_SORT, null, DESCRIPTION_ENRICHMENT_LIMIT);
        return collectGoogleCandidates(candidates, enrichmentDeadlineNanos);
    }

    private List<Book> collectOpenLibraryCandidates(Flux<Book> candidates, long enrichmentDeadlineNanos) {
        Mono<List<Book>> retriedCandidates = candidates.collectList()
            .retryWhen(Retry.max(OPEN_LIBRARY_TRANSIENT_RETRY_LIMIT)
                .filter(this::isTransientProviderFailure)
                .doBeforeRetry(retrySignal -> logger.info(
                    "Retrying Open Library description enrichment after transient {} (retry={})",
                    retrySignal.failure().getClass().getSimpleName(),
                    retrySignal.totalRetries() + 1
                ))
                .onRetryExhaustedThrow((retrySpec, retrySignal) -> retrySignal.failure()));
        return collectWithDescriptionEnrichmentDeadline(
            OPEN_LIBRARY_PROVIDER,
            retriedCandidates,
            enrichmentDeadlineNanos
        );
    }

    private List<Book> collectGoogleCandidates(Flux<Book> candidates, long enrichmentDeadlineNanos) {
        return collectWithDescriptionEnrichmentDeadline(
            GOOGLE_BOOKS_PROVIDER,
            candidates.collectList(),
            enrichmentDeadlineNanos
        );
    }

    private List<Book> collectWithDescriptionEnrichmentDeadline(String providerName,
                                                                 Mono<List<Book>> candidates,
                                                                 long enrichmentDeadlineNanos) {
        Duration remainingDuration = Duration.ofNanos(Math.max(0L, enrichmentDeadlineNanos - System.nanoTime()));
        return candidates
            .timeout(remainingDuration)
            .onErrorMap(TimeoutException.class, timeoutFailure -> new IllegalStateException(
                providerName + " description enrichment exceeded the "
                    + DESCRIPTION_ENRICHMENT_TIMEOUT.toSeconds() + "s request-wide deadline",
                timeoutFailure
            ))
            .block();
    }

    private boolean isTransientProviderFailure(Throwable failure) {
        Throwable currentFailure = failure;
        while (currentFailure != null) {
            if (currentFailure instanceof WebClientResponseException responseException
                && responseException.getStatusCode().is5xxServerError()) {
                return true;
            }
            if (currentFailure instanceof TimeoutException
                || currentFailure instanceof IOException
                || currentFailure instanceof WebClientRequestException
                || currentFailure instanceof PrematureCloseException) {
                return true;
            }
            currentFailure = currentFailure.getCause();
        }
        return false;
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
            return ISBN_QUERY_PREFIX + isbn13;
        }
        String isbn10 = IsbnUtils.sanitize(detail.isbn10());
        if (StringUtils.hasText(isbn10)) {
            return ISBN_QUERY_PREFIX + isbn10;
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

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        return token.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
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

            result = queryDatabase(repo -> repo.fetchByIsbn13(identifier)).orElse(null);
            if (result != null) return result;

            result = queryDatabase(repo -> repo.fetchByIsbn10(identifier)).orElse(null);
            if (result != null) return result;

            return queryDatabase(repo -> repo.fetchByExternalId(identifier)).orElse(null);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(book -> book != null ? Mono.just(book) : Mono.empty())
        .doOnError(e -> logger.error("fetchCanonicalBookReactive failed for {}: {}", identifier, e.getMessage(), e));
    }

    /**
     * Persists books fetched from external APIs and schedules a throttled search-view refresh.
     *
     * @param books external books to persist
     * @param context operation context used in persistence logs
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
