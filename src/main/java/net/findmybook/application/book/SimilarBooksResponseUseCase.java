package net.findmybook.application.book;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.findmybook.application.similarity.BookSimilarityEmbeddingService;
import net.findmybook.application.similarity.BookSimilarityEmbeddingService.SimilarBookMatch;
import net.findmybook.controller.dto.BookDto;
import net.findmybook.controller.dto.BookDtoMapper;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.model.Book;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.RecommendationService;
import net.findmybook.util.cover.CoverPrioritizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Application use case that resolves similar-book responses from cache and regeneration flows.
 *
 * <p>This use case keeps recommendation cache semantics out of controller HTTP logic
 * while preserving deterministic fallback ordering for stale cached rows.</p>
 */
@Service
public class SimilarBooksResponseUseCase {

    private static final Logger log = LoggerFactory.getLogger(SimilarBooksResponseUseCase.class);
    /** Recommendation reason emitted when persisted book-similarity vectors served the result. */
    public static final String EMBEDDING_REASON = "EMBEDDING_SIMILARITY";
    /** Recommendation source emitted when persisted book-similarity vectors served the result. */
    public static final String EMBEDDING_SOURCE = "BOOK_SIMILARITY_EMBEDDING";

    private final BookSearchService bookSearchService;
    private final RecommendationService recommendationService;
    private final BookSimilarityEmbeddingService bookSimilarityEmbeddingService;

    public SimilarBooksResponseUseCase(BookSearchService bookSearchService,
                                       RecommendationService recommendationService,
                                       BookSimilarityEmbeddingService bookSimilarityEmbeddingService) {
        this.bookSearchService = bookSearchService;
        this.recommendationService = recommendationService;
        this.bookSimilarityEmbeddingService = bookSimilarityEmbeddingService;
    }

