package net.findmybook.application.book;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.findmybook.controller.dto.BookDto;
import net.findmybook.controller.dto.BookDtoMapper;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.util.cover.CoverPrioritizer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Maps recommendation-card domain payloads into stable API DTO responses.
 *
 * <p>Centralizes recommendation-specific DTO shaping and deterministic cover-priority
 * ordering so controllers stay focused on HTTP concerns.</p>
 */
@Service
public class RecommendationCardResponseUseCase {

    /**
     * Maps and sorts recommendation cards for API responses.
     *
     * @param cards recommendation card payloads
     * @return sorted recommendation DTO list
     */
    public List<BookDto> mapRecommendationCards(List<RecommendationCard> cards) {
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

    private BookDto toRecommendationDto(RecommendationCard card) {
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
}
