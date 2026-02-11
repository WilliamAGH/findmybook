/**
 * REST controller exposing Postgres-first book APIs.
 */
package net.findmybook.controller;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.application.book.BookDetailResponseUseCase;
import net.findmybook.application.book.RecommendationCardResponseUseCase;
import net.findmybook.controller.dto.BookDto;
import net.findmybook.controller.dto.BookDtoMapper;
import net.findmybook.controller.dto.search.AuthorSearchResponse;
import net.findmybook.controller.dto.search.SearchContractMapper;
import net.findmybook.controller.dto.search.SearchFilters;
import net.findmybook.controller.dto.search.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import net.findmybook.dto.BookDetail;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.RecommendationService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.EnumParsingUtils;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.ReactiveControllerUtils;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.UuidUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/books")
public class BookController {
    private static final Logger log = LoggerFactory.getLogger(BookController.class);
    private final BookSearchService bookSearchService;
    private final BookIdentifierResolver bookIdentifierResolver;
    private final SearchPaginationService searchPaginationService;
    private final BookDetailResponseUseCase bookDetailResponseUseCase;
    private final RecommendationCardResponseUseCase recommendationCardResponseUseCase;
    private final RecommendationService recommendationService;
    /**
     * The book data orchestrator, may be null when disabled.
     */
    private final BookDataOrchestrator bookDataOrchestrator;

    /**
     * Constructs BookController with optional orchestrator.
     *
     * @param bookSearchService the book search service
     * @param bookIdentifierResolver the identifier resolver
     * @param searchPaginationService the pagination service
     * @param bookDetailResponseUseCase use case for detail response enrichment and side effects
     * @param recommendationCardResponseUseCase use case for recommendation DTO ordering/mapping
     * @param recommendationService service responsible for recommendation regeneration
     * @param bookDataOrchestrator the data orchestrator, or null when disabled
     */
    public BookController(BookSearchService bookSearchService,
                          BookIdentifierResolver bookIdentifierResolver,
                          SearchPaginationService searchPaginationService,
                          BookDetailResponseUseCase bookDetailResponseUseCase,
                          RecommendationCardResponseUseCase recommendationCardResponseUseCase,
                          RecommendationService recommendationService,
                          BookDataOrchestrator bookDataOrchestrator) {
        this.bookSearchService = bookSearchService;
        this.bookIdentifierResolver = bookIdentifierResolver;
        this.searchPaginationService = searchPaginationService;
        this.bookDetailResponseUseCase = bookDetailResponseUseCase;
        this.recommendationCardResponseUseCase = recommendationCardResponseUseCase;
        this.recommendationService = recommendationService;
        this.bookDataOrchestrator = bookDataOrchestrator;
    }

