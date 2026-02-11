package net.findmybook.application.page;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.findmybook.controller.PageApiPayloads.CategoriesFacetsPayload;
import net.findmybook.controller.PageApiPayloads.CategoryFacetPayload;
import net.findmybook.controller.PageApiPayloads.HomePayload;
import net.findmybook.dto.BookCard;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.adapters.persistence.PageViewEventRepository;
import net.findmybook.service.RecentBookViewRepository;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Use case for assembling public SPA payloads outside the HTTP controller layer.
 *
 * <p>Centralizes homepage card shaping, homepage page-view recording, affiliate lookup,
 * and category facet mapping so controllers remain focused on request/response translation.</p>
 */
@Service
public class PublicPagePayloadUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublicPagePayloadUseCase.class);

    private static final int MAX_BESTSELLERS = 8;
    private static final int MAX_RECENT_BOOKS = 8;
    private static final int DEFAULT_POPULAR_BOOKS = 8;
    private static final int MAX_POPULAR_BOOKS = 24;

    /**
     * Over-fetch multiplier applied to each homepage section's data-source query.
     * After filtering by cover quality the result is trimmed back to the section's
     * display limit, so fetching extra candidates compensates for grayscale and
     * coverless books that get removed by {@link #retainRenderableCovers}.
     */
    private static final int OVERFETCH_MULTIPLIER = 3;

    private static final int DEFAULT_CATEGORY_FACET_LIMIT = 24;
    private static final int MAX_CATEGORY_FACET_LIMIT = 200;
    private static final int DEFAULT_CATEGORY_MIN_BOOKS = 1;

    private static final String HOMEPAGE_PAGE_KEY = "homepage";
    private static final String API_SOURCE = "api";
    private static final String INVALID_POPULAR_WINDOW_DETAIL =
        "Invalid popularWindow parameter: supported values are 30d, 90d, all";

    private final HomePageSectionsService homePageSectionsService;
    private final PageViewEventRepository pageViewEventRepository;
    private final AffiliateLinkService affiliateLinkService;

    /**
     * Creates the public payload use case.
     *
     * @param homePageSectionsService service for homepage sections and book lookups
     * @param pageViewEventRepository repository for non-book page-view event writes
     * @param affiliateLinkService service for outbound affiliate link generation
     */
    public PublicPagePayloadUseCase(HomePageSectionsService homePageSectionsService,
                                    PageViewEventRepository pageViewEventRepository,
                                    AffiliateLinkService affiliateLinkService) {
        this.homePageSectionsService = homePageSectionsService;
        this.pageViewEventRepository = pageViewEventRepository;
        this.affiliateLinkService = affiliateLinkService;
    }

    /**
     * Loads the homepage payload with bestsellers, recently viewed, and popular books.
     *
     * @param rawPopularWindow optional popularity window query value
     * @param rawPopularLimit optional popularity limit query value
     * @param recordView whether to record a homepage page-view event
     * @return asynchronous homepage payload response object
     */
    public Mono<HomePayload> loadHomePayload(@Nullable String rawPopularWindow, @Nullable Integer rawPopularLimit, boolean recordView) {
        RecentBookViewRepository.ViewWindow resolvedPopularWindow = parsePopularWindow(rawPopularWindow);
        int resolvedPopularLimit = PagingUtils.safeLimit(
            rawPopularLimit != null ? rawPopularLimit : 0,
            DEFAULT_POPULAR_BOOKS,
            1,
            MAX_POPULAR_BOOKS
        );

        if (recordView) {
            pageViewEventRepository.recordView(HOMEPAGE_PAGE_KEY, Instant.now(), API_SOURCE);
        }

        Mono<List<BookCard>> bestsellers = homePageSectionsService.loadCurrentBestsellers(MAX_BESTSELLERS * OVERFETCH_MULTIPLIER)
            .map(cards -> retainRenderableCovers(cards, MAX_BESTSELLERS))
            .timeout(Duration.ofSeconds(3));
        Mono<List<BookCard>> recentBooks = homePageSectionsService.loadRecentBooks(MAX_RECENT_BOOKS * OVERFETCH_MULTIPLIER)
            .map(cards -> retainRenderableCovers(cards, MAX_RECENT_BOOKS))
            .timeout(Duration.ofSeconds(3));
        Mono<List<BookCard>> popularBooks = homePageSectionsService.loadPopularBooks(resolvedPopularWindow, resolvedPopularLimit * OVERFETCH_MULTIPLIER)
            .map(cards -> retainRenderableCovers(cards, resolvedPopularLimit))
            .timeout(Duration.ofSeconds(3));

        return Mono.zip(bestsellers, recentBooks, popularBooks)
            .map(tuple -> new HomePayload(
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3(),
                resolvedPopularWindow.queryValue()
            ))
            .onErrorMap(ex -> {
                log.warn("Failed to build /api/pages/home payload: {}", ex.getMessage());
                return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Homepage payload load failed", ex);
            });
    }

    /**
     * Resolves typed affiliate links for a single book identifier.
     *
     * @param identifier slug, UUID, or ISBN identifier
     * @return affiliate links response when a book exists, otherwise 404
     */
    public Mono<ResponseEntity<Map<String, String>>> loadAffiliateLinks(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Book identifier is required"));
        }

        return homePageSectionsService.locateBook(identifier)
            .map(book -> ResponseEntity.ok(affiliateLinkService.generateLinks(book)))
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No book found for identifier: " + identifier)
            ));
    }

    /**
     * Resolves category facets with validated pagination/filter bounds.
     *
     * @param limit optional max facet count
     * @param minBooks optional minimum books threshold
     * @return typed category facets payload
     */
    public ResponseEntity<CategoriesFacetsPayload> loadCategoryFacets(@Nullable Integer limit, @Nullable Integer minBooks) {
        int safeLimit = PagingUtils.safeLimit(
            limit != null ? limit : 0,
            DEFAULT_CATEGORY_FACET_LIMIT,
            1,
            MAX_CATEGORY_FACET_LIMIT
        );
        int safeMinBooks = minBooks == null ? DEFAULT_CATEGORY_MIN_BOOKS : Math.max(0, minBooks);
        List<CategoryFacetPayload> genres = homePageSectionsService.loadCategoryFacets(safeLimit, safeMinBooks).stream()
            .map(facet -> new CategoryFacetPayload(facet.name(), facet.bookCount()))
            .toList();
        return ResponseEntity.ok(new CategoriesFacetsPayload(
            genres,
            Instant.now(),
            safeLimit,
            safeMinBooks
        ));
    }

    private static List<BookCard> retainRenderableCovers(List<BookCard> cards, int maxItems) {
        if (cards == null || cards.isEmpty() || maxItems <= 0) {
            return List.of();
        }

        List<BookCard> filtered = new ArrayList<>(Math.min(cards.size(), maxItems));
        for (BookCard card : cards) {
            if (card == null || !CoverPrioritizer.hasColorCover(card)) {
                continue;
            }
            filtered.add(card);
            if (filtered.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(filtered);
    }

    private static RecentBookViewRepository.ViewWindow parsePopularWindow(@Nullable String rawPopularWindow) {
        if (!StringUtils.hasText(rawPopularWindow)) {
            return RecentBookViewRepository.ViewWindow.LAST_30_DAYS;
        }
        return RecentBookViewRepository.ViewWindow.fromQueryValue(rawPopularWindow)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_POPULAR_WINDOW_DETAIL));
    }
}
