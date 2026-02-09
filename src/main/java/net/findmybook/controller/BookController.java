/**
 * REST controller exposing Postgres-first book APIs.
 */
package net.findmybook.controller;

import net.findmybook.application.ai.BookAiContentService;
import net.findmybook.controller.dto.BookAiContentSnapshotDto;
import net.findmybook.controller.dto.BookDto;
import net.findmybook.controller.dto.BookDtoMapper;
import net.findmybook.controller.dto.search.AuthorSearchResponse;
import net.findmybook.controller.dto.search.SearchContractMapper;
import net.findmybook.controller.dto.search.SearchFilters;
import net.findmybook.controller.dto.search.SearchResponse;
import org.springframework.web.server.ResponseStatusException;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.ReactiveControllerUtils;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.SearchQueryUtils;
import org.springframework.util.StringUtils;
import net.findmybook.util.EnumParsingUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import net.findmybook.util.UuidUtils;

@RestController
@RequestMapping("/api/books")
@Slf4j
public class BookController {
    private final BookSearchService bookSearchService;
    private final BookIdentifierResolver bookIdentifierResolver;
    private final SearchPaginationService searchPaginationService;
    private final BookAiContentService bookAiContentService;
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
     * @param bookAiContentService service for cache-first AI reader-fit snapshots
     * @param bookDataOrchestrator the data orchestrator, or null when disabled
     */
    public BookController(BookSearchService bookSearchService,
                          BookIdentifierResolver bookIdentifierResolver,
                          SearchPaginationService searchPaginationService,
                          BookAiContentService bookAiContentService,
                          BookDataOrchestrator bookDataOrchestrator) {
        this.bookSearchService = bookSearchService;
        this.bookIdentifierResolver = bookIdentifierResolver;
        this.searchPaginationService = searchPaginationService;
        this.bookAiContentService = bookAiContentService;
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
                log.error("Failed to search books for query '{}': {}. Returning explicit error status.",
                    normalizedQuery, ex.getMessage(), ex);
                return Mono.fromSupplier(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(SearchContractMapper.emptySearchResponse(normalizedQuery, request)));
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

        return withEmptyFallback(
            responseMono,
            () -> SearchContractMapper.emptyAuthorSearchResponse(normalizedQuery, safeLimit),
            () -> String.format("Failed to search authors for query '%s'", normalizedQuery)
        );
    }

    @GetMapping("/{identifier}")
    public Mono<ResponseEntity<BookDto>> getBookByIdentifier(@PathVariable String identifier) {
        return ReactiveControllerUtils.withErrorHandling(
            findBookDto(identifier),
            String.format("Failed to fetch book '%s'", identifier)
        );
    }

    /**
     * Alias route for explicitly slug-based lookups.
     * Delegates to the same fetchBook logic that handles slugs, IDs, ISBNs, etc.
     */
    @GetMapping("/slug/{slug}")
    public Mono<ResponseEntity<BookDto>> getBookBySlug(@PathVariable String slug) {
        return ReactiveControllerUtils.withErrorHandling(
            findBookDto(slug),
            String.format("Failed to fetch book by slug '%s'", slug)
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
            return Mono.fromCallable(() -> bookSearchService.fetchRecommendationCards(maybeUuid.get(), safeLimit))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::mapRecommendationCards);
        });

        return ReactiveControllerUtils.withErrorHandling(
            similarBooks,
            String.format("Failed to load similar books for '%s'", identifier)
        );
    }

    private <T> Mono<ResponseEntity<T>> withEmptyFallback(Mono<T> pipeline,
                                                          Supplier<T> emptySupplier,
                                                          Supplier<String> contextSupplier) {
        return pipeline
            .map(ResponseEntity::ok)
            .onErrorResume(ex -> {
                log.error("{}: {}. Returning explicit error status.", contextSupplier.get(), ex.getMessage(), ex);
                return Mono.fromSupplier(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(emptySupplier.get()));
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

        return resolvedBook.flatMap(dto -> Mono.fromCallable(() -> attachAiContentSnapshot(dto))
            .subscribeOn(Schedulers.boundedElastic()));
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

    private BookDto attachAiContentSnapshot(BookDto bookDto) {
        if (bookDto == null || !StringUtils.hasText(bookDto.id())) {
            return bookDto;
        }

        UUID bookId = UuidUtils.parseUuidOrNull(bookDto.id());
        if (bookId == null) {
            return bookDto;
        }

        return bookAiContentService.findCurrent(bookId)
            .map(BookAiContentSnapshotDto::fromSnapshot)
            .map(bookDto::withAiContent)
            .orElse(bookDto);
    }

    private BookDto toRecommendationDto(RecommendationCard card) {
        if (card == null || card.card() == null) {
            return null;
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        if (card.score() != null) {
            extras.put("recommendation.score", card.score());
        }
        if (StringUtils.hasText(card.reason())) {
            extras.put("recommendation.reason", card.reason());
        }
        if (StringUtils.hasText(card.source())) {
            extras.put("recommendation.source", card.source());
        }
        return BookDtoMapper.fromCard(card.card(), extras);
    }

    private List<BookDto> mapRecommendationCards(List<RecommendationCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }

        Map<String, RecommendationCard> cardsById = new LinkedHashMap<>();
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        List<BookCard> sortableCards = new ArrayList<>();
        int index = 0;

        for (RecommendationCard card : cards) {
            if (card == null || card.card() == null || !StringUtils.hasText(card.card().id())) {
                continue;
            }
            String id = card.card().id();
            cardsById.putIfAbsent(id, card);
            sortableCards.add(card.card());
            insertionOrder.putIfAbsent(id, index++);
        }

        if (sortableCards.isEmpty()) {
            return List.of();
        }

        sortableCards.sort(CoverPrioritizer.cardComparator(insertionOrder));

        List<BookDto> dtos = new ArrayList<>(sortableCards.size());
        for (BookCard payload : sortableCards) {
            RecommendationCard wrapper = cardsById.get(payload.id());
            BookDto dto = toRecommendationDto(wrapper);
            if (dto != null) {
                dtos.add(dto);
            }
        }
        return dtos;
    }

}
