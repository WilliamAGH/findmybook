/**
 * REST controller exposing Postgres-first book APIs.
 */
package net.findmybook.controller;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import net.findmybook.controller.dto.BookDto;
import net.findmybook.controller.dto.BookDtoMapper;
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
import net.findmybook.service.BookSearchService.AuthorResult;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.ReactiveControllerUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.ValidationUtils;
import net.findmybook.util.SlugGenerator;
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
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    @Nullable
    private final BookDataOrchestrator bookDataOrchestrator;

    public BookController(BookSearchService bookSearchService,
                          BookIdentifierResolver bookIdentifierResolver,
                          SearchPaginationService searchPaginationService,
                          @Nullable BookDataOrchestrator bookDataOrchestrator) {
        this.bookSearchService = bookSearchService;
        this.bookIdentifierResolver = bookIdentifierResolver;
        this.searchPaginationService = searchPaginationService;
        this.bookDataOrchestrator = bookDataOrchestrator;
    }

    @GetMapping("/search")
    public Mono<ResponseEntity<SearchResponse>> searchBooks(@RequestParam String query,
                                                            @RequestParam(name = "startIndex", defaultValue = "0") int startIndex,
                                                            @RequestParam(name = "maxResults", defaultValue = "12") int maxResults,
                                                            @RequestParam(name = "orderBy", defaultValue = "newest") String orderBy,
                                                            @RequestParam(name = "publishedYear", required = false) Integer publishedYear,
                                                            @RequestParam(name = "coverSource", defaultValue = "ANY") String coverSource,
                                                            @RequestParam(name = "resolution", defaultValue = "ANY") String resolution) {
        String normalizedQuery = SearchQueryUtils.normalize(query);
        CoverImageSource coverSourcePreference = EnumParsingUtils.parseOrDefault(
            coverSource,
            CoverImageSource.class,
            CoverImageSource.ANY,
            raw -> log.debug("Invalid coverSource '{}' supplied to search endpoint, defaulting to ANY", raw)
        );
        ImageResolutionPreference resolutionPreference = EnumParsingUtils.parseOrDefault(
            resolution,
            ImageResolutionPreference.class,
            ImageResolutionPreference.ANY,
            raw -> log.debug("Invalid resolution '{}' supplied to search endpoint, defaulting to ANY", raw)
        );
        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            normalizedQuery,
            startIndex,
            maxResults,
            orderBy,
            coverSourcePreference,
            resolutionPreference,
            publishedYear
        );

        Mono<SearchResponse> responseMono = searchPaginationService.search(request)
            .map(this::toSearchResponse);

        return withEmptyFallback(
            responseMono,
            () -> emptySearchResponse(normalizedQuery, request),
            () -> String.format("Failed to search books for query '%s'", normalizedQuery)
        );
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
            .map(results -> results == null ? List.<AuthorResult>of() : results)
            .map(results -> buildAuthorResponse(normalizedQuery, safeLimit, results));

        return withEmptyFallback(
            responseMono,
            () -> emptyAuthorResponse(normalizedQuery, safeLimit),
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

    private AuthorSearchResponse buildAuthorResponse(String query,
                                                     int limit,
                                                     List<AuthorResult> results) {
        List<AuthorResult> safeResults = results == null ? List.of() : results;
        List<AuthorHitDto> hits = safeResults.stream()
                .sorted(Comparator.comparingDouble(AuthorResult::relevanceScore).reversed())
                .limit(Math.max(0, limit))
                .map(this::toAuthorHit)
                .toList();
        return new AuthorSearchResponse(query, limit, hits);
    }

    private AuthorHitDto toAuthorHit(AuthorResult authorResult) {
        String effectiveId = authorResult.authorId();
        if (!ValidationUtils.hasText(effectiveId)) {
            String slug = SlugGenerator.slugify(authorResult.authorName());
            if (slug == null || slug.isBlank()) {
                slug = "unknown";
            }
            effectiveId = "external-author-" + slug;
        }
        return new AuthorHitDto(
                effectiveId,
                authorResult.authorName(),
                authorResult.bookCount(),
                authorResult.relevanceScore()
        );
    }

    private SearchResponse toSearchResponse(SearchPaginationService.SearchPage page) {
        List<SearchHitDto> hits = page.pageItems().stream()
            .map(this::toSearchHit)
            .filter(Objects::nonNull)
            .toList();
        String queryHash = SearchQueryUtils.topicKey(page.query());

        return new SearchResponse(
            page.query(),
            queryHash,
            page.startIndex(),
            page.maxResults(),
            page.totalUnique(),
            page.hasMore(),
            page.nextStartIndex(),
            page.prefetchedCount(),
            page.orderBy(),
            page.coverSource() != null ? page.coverSource().name() : CoverImageSource.ANY.name(),
            page.resolutionPreference() != null ? page.resolutionPreference().name() : ImageResolutionPreference.ANY.name(),
            hits
        );
    }

    private SearchResponse emptySearchResponse(String query, SearchPaginationService.SearchRequest request) {
        String queryHash = SearchQueryUtils.topicKey(query);
        return new SearchResponse(
            query,
            queryHash,
            request.startIndex(),
            request.maxResults(),
            0,
            false,
            request.startIndex(),
            0,
            request.orderBy(),
            request.coverSource().name(),
            request.resolutionPreference().name(),
            List.<SearchHitDto>of()
        );
    }

    private AuthorSearchResponse emptyAuthorResponse(String query, int limit) {
        return new AuthorSearchResponse(query, limit, List.of());
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
        if (detail == null || !ValidationUtils.hasText(detail.id())) {
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

    private SearchHitDto toSearchHit(Book book) {
        if (book == null) {
            return null;
        }
        Map<String, Object> extras = Optional.ofNullable(book.getQualifiers()).orElse(Map.of());
        String matchType = Optional.ofNullable(extras.get("search.matchType"))
            .map(Object::toString)
            .orElse(null);
        Double relevance = Optional.ofNullable(extras.get("search.relevanceScore"))
            .map(value -> {
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
                try {
                    return Double.parseDouble(value.toString());
                } catch (NumberFormatException ex) {
                    return null;
                }
            })
            .orElse(null);

        BookDto dto = BookDtoMapper.toDto(book);
        return new SearchHitDto(dto, matchType, relevance);
    }

    private Mono<BookDto> findBookDto(String identifier) {
        if (!ValidationUtils.hasText(identifier)) {
            return Mono.empty();
        }

        String trimmed = identifier.trim();
        return Mono.fromCallable(() -> locateBookDto(trimmed))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(dto -> dto == null ? Mono.<BookDto>empty() : Mono.just(dto))
            .switchIfEmpty(fetchBookViaFallback(trimmed));
    }

    private BookDto locateBookDto(String identifier) {
        Optional<BookDetail> bySlug = bookSearchService.fetchBookDetailBySlug(identifier);
        if (bySlug.isPresent()) {
            return BookDtoMapper.fromDetail(enrichWithEditions(bySlug.get()));
        }

        Optional<String> canonicalId = bookIdentifierResolver.resolveCanonicalId(identifier);
        if (canonicalId.isEmpty()) {
            return null;
        }

        UUID uuid = UuidUtils.parseUuidOrNull(canonicalId.get());
        if (uuid == null) {
            return null;
        }

        return bookSearchService.fetchBookDetail(uuid)
            .map(this::enrichWithEditions)
            .map(BookDtoMapper::fromDetail)
            .orElse(null);
    }

    private Mono<BookDto> fetchBookViaFallback(String identifier) {
        if (bookDataOrchestrator == null) {
            return Mono.empty();
        }
        return bookDataOrchestrator.fetchCanonicalBookReactive(identifier)
            .map(BookDtoMapper::toDto)
            .doOnNext(bookDto -> log.debug("BookController fallback resolved '{}' via orchestrator", identifier));
    }

    private BookDto toRecommendationDto(RecommendationCard card) {
        if (card == null || card.card() == null) {
            return null;
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        if (card.score() != null) {
            extras.put("recommendation.score", card.score());
        }
        if (ValidationUtils.hasText(card.reason())) {
            extras.put("recommendation.reason", card.reason());
        }
        if (ValidationUtils.hasText(card.source())) {
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
            if (card == null || card.card() == null || !ValidationUtils.hasText(card.card().id())) {
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

    private record SearchResponse(String query,
                                  String queryHash,
                                  int startIndex,
                                  int maxResults,
                                  int totalResults,
                                  boolean hasMore,
                                  int nextStartIndex,
                                  int prefetchedCount,
                                  String orderBy,
                                  String coverSource,
                                  String resolution,
                                  List<SearchHitDto> results) {
    }

    private record SearchHitDto(@JsonUnwrapped BookDto book, String matchType, Double relevanceScore) {
    }

    private record AuthorSearchResponse(String query,
                                        int limit,
                                        List<AuthorHitDto> results) {
    }

    private record AuthorHitDto(String id,
                                String name,
                                long bookCount,
                                double relevanceScore) {
    }
}
