package net.findmybook.util.cover;

import net.findmybook.dto.BookCard;
import net.findmybook.model.Book;
import org.springframework.util.StringUtils;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
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

    private static final int MIN_COLOR_COVER_SCORE = 2;

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
            book.getIsCoverHighResolution(),
            book.getIsCoverGrayscale()
        );
    }

    public static int score(BookCard card) {
        if (card == null) {
            return 0;
        }
        String primary = StringUtils.hasText(card.coverS3Key()) ? card.coverS3Key() : card.coverUrl();
        if (!StringUtils.hasText(primary) && !StringUtils.hasText(card.fallbackCoverUrl())) {
            return 0;
        }
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            primary,
            card.fallbackCoverUrl()
        );
        return CoverQuality.rankFromUrl(
            resolved.url(),
            resolved.width(),
            resolved.height(),
            resolved.highResolution(),
            card.coverGrayscale()
        );
    }

    /**
     * Returns {@code true} when the book has a known renderable color cover.
     */
    public static boolean hasColorCover(Book book) {
        return score(book) >= MIN_COLOR_COVER_SCORE;
    }

    /**
     * Returns {@code true} when the card has a known renderable color cover.
     */
    public static boolean hasColorCover(BookCard card) {
        return score(card) >= MIN_COLOR_COVER_SCORE;
    }

    public static Comparator<Book> bookComparator(Map<String, Integer> insertionOrder) {
        return bookComparator(insertionOrder, null);
    }

    public static Comparator<Book> bookComparatorWithPrimarySort(Map<String, Integer> insertionOrder,
                                                                 Comparator<Book> primarySort) {
        if (primarySort == null) {
            return bookComparator(insertionOrder);
        }
        Comparator<Book> comparator = Comparator
            .comparingInt(CoverPrioritizer::coverPresenceRank)
            .thenComparing(primarySort)
            .thenComparingInt(CoverPrioritizer::sourceRank)
            .thenComparingInt(CoverPrioritizer::matchTypeRank)
            .thenComparing(Comparator.<Book>comparingDouble(CoverPrioritizer::relevanceScore).reversed());

        comparator = appendSecondaryTiebreakers(comparator, insertionOrder);

        return Comparator.nullsLast(comparator);
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

        comparator = appendSecondaryTiebreakers(comparator, insertionOrder);

        return Comparator.nullsLast(comparator);
    }

    /**
     * Appends the shared tiebreaker chain common to all book comparators:
     * publishedDate → score → totalPixels → height → width → inPostgres → insertionOrder → title.
     */
    private static Comparator<Book> appendSecondaryTiebreakers(Comparator<Book> base,
                                                                Map<String, Integer> insertionOrder) {
        return base
            .thenComparing(Comparator.<Book>comparingLong(CoverPrioritizer::publishedDateEpochDay).reversed())
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
    }

    public static Comparator<BookCard> cardComparator(Map<String, Integer> originalOrder) {
        Comparator<BookCard> comparator = Comparator
            .comparingInt((BookCard card) -> CoverPrioritizer.score(card))
            .reversed()
            .thenComparing(card -> CoverUrlResolver.isCdnUrl(card.coverUrl()), Comparator.reverseOrder())
            .thenComparing(Comparator.<BookCard>comparingLong(CoverPrioritizer::cardPublishedDateEpochDay).reversed());

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
            if (card == null || !StringUtils.hasText(card.id())) {
                return Integer.MAX_VALUE;
            }
            return safeOrder.getOrDefault(card.id(), Integer.MAX_VALUE);
        });
    }

    private static int coverPresenceRank(Book book) {
        if (hasColorCover(book)) return 0; // color cover
        int quality = score(book);
        if (quality == 1) return 1;   // grayscale cover
        return 2;                     // no cover
    }

    private static int sourceRank(Book book) {
        if (book == null) {
            return 5;
        }
        if (Boolean.TRUE.equals(book.getInPostgres())) {
            return 0;
        }
        String provider = resolveExternalProvider(book);
        if ("OPEN_LIBRARY".equals(provider) || "OPEN_LIBRARY_API".equals(provider)) {
            return 1;
        }
        if ("GOOGLE_BOOKS".equals(provider) || "GOOGLE_BOOKS_API".equals(provider) || "GOOGLE_API".equals(provider)) {
            return 2;
        }
        String source = qualifierAsString(book, "search.source");
        return "EXTERNAL_FALLBACK".equals(source) ? 3 : 4;
    }

    private static String resolveExternalProvider(Book book) {
        if (book == null) {
            return null;
        }
        String provider = qualifierAsString(book, "search.provider");
        if (!StringUtils.hasText(provider)) {
            provider = book.getDataSource();
        }
        if (!StringUtils.hasText(provider)) {
            provider = book.getRetrievedFrom();
        }
        if (!StringUtils.hasText(provider)) {
            return null;
        }
        return provider.trim().toUpperCase(Locale.ROOT);
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
        if (book == null || !StringUtils.hasText(key)) {
            return null;
        }
        Map<String, Object> qualifiers = book.getQualifiers();
        if (qualifiers == null) {
            return null;
        }
        return qualifiers.get(key);
    }

    /**
     * Converts a book's published date to epoch day for recency comparison.
     * Returns {@code Long.MIN_VALUE} when no date is available so null dates
     * sort after all dated books.
     */
    private static long publishedDateEpochDay(Book book) {
        Date published = book == null ? null : book.getPublishedDate();
        if (published == null) {
            return Long.MIN_VALUE;
        }
        return published.toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay();
    }

    /**
     * Converts a card's published date to epoch day for recency comparison.
     * Returns {@code Long.MIN_VALUE} when no date is available so null dates
     * sort after all dated cards.
     */
    private static long cardPublishedDateEpochDay(BookCard card) {
        LocalDate published = card == null ? null : card.publishedDate();
        if (published == null) {
            return Long.MIN_VALUE;
        }
        return published.toEpochDay();
    }
}
