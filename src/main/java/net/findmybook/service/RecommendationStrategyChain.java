package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.ValidationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
    private static final int FETCH_CONCURRENCY = 4;

    private final BookDataOrchestrator bookDataOrchestrator;
    private final BookSearchService bookSearchService;
    private final BookQueryRepository bookQueryRepository;
    private final RecommendationScoringStrategy scoringStrategy;

    /**
     * Constructs the strategy chain with required dependencies.
     */
    public RecommendationStrategyChain(BookDataOrchestrator bookDataOrchestrator,
                                        BookSearchService bookSearchService,
                                        BookQueryRepository bookQueryRepository,
                                        RecommendationScoringStrategy scoringStrategy) {
        this.bookDataOrchestrator = bookDataOrchestrator;
        this.bookSearchService = bookSearchService;
        this.bookQueryRepository = bookQueryRepository;
        this.scoringStrategy = scoringStrategy;
    }

    /**
     * Finds books by the same authors as the source book.
     */
    public Flux<ScoredBook> findByAuthors(Book sourceBook) {
        if (ValidationUtils.isNullOrEmpty(sourceBook.getAuthors())) {
            return Flux.empty();
        }
        String langCode = sourceBook.getLanguage();

        return Flux.fromIterable(sourceBook.getAuthors())
            .flatMap(author -> searchBooks("inauthor:" + author, langCode, MAX_SEARCH_RESULTS)
                .flatMapMany(Flux::fromIterable)
                .map(book -> new ScoredBook(
                    book,
                    scoringStrategy.authorMatchScore(),
                    scoringStrategy.authorReason()))
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
        String langCode = sourceBook.getLanguage();

        return searchBooks(categoryQueryString, langCode, MAX_SEARCH_RESULTS)
            .flatMapMany(Flux::fromIterable)
            .take(MAX_SEARCH_RESULTS)
            .map(book -> {
                double categoryScore = scoringStrategy.calculateCategoryOverlapScore(sourceBook, book);
                return new ScoredBook(book, categoryScore, scoringStrategy.categoryReason());
            })
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
        String langCode = sourceBook.getLanguage();

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
                    double score = scoringStrategy.calculateTextMatchScore(matchCount);
                    return Mono.just(new ScoredBook(book, score, scoringStrategy.textReason()));
                }
                return Mono.empty();
            })
            .onErrorMap(error -> new IllegalStateException(
                "RecommendationStrategyChain.findByText query=" + query,
                error));
    }

    private Mono<List<Book>> searchBooks(String query, String langCode, int limit) {
        if (!org.springframework.util.StringUtils.hasText(query) || bookSearchService == null || bookQueryRepository == null) {
            return Mono.just(Collections.emptyList());
        }

        final int safeLimit = Math.max(limit, 1);

        Mono<List<BookSearchService.SearchResult>> searchMono = Mono.fromCallable(() -> bookSearchService.searchBooks(query, safeLimit))
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());

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
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(items -> orderBooksBySearchResults(results, items, safeLimit));
        });
    }

    private List<Book> orderBooksBySearchResults(List<BookSearchService.SearchResult> results,
                                                  List<net.findmybook.dto.BookListItem> items,
                                                  int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        java.util.Map<String, Book> booksById = net.findmybook.util.BookDomainMapper.fromListItems(items).stream()
            .filter(Objects::nonNull)
            .filter(book -> org.springframework.util.StringUtils.hasText(book.getId()))
            .collect(Collectors.toMap(Book::getId, book -> book, (first, second) -> first, java.util.LinkedHashMap::new));

        if (booksById.isEmpty()) {
            return List.of();
        }

        List<Book> ordered = new java.util.ArrayList<>(Math.min(limit, booksById.size()));

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