    /**
     * Resolves similar books for a canonical source UUID.
     *
     * @param identifier client-supplied identifier used for logging and regeneration
     * @param sourceUuid canonical source book UUID
     * @param safeLimit bounded maximum number of results to return
     * @return embedding-backed, cached, or regenerated recommendation DTOs
     */
    public Mono<List<BookDto>> resolveSimilarBooks(String identifier, UUID sourceUuid, int safeLimit) {
        return Mono.fromCallable(() -> {
                bookSimilarityEmbeddingService.enqueueDemandRefresh(sourceUuid);
                return fetchEmbeddingBookDtos(sourceUuid, safeLimit);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(embeddingFailure -> {
                log.warn(
                    "Embedding similar-book lookup failed for '{}'; using recommendation fallback.",
                    identifier,
                    embeddingFailure
                );
                return Mono.just(List.of());
            })
            .flatMap(embeddingDtos -> {
                if (!embeddingDtos.isEmpty()) {
                    return Mono.just(embeddingDtos);
                }
                return resolveRecommendationFallback(identifier, sourceUuid, safeLimit);
            });
    }

    private Mono<List<BookDto>> resolveRecommendationFallback(String identifier, UUID sourceUuid, int safeLimit) {
        return Mono.fromCallable(() -> {
                List<RecommendationCard> cards = bookSearchService.fetchRecommendationCards(sourceUuid, safeLimit);
                boolean hasActiveRows = bookSearchService.hasActiveRecommendationCards(sourceUuid);
                return new SimilarCacheSnapshot(cards, hasActiveRows);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(cacheSnapshot -> {
                List<BookDto> cachedDtos = mapRecommendationCards(cacheSnapshot.cards());
                if (cacheSnapshot.hasActiveRows() && !cachedDtos.isEmpty()) {
                    return Mono.just(cachedDtos);
                }

                log.info("Recommendation cache is stale or empty for '{}'; regenerating recommendations.", identifier);
                return recommendationService.regenerateSimilarBooks(identifier, safeLimit)
                    .flatMap(generatedBooks -> Mono
                        .fromCallable(() -> bookSearchService.fetchRecommendationCards(sourceUuid, safeLimit))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(refreshedCards -> toSimilarBookDtos(new SimilarBookRefreshContext(
                            safeLimit,
                            cachedDtos,
                            generatedBooks,
                            refreshedCards
                        ))))
                    .onErrorResume(regenerationFailure -> {
                        if (!cachedDtos.isEmpty()) {
                            log.warn(
                                "Recommendation regeneration failed for '{}'; serving {} stale cached recommendation cards.",
                                identifier,
                                cachedDtos.size(),
                                regenerationFailure
                            );
                            return Mono.just(cachedDtos);
                        }
                        return Mono.error(regenerationFailure);
                    });
            });
    }

    private List<BookDto> fetchEmbeddingBookDtos(UUID sourceUuid, int safeLimit) {
        List<SimilarBookMatch> matches = bookSimilarityEmbeddingService.findNearestBooks(sourceUuid, safeLimit);
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }

        Map<String, Double> scoresByBookId = new LinkedHashMap<>();
        for (SimilarBookMatch match : matches) {
            if (match.bookId() != null) {
                scoresByBookId.putIfAbsent(match.bookId().toString(), match.similarity());
            }
            if (scoresByBookId.size() >= safeLimit) {
                break;
            }
        }
        if (scoresByBookId.isEmpty()) {
            return List.of();
        }

        List<UUID> rankedBookIds = scoresByBookId.keySet().stream()
            .map(UUID::fromString)
            .toList();
        List<BookCard> cards = bookSearchService.fetchBookCards(rankedBookIds);
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }

        List<RecommendationCard> recommendationCards = cards.stream()
            .map(card -> new RecommendationCard(
                card,
                scoresByBookId.get(card.id()),
                EMBEDDING_REASON,
                EMBEDDING_SOURCE
            ))
            .toList();
        return mapRecommendationCardsInOrder(recommendationCards);
    }

    private List<BookDto> toSimilarBookDtos(SimilarBookRefreshContext context) {
        List<BookDto> refreshedDtos = mapRecommendationCards(context.refreshedCards());
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
            .map(book -> {
                BookDto dto = BookDtoMapper.toDto(book);
                if (dto == null) {
                    log.warn("BookDtoMapper.toDto returned null for book id='{}'; excluding from similar books.", book.getId());
                }
                return dto;
            })
            .filter(Objects::nonNull)
            .limit(context.safeLimit())
            .toList();
    }

    private List<BookDto> mapRecommendationCards(List<RecommendationCard> cards) {
        return mapRecommendationCards(cards, true);
    }

    private List<BookDto> mapRecommendationCardsInOrder(List<RecommendationCard> cards) {
        return mapRecommendationCards(cards, false);
    }

    private List<BookDto> mapRecommendationCards(List<RecommendationCard> cards, boolean prioritizeCovers) {
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
            if (cardsById.putIfAbsent(id, card) != null) {
                continue;
            }
            sortableCards.add(card.card());
            insertionOrder.put(id, index++);
        }

        if (sortableCards.isEmpty()) {
            return List.of();
        }

        if (prioritizeCovers) {
            sortableCards.sort(CoverPrioritizer.cardComparator(insertionOrder));
        }

        List<BookDto> dtos = new ArrayList<>(sortableCards.size());
        for (BookCard payload : sortableCards) {
            BookDto dto = toRecommendationDto(cardsById.get(payload.id()));
            if (dto != null) {
                dtos.add(dto);
            }
        }
        return dtos;
    }

    private static BookDto toRecommendationDto(RecommendationCard card) {
        if (card == null || card.card() == null) {
            return null;
        }
        return BookDtoMapper.fromCard(card.card(), buildRecommendationExtras(card));
    }

    private static Map<String, Object> buildRecommendationExtras(RecommendationCard card) {
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
        return extras;
    }

    private record SimilarCacheSnapshot(List<RecommendationCard> cards, boolean hasActiveRows) {
    }

    private record SimilarBookRefreshContext(int safeLimit,
                                             List<BookDto> cachedDtos,
                                             List<Book> generatedBooks,
                                             List<RecommendationCard> refreshedCards) {
    }
}
