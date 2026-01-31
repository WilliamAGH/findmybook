package net.findmybook.util.cover;

import net.findmybook.dto.BookCard;
import net.findmybook.model.Book;
import net.findmybook.util.ValidationUtils;
import java.util.Comparator;
import java.util.Locale;
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
        Comparator<Book> comparator = Comparator
            .comparingInt(CoverPrioritizer::coverPresenceRank)
            .thenComparingInt(CoverPrioritizer::sourceRank)
            .thenComparingInt(CoverPrioritizer::matchTypeRank)
            .thenComparing(Comparator.<Book>comparingDouble(CoverPrioritizer::relevanceScore).reversed());

        if (orderSpecific != null) {
            comparator = comparator.thenComparing(orderSpecific);
        }

        comparator = comparator
            .thenComparing(Comparator.<Book>comparingInt(CoverPrioritizer::score).reversed())
            .thenComparing(Comparator.<Book>comparingLong(book -> ImageDimensionUtils.totalPixels(
                book.getCoverImageWidth(),
                book.getCoverImageHeight()
            )).reversed())
            .thenComparing(Comparator.<Book>comparingInt(book -> Optional.ofNullable(book.getCoverImageHeight()).orElse(0)).reversed())
            .thenComparing(Comparator.<Book>comparingInt(book -> Optional.ofNullable(book.getCoverImageWidth()).orElse(0)).reversed())
            .thenComparing(Comparator.<Book, Boolean>comparing(book -> Boolean.TRUE.equals(book.getInPostgres()), Comparator.reverseOrder()))
            .thenComparing(bookInsertionComparator(insertionOrder))
            .thenComparing(book -> Optional.ofNullable(book.getTitle()).orElse(""));

        return Comparator.nullsLast(comparator);
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

    private static int coverPresenceRank(Book book) {
        return score(book) > 0 ? 0 : 1;
    }

    private static int sourceRank(Book book) {
        if (book == null) {
            return 1;
        }
        if (Boolean.TRUE.equals(book.getInPostgres())) {
            return 0;
        }
        String source = qualifierAsString(book, "search.source");
        return "EXTERNAL_FALLBACK".equals(source) ? 2 : 1;
    }

    private static int matchTypeRank(Book book) {
        String matchType = qualifierAsString(book, "search.matchType");
        if (matchType == null) {
            return 4;
        }
        String normalized = matchType.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EXACT_TITLE", "TITLE", "EXACT" -> 0;
            case "FULLTEXT", "TEXT" -> 1;
            case "AUTHOR", "AUTHORS" -> 2;
            case "FUZZY" -> 3;
            default -> 4;
        };
    }

    private static double relevanceScore(Book book) {
        Object value = qualifier(book, "search.relevanceScore");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private static String qualifierAsString(Book book, String key) {
        Object value = qualifier(book, key);
        return value == null ? null : value.toString();
    }

    private static Object qualifier(Book book, String key) {
        if (book == null || !ValidationUtils.hasText(key)) {
            return null;
        }
        Map<String, Object> qualifiers = book.getQualifiers();
        if (qualifiers == null) {
            return null;
        }
        return qualifiers.get(key);
    }
}
