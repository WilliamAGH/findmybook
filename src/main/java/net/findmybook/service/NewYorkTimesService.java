/**
 * Service for interacting with the New York Times Books API
 * This class is responsible for fetching data from the New York Times Best Sellers API
 * It handles:
 * - Constructing and executing API requests to retrieve current bestseller lists
 * - Converting JSON responses from the NYT API into {@link Book} domain objects
 * - Error handling for API communication issues
 * - Transforming NYT book data format into the application's Book model
 *
 * @author William Callahan
 */
package net.findmybook.service;

import com.fasterxml.jackson.databind.JsonNode;
import net.findmybook.dto.BookCard;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.LoggingUtils;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.ValidationUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class NewYorkTimesService {
    
    // S3 no longer used as read source for NYT bestsellers
    private final WebClient webClient;
    private final String nytApiBaseUrl;

    private final String nytApiKey;

    /**
     * THE SINGLE SOURCE OF TRUTH for book queries.
     * All new code must use this repository.
     */
    private final BookQueryRepository bookQueryRepository;
    
    private static final DateTimeFormatter API_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Preferred constructor using BookQueryRepository (THE SINGLE SOURCE OF TRUTH).
     * Spring will automatically inject all dependencies.
     */
    public NewYorkTimesService(WebClient.Builder webClientBuilder,
                               @Value("${nyt.api.base-url:https://api.nytimes.com/svc/books/v3}") String nytApiBaseUrl,
                               @Value("${nyt.api.key}") String nytApiKey,
                               @Nullable BookQueryRepository bookQueryRepository) {
        this.nytApiBaseUrl = nytApiBaseUrl;
        this.nytApiKey = nytApiKey;
        this.webClient = webClientBuilder.baseUrl(nytApiBaseUrl).build();
        this.bookQueryRepository = bookQueryRepository;
    }

    /**
     * Fetches the full bestseller list overview directly from the New York Times API
     * This is intended for use by the scheduler
     *
     * @return Mono<JsonNode> representing the API response, or empty on error
     */
    public Mono<JsonNode> fetchBestsellerListOverview() {
        return fetchBestsellerListOverview(null);
    }

    public Mono<JsonNode> fetchBestsellerListOverview(@Nullable LocalDate publishedDate) {
        StringBuilder overviewUrl = new StringBuilder("/lists/overview.json?api-key=").append(nytApiKey);
        if (publishedDate != null) {
            overviewUrl.append("&published_date=").append(publishedDate.format(API_DATE_FORMAT));
        }
        String maskedUrl = "/lists/overview.json?api-key=****" + (publishedDate != null ? "&published_date=" + publishedDate.format(API_DATE_FORMAT) : "");
        log.info("Fetching NYT bestseller list overview from API: {}{}", nytApiBaseUrl, maskedUrl);
        return webClient.mutate()
                .baseUrl(nytApiBaseUrl)
                .build()
                .get()
                .uri(overviewUrl.toString())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorResume(e -> {
                    LoggingUtils.error(log, e, "Error fetching NYT bestseller list overview from API for date {}", publishedDate);
                    return Mono.empty();
                });
    }


    /**
     * Fetch the latest published list's books for the given provider list code from Postgres.
     * Returns BookCard DTOs as THE SINGLE SOURCE OF TRUTH.
     * 
     * Performance: Single optimized query instead of 5+ queries per book.
     * 
     * @param listNameEncoded NYT list code (e.g., "hardcover-fiction")
     * @param limit Maximum number of books to return
     * @return Mono of BookCard list (optimized DTOs for card display)
     */
    @Cacheable(value = "nytBestsellersCurrent", key = "#listNameEncoded + '-' + T(net.findmybook.util.PagingUtils).clamp(#limit, 1, 100) + '-v2'")
    public Mono<List<BookCard>> getCurrentBestSellersCards(String listNameEncoded, int limit) {
        // Validate and clamp limit to reasonable range
        final int effectiveLimit = PagingUtils.clamp(limit, 1, 100);

        // Use BookQueryRepository as THE SINGLE SOURCE
        if (bookQueryRepository != null) {
            return Mono.fromCallable(() -> {
                List<BookCard> cards = new ArrayList<>(bookQueryRepository.fetchBookCardsByProviderListCode(listNameEncoded, effectiveLimit));
                log.info("BookQueryRepository returned {} book cards for list '{}' (optimized query)", cards.size(), listNameEncoded);

                if (!cards.isEmpty()) {
                    Map<String, Integer> insertionOrder = new LinkedHashMap<>();
                    for (int idx = 0; idx < cards.size(); idx++) {
                        BookCard card = cards.get(idx);
                        if (card != null && ValidationUtils.hasText(card.id())) {
                            insertionOrder.putIfAbsent(card.id(), idx);
                        }
                    }
                    cards.sort(CoverPrioritizer.cardComparator(insertionOrder));
                }

                return cards;
            })
            .timeout(Duration.ofMillis(3000)) // 3s timeout for Postgres query
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                if (e instanceof java.util.concurrent.TimeoutException) {
                    log.error("Timeout fetching bestsellers for list '{}' after 3000ms", listNameEncoded);
                } else {
                    LoggingUtils.error(log, e, "DB error fetching current bestsellers for list '{}'", listNameEncoded);
                }
                return Mono.just(Collections.emptyList());
            });
        }
        
        // No BookQueryRepository available
        log.error("BookQueryRepository not injected - cannot fetch bestsellers");
        return Mono.just(Collections.emptyList());
    }
    
}
