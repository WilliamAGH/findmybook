package net.findmybook.application.book;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.findmybook.controller.dto.BookDto;
import net.findmybook.controller.dto.BookDtoMapper;
import net.findmybook.model.Book;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.RecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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

    private final BookSearchService bookSearchService;
    private final RecommendationCardResponseUseCase recommendationCardResponseUseCase;
    private final RecommendationService recommendationService;

    public SimilarBooksResponseUseCase(BookSearchService bookSearchService,
                                       RecommendationCardResponseUseCase recommendationCardResponseUseCase,
                                       RecommendationService recommendationService) {
        this.bookSearchService = bookSearchService;
        this.recommendationCardResponseUseCase = recommendationCardResponseUseCase;
        this.recommendationService = recommendationService;
    }

    /**
     * Resolves similar books for a canonical source UUID.
     *
     * @param identifier client-supplied identifier used for logging and regeneration
     * @param sourceUuid canonical source book UUID
     * @param safeLimit bounded maximum number of results to return
     * @return cached or regenerated recommendation DTOs
     */
    public Mono<List<BookDto>> resolveSimilarBooks(String identifier, UUID sourceUuid, int safeLimit) {
        return Mono.fromCallable(() -> {
                List<net.findmybook.dto.RecommendationCard> cards = bookSearchService.fetchRecommendationCards(sourceUuid, safeLimit);
                boolean hasActiveRows = bookSearchService.hasActiveRecommendationCards(sourceUuid);
                return new SimilarCacheSnapshot(cards, hasActiveRows);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(cacheSnapshot -> {
                List<BookDto> cachedDtos = recommendationCardResponseUseCase.mapRecommendationCards(cacheSnapshot.cards());
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

    private record SimilarCacheSnapshot(List<net.findmybook.dto.RecommendationCard> cards, boolean hasActiveRows) {
    }

    private record SimilarBookRefreshContext(int safeLimit,
                                             List<BookDto> cachedDtos,
                                             List<Book> generatedBooks,
                                             List<net.findmybook.dto.RecommendationCard> refreshedCards) {
    }
}
