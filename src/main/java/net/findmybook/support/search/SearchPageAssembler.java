package net.findmybook.support.search;

import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import net.findmybook.util.cover.ImageDimensionUtils;
import net.findmybook.util.cover.UrlSourceDetector;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds stable paginated search pages from hydrated books.
 *
 * <p>Encapsulates deduplication, cover preference filtering, and deterministic ordering so
 * search services can delegate page-shaping concerns to one shared policy.</p>
 */
public final class SearchPageAssembler {

    /**
     * Creates a search page from candidate books and paging metadata.
     *
     * @param query original query string
     * @param orderBy ordering key
     * @param coverSource cover source preference
     * @param resolutionPreference image resolution preference
     * @param rawResults hydrated candidate books
     * @param window paging window descriptor
     * @return immutable page payload for API responses
     */
    public SearchPaginationService.SearchPage buildPage(String query,
                                                        String orderBy,
                                                        CoverImageSource coverSource,
                                                        ImageResolutionPreference resolutionPreference,
                                                        List<Book> rawResults,
                                                        PagingUtils.Window window) {
        List<Book> safeRawResults = rawResults == null ? List.of() : rawResults;
        LinkedHashMap<String, Book> ordered = new LinkedHashMap<>();
        Map<String, Integer> insertionOrder = new LinkedHashMap<>();
        int position = 0;

        for (Book book : safeRawResults) {
            if (book == null || !StringUtils.hasText(book.getId()) || ordered.containsKey(book.getId())) {
                continue;
            }
            ordered.put(book.getId(), book);
            insertionOrder.put(book.getId(), position++);
        }

        List<Book> uniqueResults = new ArrayList<>(ordered.values());
        List<Book> filtered = applyCoverPreferences(uniqueResults, coverSource, resolutionPreference);

        filtered.sort(buildSearchResultComparator(insertionOrder, orderBy));
        List<Book> pageItems = PagingUtils.slice(filtered, window.startIndex(), window.limit());
        int totalUnique = filtered.size();
        boolean hasMore = PagingUtils.hasMore(totalUnique, window.startIndex(), window.limit());
        int prefetched = PagingUtils.prefetchedCount(totalUnique, window.startIndex(), window.limit());
        int nextStartIndex = hasMore ? window.startIndex() + window.limit() : window.startIndex();

        CoverImageSource effectiveSource = coverSource == null ? CoverImageSource.ANY : coverSource;
        ImageResolutionPreference effectiveResolution = resolutionPreference == null
            ? ImageResolutionPreference.ANY
            : resolutionPreference;

        return new SearchPaginationService.SearchPage(
            query,
            window.startIndex(),
            window.limit(),
            window.totalRequested(),
            totalUnique,
            pageItems,
            filtered,
            hasMore,
            nextStartIndex,
            prefetched,
            Optional.ofNullable(orderBy).orElse("newest"),
            effectiveSource,
            effectiveResolution
        );
    }

    private Comparator<Book> buildSearchResultComparator(Map<String, Integer> insertionOrder,
                                                         String orderBy) {
        if (orderBy == null) {
            return CoverPrioritizer.bookComparator(insertionOrder);
        }

        String normalized = orderBy.toLowerCase(Locale.ROOT);
        Comparator<Book> orderSpecific = switch (normalized) {
            case "newest" -> Comparator
                .comparing(Book::getPublishedDate, Comparator.nullsLast(java.util.Date::compareTo))
                .reversed();
            case "title" -> Comparator.comparing(
                book -> Optional.ofNullable(book.getTitle()).orElse(""),
                String.CASE_INSENSITIVE_ORDER
            );
            case "author" -> Comparator.comparing(
                book -> {
                    if (book == null || book.getAuthors() == null || book.getAuthors().isEmpty()) {
                        return "";
                    }
                    return Optional.ofNullable(book.getAuthors().get(0)).orElse("");
                },
                String.CASE_INSENSITIVE_ORDER
            );
            case "rating" -> Comparator
                .comparing((Book book) -> Optional.ofNullable(book.getAverageRating()).orElse(0.0), Comparator.reverseOrder())
                .thenComparing(book -> Optional.ofNullable(book.getRatingsCount()).orElse(0), Comparator.reverseOrder());
            case "cover-quality", "quality", "relevance" -> null;
            default -> null;
        };
        return CoverPrioritizer.bookComparator(insertionOrder, orderSpecific);
    }

    private List<Book> applyCoverPreferences(List<Book> books,
                                             CoverImageSource coverSource,
                                             ImageResolutionPreference resolutionPreference) {
        if (books == null || books.isEmpty()) {
            return new ArrayList<>();
        }

        CoverImageSource effectiveSource = coverSource == null ? CoverImageSource.ANY : coverSource;
        ImageResolutionPreference effectiveResolution = resolutionPreference == null
            ? ImageResolutionPreference.ANY
            : resolutionPreference;

        return books.stream()
            .filter(book -> matchesSourcePreference(book, effectiveSource))
            .filter(book -> matchesResolutionPreference(book, effectiveResolution))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private boolean matchesSourcePreference(Book book, CoverImageSource preference) {
        if (preference == null || preference == CoverImageSource.ANY) {
            return true;
        }

        CoverImageSource source = null;
        if (book != null && book.getCoverImages() != null) {
            source = book.getCoverImages().getSource();
        }

        if (source == null || source == CoverImageSource.UNDEFINED || source == CoverImageSource.ANY) {
            source = UrlSourceDetector.detectSource(book != null ? book.getExternalImageUrl() : null);
        }

        return source == preference;
    }

    private boolean matchesResolutionPreference(Book book, ImageResolutionPreference preference) {
        if (preference == null || preference == ImageResolutionPreference.ANY || preference == ImageResolutionPreference.HIGH_FIRST) {
            return true;
        }

        Integer width = book != null ? book.getCoverImageWidth() : null;
        Integer height = book != null ? book.getCoverImageHeight() : null;
        boolean highResolution = book != null
            && (Boolean.TRUE.equals(book.getIsCoverHighResolution()) || ImageDimensionUtils.isHighResolution(width, height));

        return switch (preference) {
            case HIGH_ONLY -> highResolution;
            case LARGE -> ImageDimensionUtils.meetsThreshold(width, height, ImageDimensionUtils.MIN_ACCEPTABLE_NON_GOOGLE);
            case MEDIUM -> ImageDimensionUtils.meetsThreshold(width, height, ImageDimensionUtils.MIN_ACCEPTABLE_CACHED);
            case SMALL -> width != null && height != null
                && width < ImageDimensionUtils.MIN_ACCEPTABLE_CACHED
                && height < ImageDimensionUtils.MIN_ACCEPTABLE_CACHED;
            case ORIGINAL -> highResolution;
            case UNKNOWN -> false;
            case ANY, HIGH_FIRST -> true;
        };
    }
}
