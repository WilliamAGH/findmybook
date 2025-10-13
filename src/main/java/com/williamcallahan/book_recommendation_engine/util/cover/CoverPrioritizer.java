package com.williamcallahan.book_recommendation_engine.util.cover;

import com.williamcallahan.book_recommendation_engine.dto.BookCard;
import com.williamcallahan.book_recommendation_engine.model.Book;
import com.williamcallahan.book_recommendation_engine.util.ValidationUtils;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared ordering helpers that ensure every surface ranks covers the same way.
 * Keeps search, homepage, and API consumers aligned on a single definition of
 * “best cover first”.
 */
public final class CoverPrioritizer {

    private CoverPrioritizer() {
    }

    public static int score(Book book) {
        if (book == null) {
            return 0;
        }
        return CoverQuality.rank(
            book.getS3ImagePath(),
            book.getExternalImageUrl(),
            book.getCoverImageWidth(),
            book.getCoverImageHeight(),
            book.getIsCoverHighResolution()
        );
    }

    public static int score(BookCard card) {
        if (card == null || !ValidationUtils.hasText(card.coverUrl())) {
            return 0;
        }
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            ValidationUtils.hasText(card.coverS3Key()) ? card.coverS3Key() : card.coverUrl(),
            card.fallbackCoverUrl()
        );
        return CoverQuality.rankFromUrl(
            resolved.url(),
            resolved.width(),
            resolved.height(),
            resolved.highResolution()
        );
    }

    public static Comparator<Book> bookComparator(Map<String, Integer> insertionOrder,
                                                  Comparator<Book> orderSpecific) {
        Comparator<Book> comparator = Comparator.<Book>comparingInt(CoverPrioritizer::score).reversed();

        comparator = comparator.thenComparing(
            Comparator.<Book>comparingLong(book -> ImageDimensionUtils.totalPixels(
                book.getCoverImageWidth(),
                book.getCoverImageHeight()
            )).reversed()
        );

        comparator = comparator.thenComparing(
            Comparator.<Book>comparingInt(book -> Optional.ofNullable(book.getCoverImageHeight()).orElse(0)).reversed()
        );

        comparator = comparator.thenComparing(
            Comparator.<Book>comparingInt(book -> Optional.ofNullable(book.getCoverImageWidth()).orElse(0)).reversed()
        );

        comparator = comparator.thenComparing(
            Comparator.<Book, Boolean>comparing(book -> Boolean.TRUE.equals(book.getInPostgres()), Comparator.reverseOrder())
        );

        if (orderSpecific != null) {
            comparator = comparator.thenComparing(orderSpecific);
        }

        comparator = comparator.thenComparing(bookInsertionComparator(insertionOrder));

        return comparator.thenComparing(book -> Optional.ofNullable(book.getTitle()).orElse(""));
    }

    public static Comparator<BookCard> cardComparator(Map<String, Integer> originalOrder) {
        Comparator<BookCard> comparator = Comparator
            .comparingInt((BookCard card) -> CoverPrioritizer.score(card))
            .reversed()
            .thenComparing(card -> CoverUrlResolver.isCdnUrl(card.coverUrl()), Comparator.reverseOrder());

        comparator = comparator.thenComparing(cardOrderComparator(originalOrder));

        return comparator.thenComparing(card -> Optional.ofNullable(card.title()).orElse(""));
    }

    private static Comparator<Book> bookInsertionComparator(Map<String, Integer> insertionOrder) {
        Map<String, Integer> safeOrder = Objects.requireNonNullElse(insertionOrder, Map.of());
        return Comparator.comparingInt(book -> safeOrder.getOrDefault(book.getId(), Integer.MAX_VALUE));
    }

    private static Comparator<BookCard> cardOrderComparator(Map<String, Integer> originalOrder) {
        Map<String, Integer> safeOrder = Objects.requireNonNullElse(originalOrder, Map.of());
        return Comparator.comparingInt(card -> {
            if (card == null || !ValidationUtils.hasText(card.id())) {
                return Integer.MAX_VALUE;
            }
            return safeOrder.getOrDefault(card.id(), Integer.MAX_VALUE);
        });
    }
}
