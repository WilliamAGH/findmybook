/**
 * Service for generating book recommendations based on various similarity criteria.
 *
 * <p>Composes {@link RecommendationScoringStrategy} (scoring, ranking, deduplication)
 * and {@link RecommendationStrategyChain} (author/category/text search orchestration)
 * to discover, score, and persist recommendation candidates.</p>
 *
 * @author William Callahan
 */
package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.service.BookRecommendationPersistenceService.RecommendationRecord;
import net.findmybook.util.LoggingUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
@Service
@Slf4j
public class RecommendationService {
    private static final int DEFAULT_RECOMMENDATION_COUNT = 6;
    private static final int FETCH_CONCURRENCY = 4;
    private static final int FETCH_PREFETCH = 8;

    private final BookDataOrchestrator bookDataOrchestrator;
    private final BookRecommendationPersistenceService recommendationPersistenceService;
    private final RecommendationStrategyChain strategyChain;
    private final boolean externalFallbackEnabled;

    /**
     * Constructs the RecommendationService with required dependencies.
     *
     * @implNote Delegates scoring and search orchestration to strategy components.
     */
    public RecommendationService(RecommendationServices services, RecommendationConfig config, RecommendationStrategyChain strategyChain) {
        this.bookDataOrchestrator = services.bookDataOrchestrator();
        this.recommendationPersistenceService = services.recommendationPersistenceService();
        this.strategyChain = strategyChain;
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
            BookRecommendationPersistenceService recommendationPersistenceService
        ) {
            return new RecommendationServices(
                bookDataOrchestrator,
                recommendationPersistenceService
            );
        }
    }

    public record RecommendationConfig(boolean externalFallbackEnabled) {}

    public record RecommendationServices(BookDataOrchestrator bookDataOrchestrator,
                                         BookRecommendationPersistenceService recommendationPersistenceService) {}

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
                .switchIfEmpty(Mono.defer(() -> {
                    if (externalFallbackEnabled) {
                        log.warn("Cache miss for book {}. Initiating legacy fallback pipeline.", bookId);
                        return fetchLegacyRecommendations(bookId, effectiveCount);
                    }
                    log.info("Cache miss for book {}. External fallback disabled — returning empty.", bookId);
                    return Mono.just(Collections.emptyList());
                }))
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
            return Mono.error(new IllegalStateException(
                "Legacy fallback invoked but externalFallbackEnabled=false for bookId=" + bookId));
        }
        return fetchCanonicalBook(bookId)
            .flatMap(sourceBook -> fetchRecommendationsFromApiAndUpdateCache(sourceBook, effectiveCount))
            .switchIfEmpty(Mono.defer(() -> {
                log.error("Legacy recommendation fallback for '{}' produced no results after API pipeline execution", bookId);
                return Mono.error(new IllegalStateException(
                    "Legacy fallback failed to produce recommendations for bookId=" + bookId));
            }));
    }

    private Mono<Book> fetchCanonicalBook(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Mono.empty();
        }
        return bookDataOrchestrator.fetchCanonicalBookReactive(identifier)
                .onErrorMap(error -> new IllegalStateException(
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
        return Flux.merge(
                strategyChain.findByAuthors(sourceBook),
                strategyChain.findByCategories(sourceBook),
                strategyChain.findByText(sourceBook)
            )
            .collect(Collectors.toMap(
                scoredBook -> scoredBook.book().getId(),
                scoredBook -> scoredBook,
                ScoredBook::mergeWith,
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
            .map(ScoredBook::book)
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
            .comparingInt((ScoredBook scored) -> CoverPrioritizer.score(scored.book()) > 0 ? 1 : 0)
            .reversed()
            .thenComparing(ScoredBook::score, Comparator.reverseOrder())
            .thenComparing(scored -> CoverPrioritizer.score(scored.book()), Comparator.reverseOrder());

        return recommendationMap.values().stream()
            .filter(scored -> isEligibleRecommendation(sourceBook, scored.book(), filterByLanguage, sourceLang))
            .sorted(comparator)
            .toList();
    }

    private Mono<List<Book>> persistRecommendations(Book sourceBook,
                                                    PersistableRecommendations recommendations) {
        List<ScoredBook> orderedCandidates = recommendations.orderedCandidates();
        List<Book> orderedBooks = recommendations.orderedBooks();
        List<Book> limitedRecommendations = recommendations.limitedRecommendations();
        bookDataOrchestrator.persistBooksAsync(limitedRecommendations, "RECOMMENDATION");

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

        Mono<Void> persistenceMono = recommendationPersistenceService
                .persistPipelineRecommendations(sourceBook, buildPersistenceRecords(orderedCandidates, limitedIds))
                .onErrorMap(ex -> {
                    LoggingUtils.warn(log, ex, "Failed to persist recommendations for {}", sourceBook.getId());
                    return new IllegalStateException(
                        "Failed to persist recommendations for " + sourceBook.getId(),
                        ex
                    );
                });

        return Mono.when(persistenceMono)
            .then(Mono.fromRunnable(() -> log.info("Updated cachedRecommendationIds for book {} with {} new IDs and cached {} individual recommended books.",
                    sourceBook.getId(), newRecommendationIds.size(), orderedBooks.size())))
            .thenReturn(limitedRecommendations)
            .doOnSuccess(finalList -> log.info("Fetched {} total potential recommendations for book ID {} from API, updated cache. Returning {} recommendations.", orderedBooks.size(), sourceBook.getId(), finalList.size()))
            .onErrorMap(e -> logAndWrapError(e, "Error completing recommendation pipeline for book " + sourceBook.getId()));
    }

    private List<RecommendationRecord> buildPersistenceRecords(List<ScoredBook> orderedCandidates, Set<String> limitedIds) {
        if (limitedIds.isEmpty()) {
            return List.of();
        }

        return orderedCandidates.stream()
            .filter(scored -> {
                Book candidate = scored.book();
                return candidate != null && candidate.getId() != null && limitedIds.contains(candidate.getId());
            })
            .map(scored -> new RecommendationRecord(
                scored.book(),
                scored.score(),
                new ArrayList<>(scored.reasons())))
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

    private record PersistableRecommendations(List<ScoredBook> orderedCandidates, List<Book> orderedBooks, List<Book> limitedRecommendations) {}

    private IllegalStateException logAndWrapError(Throwable cause, String context) {
        LoggingUtils.error(log, cause, "{}", context);
        return new IllegalStateException(context, cause);
    }
}
