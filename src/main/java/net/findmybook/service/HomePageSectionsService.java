package net.findmybook.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.model.Book;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.UuidUtils;
import org.springframework.util.StringUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Coordinates homepage and book-detail section loading outside the HTTP layer.
 */
@Service
@Slf4j
public class HomePageSectionsService {

    private final RecentlyViewedService recentlyViewedService;
    private final NewYorkTimesService newYorkTimesService;
    private final BookSearchService bookSearchService;
    private final BookIdentifierResolver bookIdentifierResolver;

    public HomePageSectionsService(RecentlyViewedService recentlyViewedService,
                                   NewYorkTimesService newYorkTimesService,
                                   BookSearchService bookSearchService,
                                   BookIdentifierResolver bookIdentifierResolver) {
        this.recentlyViewedService = recentlyViewedService;
        this.newYorkTimesService = newYorkTimesService;
        this.bookSearchService = bookSearchService;
        this.bookIdentifierResolver = bookIdentifierResolver;
    }

    public Mono<List<BookCard>> loadCurrentBestsellers(int maxBestsellers) {
        return newYorkTimesService.getCurrentBestSellersCards("hardcover-fiction", maxBestsellers)
            .map(cards -> cards.stream().limit(maxBestsellers).collect(Collectors.toList()));
    }

    public Mono<List<BookCard>> loadRecentBooks(int maxRecentBooks) {
        return Mono.fromCallable(() -> {
            List<String> bookIds = recentlyViewedService.getRecentlyViewedBookIds(maxRecentBooks);
            if (bookIds.isEmpty()) {
                log.debug("No recently viewed books, returning empty list");
                return List.<BookCard>of();
            }

            List<UUID> uuids = bookIds.stream()
                .map(identifier -> {
                    UUID direct = UuidUtils.parseUuidOrNull(identifier);
                    if (direct != null) {
                        return direct;
                    }
                    return bookIdentifierResolver.resolveToUuid(identifier).orElseGet(() -> {
                        log.warn("Unable to resolve recently viewed identifier '{}' to UUID", identifier);
                        return null;
                    });
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

            if (uuids.isEmpty()) {
                return List.<BookCard>of();
            }

            List<BookCard> cards = bookSearchService.fetchBookCards(uuids);
            if (cards.isEmpty()) {
                return List.<BookCard>of();
            }

            Map<String, Integer> originalOrder = new LinkedHashMap<>();
            for (int index = 0; index < uuids.size(); index++) {
                originalOrder.putIfAbsent(uuids.get(index).toString(), index);
            }

            List<BookCard> ordered = new ArrayList<>(cards);
            ordered.sort(Comparator.comparingInt(card -> originalOrder.getOrDefault(card.id(), Integer.MAX_VALUE)));
            log.debug("Loaded {} recent books as BookCard DTOs", ordered.size());
            return ordered;
        })
            .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Book>> loadSimilarBooks(String bookIdentifier, int recommendationLimit, int maxReturnedBooks) {
        return Mono.fromCallable(() -> bookIdentifierResolver.resolveToUuid(bookIdentifier))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(optionalUuid -> {
                if (optionalUuid.isEmpty()) {
                    return Mono.just(List.<Book>of());
                }
                UUID uuid = optionalUuid.get();
                return Mono.defer(() -> {
                    List<RecommendationCard> cards = bookSearchService.fetchRecommendationCards(uuid, recommendationLimit);
                    if (cards == null || cards.isEmpty()) {
                        return Mono.just(List.<Book>of());
                    }
                    List<Book> mapped = new ArrayList<>(cards.size());
                    for (RecommendationCard card : cards) {
                        if (card == null || card.card() == null) {
                            continue;
                        }
                        Book book = BookDomainMapper.fromCard(card.card());
                        if (book != null) {
                            mapped.add(book);
                        }
                        if (mapped.size() >= maxReturnedBooks) {
                            break;
                        }
                    }
                    if (!mapped.isEmpty()) {
                        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
                        for (int index = 0; index < mapped.size(); index++) {
                            Book candidate = mapped.get(index);
                            if (candidate != null && StringUtils.hasText(candidate.getId())) {
                                insertionOrder.putIfAbsent(candidate.getId(), index);
                            }
                        }
                        mapped.sort(CoverPrioritizer.bookComparator(insertionOrder));
                    }
                    return Mono.just(mapped);
                }).subscribeOn(Schedulers.boundedElastic());
            })
            .timeout(Duration.ofMillis(1500));
    }

    public Mono<Book> locateBook(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Mono.empty();
        }
        String trimmed = identifier.trim();
        return Mono.fromCallable(() -> findBook(trimmed))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    public void recordRecentlyViewed(Book book) {
        if (book == null) {
            throw new IllegalArgumentException("Cannot record recently viewed state for null book");
        }
        recentlyViewedService.addToRecentlyViewed(book);
    }

    private Optional<Book> findBook(String identifier) {
        Optional<BookDetail> bySlug = bookSearchService.fetchBookDetailBySlug(identifier);
        if (bySlug.isPresent()) {
            return Optional.of(BookDomainMapper.fromDetail(bySlug.get()));
        }

        Optional<UUID> maybeUuid = bookIdentifierResolver.resolveToUuid(identifier);
        if (maybeUuid.isEmpty()) {
            return Optional.empty();
        }
        UUID uuid = maybeUuid.get();

        Optional<BookDetail> detail = bookSearchService.fetchBookDetail(uuid);
        if (detail.isPresent()) {
            return Optional.of(BookDomainMapper.fromDetail(detail.get()));
        }

        return bookSearchService.fetchBookCard(uuid)
            .map(BookDomainMapper::fromCard);
    }
}
