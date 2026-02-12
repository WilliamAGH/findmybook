/**
 * Service for generating book recommendations based on various similarity criteria.
 * This class is responsible for generating book recommendations using a combination of strategies:
 *
 * - Uses multi-faceted approach combining author, category, and text-based matching
 *
 * @implNote LOC1 split plan (701 lines): extract {@code RecommendationScoringStrategy}
 *     (scoring, ranking, and deduplication logic) and {@code BookSearchStrategyChain}
 *     (author/category/text search orchestration).
 * - Implements scoring algorithm to rank recommendations by relevance
 * - Supports language-aware filtering to match source book language
 * - Provides reactive API for non-blocking recommendation generation
 * - Handles category normalization for better cross-book matching
 * - Implements keyword extraction with stop word filtering
 * - Caches recommendations for improved performance and reduced API usage
 * - Updates recommendation IDs in source book for persistent recommendation history
 *
 * @author William Callahan
 */
package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.BookRecommendationPersistenceService.RecommendationRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.findmybook.util.LoggingUtils;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.ValidationUtils;
import org.springframework.util.StringUtils;
import net.findmybook.util.CategoryNormalizer;
import net.findmybook.util.cover.CoverPrioritizer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
@Service
@Slf4j
public class RecommendationService {
    private static final int MAX_SEARCH_RESULTS = 40;
    private static final int DEFAULT_RECOMMENDATION_COUNT = 6;
    private static final int FETCH_CONCURRENCY = 4;
    private static final int FETCH_PREFETCH = 8;
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "for", "with", "are", "was", "from", "that", "this", "but", "not",
            "you", "your", "get", "will", "all", "any", "uses", "using", "learn", "what",
            "which", "its", "into", "then", "also"
    ));

    private static final double AUTHOR_MATCH_SCORE = 4.0;
    private static final int MAX_MAIN_CATEGORIES = 3;
    private static final double BASE_CATEGORY_SCORE = 0.5;
    private static final double CATEGORY_SCORE_BASE = 1.0;
    private static final double CATEGORY_SCORE_RANGE = 2.0;
    private static final int MAX_EXTRACTED_KEYWORDS = 10;
    private static final double TEXT_MATCH_SCORE_MULTIPLIER = 2.0;

    private static final String REASON_AUTHOR = "AUTHOR";
    private static final String REASON_CATEGORY = "CATEGORY";
    private static final String REASON_TEXT = "TEXT";

    private final BookDataOrchestrator bookDataOrchestrator;
    private final BookSearchService bookSearchService;
    private final BookQueryRepository bookQueryRepository;
    private final BookRecommendationPersistenceService recommendationPersistenceService;
    private final boolean externalFallbackEnabled;

    /**
     * Constructs the RecommendationService with required dependencies.
     *
     * @implNote Delegates all lookups to BookDataOrchestrator to avoid duplicate tier logic.
     */
    public RecommendationService(RecommendationServices services, RecommendationConfig config) {
        this.bookDataOrchestrator = services.bookDataOrchestrator();
        this.bookSearchService = services.bookSearchService();
        this.bookQueryRepository = services.bookQueryRepository();
        this.recommendationPersistenceService = services.recommendationPersistenceService();
        this.externalFallbackEnabled = config.externalFallbackEnabled();
    }

    @Component
    public static class ConfigLoader {
        @Bean
        public RecommendationConfig recommendationConfig(
            @Value("${app.features.external-fallback.enabled:${app.features.google-fallback.enabled:true}}") boolean externalFallbackEnabled
        ) {
            return new RecommendationConfig(externalFallbackEnabled);
        }

        @Bean
        public RecommendationServices recommendationServices(
            BookDataOrchestrator bookDataOrchestrator,
            BookSearchService bookSearchService,
            BookQueryRepository bookQueryRepository,
            BookRecommendationPersistenceService recommendationPersistenceService
        ) {
            return new RecommendationServices(
                bookDataOrchestrator,
                bookSearchService,
                bookQueryRepository,
                recommendationPersistenceService
            );
        }
    }

    public record RecommendationConfig(boolean externalFallbackEnabled) {}

    public record RecommendationServices(
        BookDataOrchestrator bookDataOrchestrator,
        BookSearchService bookSearchService,
        BookQueryRepository bookQueryRepository,
        BookRecommendationPersistenceService recommendationPersistenceService
    ) {}

    /**
     * Generates recommendations for books similar to the specified book
     *
     * @param bookId The Google Books ID to find recommendations for
     * @param finalCount The number of recommendations to return (defaults to 6 if ≤ 0)
     * @return Mono emitting list of recommended books in descending order of relevance
     *
     * @implNote Combines three recommendation strategies (author, category, text matching)
     * Scores and ranks results to provide the most relevant recommendations
     * Filters by language to match the source book when language information is available
     */
    public Mono<List<Book>> getSimilarBooks(String bookId, int finalCount) {
        final int effectiveCount = (finalCount <= 0) ? DEFAULT_RECOMMENDATION_COUNT : finalCount;

        return fetchCanonicalBook(bookId)
                .flatMap(sourceBook -> fetchCachedRecommendations(sourceBook, effectiveCount)
                        .flatMap(cached -> {
                            if (!cached.isEmpty()) {
                                log.info("Serving {} cached Postgres recommendations for book {}.", cached.size(), bookId);
                                return Mono.just(cached);
                            }
                            log.info("No cached Postgres recommendations for book {}. Falling back to API pipeline.", bookId);
                            return fetchRecommendationsFromApiAndUpdateCache(sourceBook, effectiveCount);
                        }))
                .switchIfEmpty(externalFallbackEnabled
                        ? fetchLegacyRecommendations(bookId, effectiveCount)
                        : Mono.just(Collections.emptyList()))
                .onErrorMap(ex -> logAndWrapError(ex, "Failed to assemble recommendations for " + bookId));
    }

    /**
     * Regenerates recommendations for a book by bypassing cached recommendation IDs.
     *
     * <p>This method executes the scoring pipeline and persists refreshed Postgres rows.
     * It is intended for stale-cache recovery paths where active recommendation rows are absent.</p>
     *
     * @param bookId public identifier or canonical UUID
     * @param finalCount desired number of recommendations (defaults to 6 when non-positive)
     * @return mono emitting regenerated recommendation books
     */
    public Mono<List<Book>> regenerateSimilarBooks(String bookId, int finalCount) {
        final int effectiveCount = (finalCount <= 0) ? DEFAULT_RECOMMENDATION_COUNT : finalCount;

        return fetchCanonicalBook(bookId)
            .switchIfEmpty(Mono.error(new IllegalStateException("No canonical book found for " + bookId)))
            .flatMap(sourceBook -> fetchRecommendationsFromApiAndUpdateCache(sourceBook, effectiveCount))
            .onErrorMap(ex -> logAndWrapError(ex, "Failed to regenerate recommendations for " + bookId));
    }

    /**
     * Fetches book recommendations using the Google Books API and updates the cache
     * This is a fallback method used when cached recommendations are unavailable or insufficient
     *
     * @param sourceBook The book to find recommendations for
     * @param effectiveCount The desired number of recommendations to return
     * @return Mono emitting a list of recommended books
     *
     * @implNote Uses a multi-strategy approach:
     * 1. Retrieves books by same authors (via findBooksByAuthorsReactive)
     * 2. Retrieves books in similar categories (via findBooksByCategoriesReactive)
     * 3. Retrieves books with matching keywords (via findBooksByTextReactive)
     * 4. Merges results, with duplicates having their scores combined
     * 5. Filters by language and excludes the source book itself
    * 6. Updates the source book's cached recommendation IDs for future use
     */
    private Mono<List<Book>> fetchLegacyRecommendations(String bookId, int effectiveCount) {
        if (!externalFallbackEnabled) {
            return Mono.just(Collections.emptyList());
        }
        return fetchCanonicalBook(bookId)
                .flatMap(sourceBook -> fetchRecommendationsFromApiAndUpdateCache(sourceBook, effectiveCount))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Legacy recommendation fallback for '{}' produced no results", bookId);
                    return Mono.just(Collections.emptyList());
                }));
    }

    private Mono<Book> fetchCanonicalBook(String identifier) {
        if (!StringUtils.hasText(identifier) || bookDataOrchestrator == null) {
            return Mono.empty();
        }
        Mono<Book> canonical = bookDataOrchestrator.fetchCanonicalBookReactive(identifier);
        return canonical == null
                ? Mono.empty()
                : canonical.onErrorMap(error -> new IllegalStateException(
                    "RecommendationService.fetchCanonicalBook(" + identifier + ")",
                    error
                ));
    }

    private Mono<Book> fetchCanonicalBookSafe(String identifier) {
        return fetchCanonicalBook(identifier)
                .doOnError(ex -> log.debug("Canonical lookup failed for recommendation {}: {}", identifier, ex.getMessage()))
                .onErrorMap(error -> new IllegalStateException(
                    "RecommendationService.fetchCanonicalBookSafe(" + identifier + ")",
                    error
                ));
    }

    private Mono<List<Book>> fetchCachedRecommendations(Book sourceBook, int limit) {
        if (sourceBook == null) {
            return Mono.just(Collections.<Book>emptyList());
        }
        List<String> cachedIds = sourceBook.getCachedRecommendationIds();
        if (cachedIds == null || cachedIds.isEmpty()) {
            return Mono.just(Collections.<Book>emptyList());
        }

        List<String> idsToFetch = new ArrayList<>(cachedIds);
        Collections.shuffle(idsToFetch);

        return Flux.fromIterable(idsToFetch)
                .flatMapSequential(this::fetchCanonicalBookSafe, FETCH_CONCURRENCY, FETCH_PREFETCH)
                .filter(Objects::nonNull)
                .filter(recommended -> sourceBook.getId() == null || !sourceBook.getId().equals(recommended.getId()))
                .distinct(Book::getId)
                .take(limit)
                .collectList()
                .doOnNext(results -> log.debug("Hydrated {} cached recommendations for {}", results.size(), sourceBook.getId()));
    }

    private Mono<List<Book>> fetchRecommendationsFromApiAndUpdateCache(Book sourceBook, int effectiveCount) {
        return collectScoredCandidates(sourceBook)
            .flatMap(recommendationMap -> processAndPersistRecommendations(sourceBook, recommendationMap, effectiveCount));
    }

    private Mono<Map<String, ScoredBook>> collectScoredCandidates(Book sourceBook) {
        Flux<ScoredBook> authorsFlux = findBooksByAuthorsReactive(sourceBook);
        Flux<ScoredBook> categoriesFlux = findBooksByCategoriesReactive(sourceBook);
        Flux<ScoredBook> textFlux = findBooksByTextReactive(sourceBook);

        return Flux.merge(authorsFlux, categoriesFlux, textFlux)
            .collect(Collectors.toMap(
                scoredBook -> scoredBook.getBook().getId(),
                scoredBook -> scoredBook,
                (sb1, sb2) -> {
                    sb1.mergeWith(sb2);
                    return sb1;
                },
                HashMap::new
            ));
    }

    private Mono<List<Book>> processAndPersistRecommendations(Book sourceBook,
                                                              Map<String, ScoredBook> recommendationMap,
                                                              int effectiveCount) {
        List<ScoredBook> orderedCandidates = sortAndFilterCandidates(sourceBook, recommendationMap);

        if (orderedCandidates.isEmpty()) {
            log.info("No recommendations generated from API for book ID: {}", sourceBook.getId());
            return Mono.just(Collections.<Book>emptyList());
        }

        List<Book> orderedBooks = orderedCandidates.stream()
            .map(ScoredBook::getBook)
            .toList();

        List<Book> limitedRecommendations = orderedBooks.stream()
            .limit(effectiveCount)
            .toList();

        return persistRecommendations(sourceBook,
            new PersistableRecommendations(orderedCandidates, orderedBooks, limitedRecommendations));
    }

    private List<ScoredBook> sortAndFilterCandidates(Book sourceBook, Map<String, ScoredBook> recommendationMap) {
        String sourceLang = sourceBook.getLanguage();
        boolean filterByLanguage = StringUtils.hasText(sourceLang);

        Comparator<ScoredBook> comparator = Comparator
            .comparingInt((ScoredBook scored) -> CoverPrioritizer.score(scored.getBook()) > 0 ? 1 : 0)
            .reversed()
            .thenComparing(ScoredBook::getScore, Comparator.reverseOrder())
            .thenComparing(scored -> CoverPrioritizer.score(scored.getBook()), Comparator.reverseOrder());

        return recommendationMap.values().stream()
            .filter(scored -> isEligibleRecommendation(sourceBook, scored.getBook(), filterByLanguage, sourceLang))
            .sorted(comparator)
            .toList();
    }

    private Mono<List<Book>> persistRecommendations(Book sourceBook,
                                                    PersistableRecommendations recommendations) {
        List<ScoredBook> orderedCandidates = recommendations.orderedCandidates();
        List<Book> orderedBooks = recommendations.orderedBooks();
        List<Book> limitedRecommendations = recommendations.limitedRecommendations();
        if (bookDataOrchestrator != null) {
            bookDataOrchestrator.persistBooksAsync(limitedRecommendations, "RECOMMENDATION");
        }

        List<String> newRecommendationIds = orderedBooks.stream()
            .map(Book::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        sourceBook.addRecommendationIds(newRecommendationIds);

        Set<String> limitedIds = limitedRecommendations.stream()
            .map(Book::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Mono<Void> persistenceMono = recommendationPersistenceService != null
            ? recommendationPersistenceService
                .persistPipelineRecommendations(sourceBook, buildPersistenceRecords(orderedCandidates, limitedIds))
                .onErrorMap(ex -> {
                    LoggingUtils.warn(log, ex, "Failed to persist recommendations for {}", sourceBook.getId());
                    return new IllegalStateException(
                        "Failed to persist recommendations for " + sourceBook.getId(),
                        ex
                    );
                })
            : Mono.empty();

        return Mono.when(persistenceMono)
            .then(Mono.fromRunnable(() -> log.info("Updated cachedRecommendationIds for book {} with {} new IDs and cached {} individual recommended books.",
                    sourceBook.getId(), newRecommendationIds.size(), orderedBooks.size())))
            .thenReturn(limitedRecommendations)
            .doOnSuccess(finalList -> log.info("Fetched {} total potential recommendations for book ID {} from API, updated cache. Returning {} recommendations.", orderedBooks.size(), sourceBook.getId(), finalList.size()))
            .onErrorMap(e -> logAndWrapError(e, "Error completing recommendation pipeline for book " + sourceBook.getId()));
    }

    private List<RecommendationRecord> buildPersistenceRecords(List<ScoredBook> orderedCandidates, Set<String> limitedIds) {
        if (recommendationPersistenceService == null || limitedIds.isEmpty()) {
            return List.of();
        }

        return orderedCandidates.stream()
            .filter(scored -> {
                Book candidate = scored.getBook();
                return candidate != null && candidate.getId() != null && limitedIds.contains(candidate.getId());
            })
            .map(scored -> new RecommendationRecord(
                scored.getBook(),
                scored.getScore(),
                new ArrayList<>(scored.getReasons())))
            .toList();
    }

    private boolean isEligibleRecommendation(Book sourceBook, Book candidate, boolean filterByLanguage, String sourceLang) {
        if (candidate == null) {
            return false;
        }
        String candidateId = candidate.getId();
        if (!StringUtils.hasText(candidateId)) {
            return false;
        }
        String sourceId = sourceBook != null ? sourceBook.getId() : null;
        if (sourceId != null && sourceId.equals(candidateId)) {
            return false;
        }
        if (!filterByLanguage) {
            return true;
        }
        return Objects.equals(sourceLang, candidate.getLanguage());
    }

    /**
     * Finds books by the same authors as the source book
     *
     * @param sourceBook The source book to find author matches for
     * @return Flux emitting scored books by the same authors
     *
     * @implNote Assigns high score (4.0) to author matches as they are strong indicators
     * Returns empty flux if source book has no authors
     */
    private Flux<ScoredBook> findBooksByAuthorsReactive(Book sourceBook) {
        if (ValidationUtils.isNullOrEmpty(sourceBook.getAuthors())) {
            return Flux.empty();
        }
        String langCode = sourceBook.getLanguage(); // Get language from source book

        return Flux.fromIterable(sourceBook.getAuthors())
            .flatMap(author -> searchBooks("inauthor:" + author, langCode, MAX_SEARCH_RESULTS)
                .flatMapMany(Flux::fromIterable)
                .map(book -> new ScoredBook(book, AUTHOR_MATCH_SCORE, REASON_AUTHOR))
                .onErrorMap(error -> new IllegalStateException(
                    "RecommendationService.findBooksByAuthorsReactive author=" + author,
                    error
                )));
    }

    /**
     * Finds books in the same categories as the source book
     *
     * @param sourceBook The source book to find category matches for
     * @return Flux emitting scored books in matching categories
     *
     * @implNote Extracts main categories and builds optimized category search query
     * Score varies based on category overlap calculation
     */
    private Flux<ScoredBook> findBooksByCategoriesReactive(Book sourceBook) {
        if (ValidationUtils.isNullOrEmpty(sourceBook.getCategories())) {
            return Flux.empty();
        }

        List<String> mainCategories = sourceBook.getCategories().stream()
            .map(category -> category.split("\\s*/\\s*")[0])
            .distinct()
            .limit(MAX_MAIN_CATEGORIES)
            .toList();

        if (mainCategories.isEmpty()) {
            return Flux.empty();
        }

        String categoryQueryString = "subject:" + String.join(" OR subject:", mainCategories);
        String langCode = sourceBook.getLanguage(); // Get language from source book

        return searchBooks(categoryQueryString, langCode, MAX_SEARCH_RESULTS)
            .flatMapMany(Flux::fromIterable)
            .take(MAX_SEARCH_RESULTS)
            .map(book -> {
                double categoryScore = calculateCategoryOverlapScore(sourceBook, book);
                return new ScoredBook(book, categoryScore, REASON_CATEGORY);
            })
            .onErrorMap(error -> new IllegalStateException(
                "RecommendationService.findBooksByCategoriesReactive query=" + categoryQueryString,
                error
            ));
    }

    /**
     * Calculates similarity score based on category overlap between books
     *
     * @param sourceBook The source book for comparison
     * @param candidateBook The candidate book being evaluated
     * @return Score between 1.0 and 3.0 reflecting category similarity
     *
     * @implNote Uses normalized categories for more accurate matching
     * Score is proportional to the percentage of overlapping categories
     */
    private double calculateCategoryOverlapScore(Book sourceBook, Book candidateBook) {
        if (ValidationUtils.isNullOrEmpty(sourceBook.getCategories()) ||
            ValidationUtils.isNullOrEmpty(candidateBook.getCategories())) {
            return BASE_CATEGORY_SCORE; // Some basic score if it can't calculate
        }

        Set<String> sourceCategories = normalizeCategories(sourceBook.getCategories());
        Set<String> candidateCategories = normalizeCategories(candidateBook.getCategories());

        // Find intersecting categories
        Set<String> intersection = new HashSet<>(sourceCategories);
        intersection.retainAll(candidateCategories);

        // More overlapping categories = higher score
        double overlapRatio = (double) intersection.size() /
                PagingUtils.atLeast(Math.min(sourceCategories.size(), candidateCategories.size()), 1);

        // Scale to range 1.0 - 3.0
        return CATEGORY_SCORE_BASE + (overlapRatio * CATEGORY_SCORE_RANGE);
    }

    /**
     * Normalizes book categories for consistent comparison
     *
     * @param categories List of categories to normalize
     * @return Set of normalized category strings
     *
     * @implNote Delegates to CategoryNormalizer utility for DRY principle
     * @see CategoryNormalizer#normalizeForComparison(List)
     */
    private Set<String> normalizeCategories(List<String> categories) {
        return CategoryNormalizer.normalizeForComparison(categories);
    }

    /**
     * Finds books with similar keywords in title and description
     *
     * @param sourceBook The source book to find keyword matches for
     * @return Flux emitting scored books with matching keywords
     *
     * @implNote Extracts significant keywords from title and description
     * Filters out common stop words and short tokens
     * Score based on quantity of matching keywords
     */
    private Flux<ScoredBook> findBooksByTextReactive(Book sourceBook) {
        boolean titleMissing = !StringUtils.hasText(sourceBook.getTitle());
        boolean descriptionMissing = !StringUtils.hasText(sourceBook.getDescription());
        if (titleMissing && descriptionMissing) {
            return Flux.empty();
        }

        String safeTitle = Optional.ofNullable(sourceBook.getTitle()).orElse("");
        String safeDescription = Optional.ofNullable(sourceBook.getDescription()).orElse("");
        String combinedText = (safeTitle + " " + safeDescription).toLowerCase(Locale.ROOT);
        String[] tokens = combinedText.split("[^a-z0-9]+");
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.length() > 2 && !STOP_WORDS.contains(token)) {
                keywords.add(token);
                if (keywords.size() >= MAX_EXTRACTED_KEYWORDS) break;
            }
        }

        if (keywords.isEmpty()) {
            return Flux.empty();
        }

        String query = String.join(" ", keywords);
        String langCode = sourceBook.getLanguage(); // Get language from source book

        return searchBooks(query, langCode, MAX_SEARCH_RESULTS)
            .flatMapMany(Flux::fromIterable)
            .take(MAX_SEARCH_RESULTS)
            .flatMap(book -> {
                String candidateText = ((book.getTitle() != null ? book.getTitle() : "") + " " +
                                      (book.getDescription() != null ? book.getDescription() : "")).toLowerCase(Locale.ROOT);
                int matchCount = 0;
                for (String kw : keywords) {
                    if (candidateText.contains(kw)) {
                        matchCount++;
                    }
                }
                if (matchCount > 0) {
                    double score = TEXT_MATCH_SCORE_MULTIPLIER * matchCount;
                    return Mono.just(new ScoredBook(book, score, REASON_TEXT));
                }
                return Mono.empty();
            })
            .onErrorMap(error -> new IllegalStateException(
                "RecommendationService.findBooksByTextReactive query=" + query,
                error
            ));
    }

    private Mono<List<Book>> searchBooks(String query, String langCode, int limit) {
        if (!StringUtils.hasText(query) || bookSearchService == null || bookQueryRepository == null) {
            return Mono.just(Collections.emptyList());
        }

        final int safeLimit = Math.max(limit, 1);

        return Mono.fromCallable(() -> bookSearchService.searchBooks(query, safeLimit))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(results -> {
                if (results == null || results.isEmpty()) {
                    return Mono.just(Collections.<Book>emptyList());
                }

                List<UUID> orderedIds = results.stream()
                    .map(BookSearchService.SearchResult::bookId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .limit(safeLimit)
                    .toList();

                if (orderedIds.isEmpty()) {
                    return Mono.just(Collections.<Book>emptyList());
                }

                return Mono.fromCallable(() -> bookQueryRepository.fetchBookListItems(orderedIds))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(items -> orderBooksBySearchResults(results, items, safeLimit));
            })
            .defaultIfEmpty(Collections.emptyList())
            .map(books -> limitResults(books, safeLimit))
            .onErrorMap(error -> logAndWrapError(error, "RecommendationService.searchBooks query=" + query + " lang=" + langCode));
    }

    private List<Book> limitResults(List<Book> books, int limit) {
        if (books == null || books.isEmpty()) {
            return Collections.emptyList();
        }
        return books.stream()
                .filter(Objects::nonNull)
                .filter(book -> StringUtils.hasText(book.getId()))
                .limit(limit)
                .toList();
    }

    private List<Book> orderBooksBySearchResults(List<BookSearchService.SearchResult> results,
                                                 List<BookListItem> items,
                                                 int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        Map<String, Book> booksById = BookDomainMapper.fromListItems(items).stream()
            .filter(Objects::nonNull)
            .filter(book -> StringUtils.hasText(book.getId()))
            .collect(Collectors.toMap(Book::getId, book -> book, (first, second) -> first, LinkedHashMap::new));

        if (booksById.isEmpty()) {
            return List.of();
        }

        List<Book> ordered = new ArrayList<>(Math.min(limit, booksById.size()));

        for (BookSearchService.SearchResult result : results) {
            UUID bookId = result.bookId();
            if (bookId == null) {
                continue;
            }
            Book book = booksById.remove(bookId.toString());
            if (book != null) {
                ordered.add(book);
                if (ordered.size() == limit) {
                    return ordered;
                }
            }
        }

        if (ordered.size() < limit) {
            for (Book remaining : booksById.values()) {
                ordered.add(remaining);
                if (ordered.size() == limit) {
                    break;
                }
            }
        }

        return ordered;
    }

    private record PersistableRecommendations(
        List<ScoredBook> orderedCandidates,
        List<Book> orderedBooks,
        List<Book> limitedRecommendations
    ) {}

    private IllegalStateException logAndWrapError(Throwable cause, String context) {
        LoggingUtils.error(log, cause, "{}", context);
        return new IllegalStateException(context, cause);
    }

    /**
     * Helper class to track books with their calculated similarity scores
     *
     * Encapsulates:
     * - Book object with all its metadata
     * - Similarity score that accumulates across multiple recommendation strategies
     * - Used for ranking recommendations by relevance
     * - Allows scores to be combined when a book is found by multiple strategies
     */
    private static class ScoredBook {
        private final Book book;
        private double score;
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();

        public ScoredBook(Book book, double score, String reason) {
            this.book = book;
            this.score = score;
            if (StringUtils.hasText(reason)) {
                this.reasons.add(reason);
            }
        }

        public Book getBook() {
            return book;
        }

        public double getScore() {
            return score;
        }

        public Set<String> getReasons() {
            return Collections.unmodifiableSet(reasons);
        }

        public void mergeWith(ScoredBook other) {
            this.score += other.score;
            this.reasons.addAll(other.reasons);
        }
    }
}
