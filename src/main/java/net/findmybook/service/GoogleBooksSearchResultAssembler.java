package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.BookDomainMapper;
import net.findmybook.util.UuidUtils;
import net.findmybook.util.ValidationUtils;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal helper for composing legacy {@link Book} search results from Postgres-first and
 * Google fallback flows.
 */
final class GoogleBooksSearchResultAssembler {

    private static final List<String> GOOGLE_ID_TAG_KEYS = List.of(
        "google_canonical_id",
        "googleVolumeId",
        "google_volume_id",
        "google_volume",
        "google_book_id"
    );

    private GoogleBooksSearchResultAssembler() {
    }

    static Mono<List<Book>> assembleBaselineResults(List<BookSearchService.SearchResult> results,
                                                    BookQueryRepository bookQueryRepository,
                                                    Logger logger) {
        if (results == null || results.isEmpty()) {
            return Mono.just(List.of());
        }

        LinkedHashMap<UUID, Integer> orderedIds = new LinkedHashMap<>();
        for (BookSearchService.SearchResult result : results) {
            if (result != null && result.bookId() != null) {
                orderedIds.putIfAbsent(result.bookId(), orderedIds.size());
            }
        }

        if (orderedIds.isEmpty() || bookQueryRepository == null) {
            return Mono.just(List.of());
        }

        List<UUID> lookupOrder = new ArrayList<>(orderedIds.keySet());

        return Mono.fromCallable(() -> bookQueryRepository.fetchBookListItems(lookupOrder))
            .subscribeOn(Schedulers.boundedElastic())
            .map(items -> assembleOrderedLegacyBooks(orderedIds, items))
            .onErrorMap(ex -> {
                logger.warn("Failed to fetch Postgres list items for search ({} ids): {}", lookupOrder.size(), ex.getMessage());
                return new IllegalStateException(
                    "Failed to fetch Postgres list items for baseline search assembly",
                    ex
                );
            });
    }

    static List<Book> mergeSearchResults(List<Book> baseline, List<Book> fallback, int limit) {
        if ((baseline == null || baseline.isEmpty()) && (fallback == null || fallback.isEmpty())) {
            return List.of();
        }

        LinkedHashMap<String, Book> ordered = new LinkedHashMap<>();

        if (baseline != null) {
            baseline.stream()
                .filter(Objects::nonNull)
                .forEach(book -> ordered.putIfAbsent(searchResultKey(book), book));
        }

        if (fallback != null) {
            for (Book book : fallback) {
                if (book == null) {
                    continue;
                }
                String key = searchResultKey(book);
                if (!ordered.containsKey(key)) {
                    ordered.put(key, book);
                }
                if (ordered.size() >= limit) {
                    break;
                }
            }
        }

        if (ordered.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(ordered.values());
    }

    static Optional<String> resolveGoogleVolumeId(Book book) {
        if (book == null) {
            return Optional.empty();
        }

        String candidate = book.getId();
        if (ValidationUtils.hasText(candidate) && UuidUtils.parseUuidOrNull(candidate) == null) {
            return Optional.of(candidate);
        }

        Map<String, Object> qualifiers = book.getQualifiers();
        if (qualifiers == null || qualifiers.isEmpty()) {
            return Optional.empty();
        }

        for (String key : GOOGLE_ID_TAG_KEYS) {
            Object value = qualifiers.get(key);
            if (value instanceof String str && ValidationUtils.hasText(str)) {
                return Optional.of(str);
            }
        }

        return Optional.empty();
    }

    private static List<Book> assembleOrderedLegacyBooks(LinkedHashMap<UUID, Integer> orderedIds,
                                                         List<BookListItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        Map<UUID, Book> mapped = new HashMap<>();
        for (BookListItem item : items) {
            if (item == null) {
                continue;
            }
            UUID uuid = UuidUtils.parseUuidOrNull(item.id());
            if (uuid == null || mapped.containsKey(uuid)) {
                continue;
            }
            Book legacy = BookDomainMapper.fromListItem(item);
            if (legacy != null) {
                mapped.put(uuid, legacy);
            }
        }

        if (mapped.isEmpty()) {
            return List.of();
        }

        List<Book> ordered = new ArrayList<>(mapped.size());
        for (UUID id : orderedIds.keySet()) {
            Book book = mapped.get(id);
            if (book != null) {
                ordered.add(book);
            }
        }
        return ordered.isEmpty() ? List.of() : ordered;
    }

    private static String searchResultKey(Book book) {
        if (book == null) {
            return "__null__";
        }
        if (ValidationUtils.hasText(book.getSlug())) {
            return book.getSlug();
        }
        if (ValidationUtils.hasText(book.getId())) {
            return book.getId();
        }
        if (book.getTitle() != null && book.getAuthors() != null && !book.getAuthors().isEmpty()) {
            return book.getTitle() + "::" + String.join("|", book.getAuthors());
        }
        return book.getTitle() != null ? book.getTitle() : Integer.toHexString(System.identityHashCode(book));
    }
}
