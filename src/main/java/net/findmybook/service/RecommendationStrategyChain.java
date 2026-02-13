package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Strategy chain for orchestrating author, category, and text-based book searches.
 *
 * <p>Encapsulates search query building and external API coordination for
 * recommendation candidate discovery.</p>
 */
@Component
public class RecommendationStrategyChain {

    private static final Logger log = LoggerFactory.getLogger(RecommendationStrategyChain.class);

    private static final int MAX_SEARCH_RESULTS = 40;

    private final BookSearchService bookSearchService;
    private final BookQueryRepository bookQueryRepository;
    private final RecommendationScoringStrategy scoringStrategy;

    /**
     * Constructs the strategy chain with required dependencies.
     */
    public RecommendationStrategyChain(BookSearchService bookSearchService,
                                        BookQueryRepository bookQueryRepository,
                                        RecommendationScoringStrategy scoringStrategy) {
        this.bookSearchService = Objects.requireNonNull(bookSearchService, "bookSearchService");
        this.bookQueryRepository = Objects.requireNonNull(bookQueryRepository, "bookQueryRepository");
        this.scoringStrategy = Objects.requireNonNull(scoringStrategy, "scoringStrategy");
    }

    /**
     * Finds books by the same authors as the source book.
     */
    public Flux<ScoredBook> findByAuthors(Book sourceBook) {
        if (ValidationUtils.isNullOrEmpty(sourceBook.getAuthors())) {
            return Flux.empty();
        }

        return Flux.fromIterable(sourceBook.getAuthors())
            .flatMap(author -> searchBooks("inauthor:" + author, MAX_SEARCH_RESULTS)
                .flatMapMany(Flux::fromIterable)
                .map(book -> new ScoredBook(
                    book,
                    scoringStrategy.authorMatchScore(),
                    scoringStrategy.authorReason()))
                .doOnError(error -> log.error("findByAuthors failed for author={}", author, error))
                .onErrorMap(error -> new IllegalStateException(
                    "RecommendationStrategyChain.findByAuthors author=" + author,
                    error)));
    }

    /**
     * Finds books in the same categories as the source book.
     */
    public Flux<ScoredBook> findByCategories(Book sourceBook) {
        List<String> mainCategories = scoringStrategy.extractMainCategories(sourceBook);
        if (mainCategories.isEmpty()) {
            return Flux.empty();
        }

        String categoryQueryString = "subject:" + String.join(" OR subject:", mainCategories);

        return searchBooks(categoryQueryString, MAX_SEARCH_RESULTS)
            .flatMapMany(Flux::fromIterable)
            .take(MAX_SEARCH_RESULTS)
            .map(book -> {
                double categoryScore = scoringStrategy.calculateCategoryOverlapScore(sourceBook, book);
                return new ScoredBook(book, categoryScore, scoringStrategy.categoryReason());
            })
            .doOnError(error -> log.error("findByCategories failed for query={}", categoryQueryString, error))
            .onErrorMap(error -> new IllegalStateException(
                "RecommendationStrategyChain.findByCategories query=" + categoryQueryString,
                error));
    }

    /**
     * Finds books with similar keywords in title and description.
     */
    public Flux<ScoredBook> findByText(Book sourceBook) {
        Set<String> keywords = scoringStrategy.extractKeywords(sourceBook);
        if (keywords.isEmpty()) {
            return Flux.empty();
        }

        String query = String.join(" ", keywords);

        return searchBooks(query, MAX_SEARCH_RESULTS)
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
                    double score = scoringStrategy.calculateTextMatchScore(matchCount);
                    return Mono.just(new ScoredBook(book, score, scoringStrategy.textReason()));
                }
                return Mono.empty();
            })
            .doOnError(error -> log.error("findByText failed for query={}", query, error))
            .onErrorMap(error -> new IllegalStateException(
                "RecommendationStrategyChain.findByText query=" + query,
                error));
    }

    private Mono<List<Book>> searchBooks(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return Mono.just(Collections.emptyList());
        }

        final int safeLimit = Math.max(limit, 1);

        Mono<List<BookSearchService.SearchResult>> searchMono = Mono.fromCallable(() -> bookSearchService.searchBooks(query, safeLimit))
            .subscribeOn(Schedulers.boundedElastic());

        return searchMono.flatMap(results -> {
            if (results == null || results.isEmpty()) {
                return Mono.just(Collections.emptyList());
            }

            List<UUID> orderedIds = results.stream()
                .map(BookSearchService.SearchResult::bookId)
                .filter(Objects::nonNull)
                .distinct()
                .limit(safeLimit)
                .toList();

            if (orderedIds.isEmpty()) {
                return Mono.just(Collections.emptyList());
            }

            return Mono.fromCallable(() -> bookQueryRepository.fetchBookListItems(orderedIds))
                .subscribeOn(Schedulers.boundedElastic())
                .map(items -> orderBooksBySearchResults(results, items, safeLimit));
        });
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
}