    /**
     * Searches books using offset-based pagination and deterministic provider ordering.
     *
     * <p>{@code startIndex} is a zero-based absolute offset and {@code maxResults}
     * controls page size. UI routes may use a one-based {@code page} query parameter,
     * but clients must convert that value to {@code startIndex} before calling this API.</p>
     */
    @GetMapping("/search")
    public Mono<ResponseEntity<SearchResponse>> searchBooks(@RequestParam String query,
                                                            SearchFilters filters) {
        String normalizedQuery = SearchQueryUtils.normalize(query);
        if (StringUtils.hasText(filters.orderBy())
            && !SearchExternalProviderUtils.isSupportedOrderBy(filters.orderBy())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid orderBy parameter: Supported values: "
                    + String.join(", ", SearchExternalProviderUtils.supportedOrderByValues()));
        }
        CoverImageSource coverSourcePreference = EnumParsingUtils.parseOrDefault(
            filters.effectiveCoverSource(),
            CoverImageSource.class,
            CoverImageSource.ANY,
            raw -> log.debug("Invalid coverSource '{}' supplied to search endpoint, defaulting to ANY", raw)
        );
        ImageResolutionPreference resolutionPreference = EnumParsingUtils.parseOrDefault(
            filters.effectiveResolution(),
            ImageResolutionPreference.class,
            ImageResolutionPreference.ANY,
            raw -> log.debug("Invalid resolution '{}' supplied to search endpoint, defaulting to ANY", raw)
        );
        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            normalizedQuery,
            filters.effectiveStartIndex(),
            filters.effectiveMaxResults(),
            filters.effectiveOrderBy(),
            coverSourcePreference,
            resolutionPreference,
            filters.publishedYear()
        );

        return searchPaginationService.search(request)
            .map(SearchContractMapper::fromSearchPage)
            .map(ResponseEntity::ok)
            .onErrorResume(ex -> {
                if (ex instanceof ResponseStatusException responseStatusException) {
                    return Mono.error(responseStatusException);
                }
                log.error("Failed to search books for query '{}': {}", normalizedQuery, ex.getMessage(), ex);
                return Mono.error(new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    String.format("Failed to search books for query '%s'", normalizedQuery),
                    ex
                ));
            });
    }

    @GetMapping("/authors/search")
    public Mono<ResponseEntity<AuthorSearchResponse>> searchAuthors(@RequestParam String query,
                                                                    @RequestParam(name = "limit", defaultValue = "10") int limit) {
        String normalizedQuery = SearchQueryUtils.normalize(query);
        int safeLimit = PagingUtils.safeLimit(
            limit,
            ApplicationConstants.Paging.DEFAULT_AUTHOR_LIMIT,
            ApplicationConstants.Paging.MIN_AUTHOR_LIMIT,
            ApplicationConstants.Paging.MAX_AUTHOR_LIMIT
        );

        Mono<AuthorSearchResponse> responseMono = Mono.fromCallable(() -> bookSearchService.searchAuthors(normalizedQuery, safeLimit))
            .subscribeOn(Schedulers.boundedElastic())
            .map(results -> SearchContractMapper.fromAuthorResults(normalizedQuery, safeLimit, results));

        return responseMono
            .map(ResponseEntity::ok)
            .onErrorResume(ex -> {
                if (ex instanceof ResponseStatusException responseStatusException) {
                    return Mono.error(responseStatusException);
                }
                String errorDetail = String.format("Failed to search authors for query '%s'", normalizedQuery);
                log.error("{}: {}", errorDetail, ex.getMessage(), ex);
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, errorDetail, ex));
            });
    }

    @GetMapping("/{identifier}")
    public Mono<ResponseEntity<BookDto>> getBookByIdentifier(@PathVariable String identifier,
                                                              @RequestParam(name = "viewWindow", required = false) String viewWindow) {
        return resolveBookRequest(
            identifier,
            String.format("No book found for identifier: %s", identifier),
            String.format("Failed to fetch book '%s'", identifier),
            viewWindow
        );
    }

    /**
     * Alias route for explicitly slug-based lookups.
     * Delegates to the same fetchBook logic that handles slugs, IDs, ISBNs, etc.
     */
    @GetMapping("/slug/{slug}")
    public Mono<ResponseEntity<BookDto>> getBookBySlug(@PathVariable String slug,
                                                        @RequestParam(name = "viewWindow", required = false) String viewWindow) {
        return resolveBookRequest(
            slug,
            String.format("No book found for slug: %s", slug),
            String.format("Failed to fetch book by slug '%s'", slug),
            viewWindow
        );
    }

    @GetMapping("/{identifier}/similar")
    public Mono<ResponseEntity<List<BookDto>>> getSimilarBooks(@PathVariable String identifier,
                                                               @RequestParam(name = "limit", defaultValue = "5") int limit) {
        int safeLimit = PagingUtils.safeLimit(
            limit,
            ApplicationConstants.Paging.DEFAULT_SIMILAR_LIMIT,
            ApplicationConstants.Paging.MIN_SEARCH_LIMIT,
            ApplicationConstants.Paging.MAX_SIMILAR_LIMIT
        );
        Mono<List<BookDto>> similarBooks = Mono.defer(() -> {
            Optional<UUID> maybeUuid = bookIdentifierResolver.resolveToUuid(identifier);
            if (maybeUuid.isEmpty()) {
                return Mono.<List<BookDto>>empty();
            }
            UUID sourceUuid = maybeUuid.get();
            return resolveSimilarBooks(identifier, sourceUuid, safeLimit);
        });

        return ReactiveControllerUtils.withErrorHandling(
            similarBooks,
            String.format("Failed to load similar books for '%s'", identifier)
        );
    }

    private Mono<List<BookDto>> resolveSimilarBooks(String identifier, UUID sourceUuid, int safeLimit) {
        return Mono.fromCallable(() -> {
                List<net.findmybook.dto.RecommendationCard> cards = bookSearchService.fetchRecommendationCards(sourceUuid, safeLimit);
                boolean hasActiveRows = bookSearchService.hasActiveRecommendationCards(sourceUuid);
                return new SimilarCacheSnapshot(cards, hasActiveRows);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(cacheSnapshot -> {
                List<BookDto> cachedDtos = recommendationCardResponseUseCase.mapRecommendationCards(cacheSnapshot.cards());
                boolean hasActiveRows = cacheSnapshot.hasActiveRows();
                if (hasActiveRows && !cachedDtos.isEmpty()) {
                    return Mono.just(cachedDtos);
                }

                log.info("Recommendation cache is stale or empty for '{}'; regenerating recommendations.", identifier);
                return recommendationService.regenerateSimilarBooks(identifier, safeLimit)
                    .flatMap(generatedBooks -> Mono
                        .fromCallable(() -> bookSearchService.fetchRecommendationCards(sourceUuid, safeLimit))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(refreshedCards -> toSimilarBookDtos(new SimilarBookRefreshContext(safeLimit, cachedDtos, generatedBooks, refreshedCards))));
            });
    }

    private List<BookDto> toSimilarBookDtos(SimilarBookRefreshContext context) {
        List<BookDto> refreshedDtos = recommendationCardResponseUseCase.mapRecommendationCards(context.refreshedCards());
        if (!refreshedDtos.isEmpty()) {
            return refreshedDtos;
        }
        if (!context.cachedDtos().isEmpty()) {
            return context.cachedDtos();
        }
        if (context.generatedBooks() == null || context.generatedBooks().isEmpty()) {
            return List.of();
        }
        return context.generatedBooks().stream()
            .map(BookDtoMapper::toDto)
            .filter(Objects::nonNull)
            .limit(context.safeLimit())
            .toList();
    }

    private record SimilarCacheSnapshot(List<net.findmybook.dto.RecommendationCard> cards, boolean hasActiveRows) {
    }

    private record SimilarBookRefreshContext(int safeLimit,
                                             List<BookDto> cachedDtos,
                                             List<Book> generatedBooks,
                                             List<net.findmybook.dto.RecommendationCard> refreshedCards) {
    }

    private Mono<ResponseEntity<BookDto>> resolveBookRequest(String identifier,
                                                             String notFoundDetail,
                                                             String failureDetail,
                                                             @Nullable String rawViewWindow) {
        bookDetailResponseUseCase.validateViewWindow(rawViewWindow);
        return findBookDto(identifier)
            .flatMap(dto -> Mono.fromCallable(() -> bookDetailResponseUseCase.enrichDetailResponse(dto, rawViewWindow))
                .subscribeOn(Schedulers.boundedElastic()))
            .map(ResponseEntity::ok)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundDetail)))
            .onErrorResume(ex -> {
                if (ex instanceof ResponseStatusException responseStatusException) {
                    return Mono.error(responseStatusException);
                }
                log.error("{}: {}", failureDetail, ex.getMessage(), ex);
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failureDetail, ex));
            });
    }

    private BookDetail enrichWithEditions(BookDetail detail) {
        if (detail == null || !StringUtils.hasText(detail.id())) {
            return detail;
        }
        UUID uuid = UuidUtils.parseUuidOrNull(detail.id());
        if (uuid == null) {
            return detail;
        }
        List<net.findmybook.dto.EditionSummary> editions = bookSearchService.fetchBookEditions(uuid);
        if (editions == null || editions.isEmpty()) {
            return detail;
        }
        return detail.withEditions(editions);
    }

    private Mono<BookDto> findBookDto(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Mono.empty();
        }

        String trimmed = identifier.trim();
        Mono<BookDto> resolvedBook = Mono.fromCallable(() -> locateBookDto(trimmed))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(Mono::justOrEmpty)
            .switchIfEmpty(fetchBookViaFallback(trimmed));

        return resolvedBook;
    }

    private Optional<BookDto> locateBookDto(String identifier) {
        Optional<BookDto> canonicalBook = resolveCanonicalBookDto(identifier);
        if (canonicalBook.isPresent()) {
            return canonicalBook;
        }

        Optional<BookDetail> bySlug = bookSearchService.fetchBookDetailBySlug(identifier);
        if (bySlug.isPresent()) {
            return Optional.of(BookDtoMapper.fromDetail(enrichWithEditions(bySlug.get())));
        }

        Optional<String> canonicalId = bookIdentifierResolver.resolveCanonicalId(identifier);
        if (canonicalId.isEmpty()) {
            return Optional.empty();
        }

        UUID uuid = UuidUtils.parseUuidOrNull(canonicalId.get());
        if (uuid == null) {
            return Optional.empty();
        }

        return bookSearchService.fetchBookDetail(uuid)
            .map(this::enrichWithEditions)
            .map(BookDtoMapper::fromDetail);
    }

    private Optional<BookDto> resolveCanonicalBookDto(String identifier) {
        if (!StringUtils.hasText(identifier) || bookDataOrchestrator == null) {
            return Optional.empty();
        }

        Optional<Book> bySlug = bookDataOrchestrator.getBookFromDatabaseBySlug(identifier);
        if (bySlug.isPresent()) {
            return bySlug.map(BookDtoMapper::toDto);
        }

        return bookDataOrchestrator.getBookFromDatabase(identifier).map(BookDtoMapper::toDto);
    }

    private Mono<BookDto> fetchBookViaFallback(String identifier) {
        if (bookDataOrchestrator == null) {
            return Mono.empty();
        }
        return bookDataOrchestrator.fetchCanonicalBookReactive(identifier)
            .map(BookDtoMapper::toDto)
            .doOnNext(bookDto -> log.debug("BookController fallback resolved '{}' via orchestrator", identifier));
    }

}
