package net.findmybook.support.search;

import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.SearchExternalProviderUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import net.findmybook.util.cover.ImageDimensionUtils;
import net.findmybook.util.cover.UrlSourceDetector;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds stable paginated search pages from hydrated books.
 *
 * <p>Encapsulates deduplication, cover preference filtering, and deterministic ordering so
 * search services can delegate page-shaping concerns to one shared policy.</p>
 */
public final class SearchPageAssembler {

    private static final Pattern AUTHOR_QUERY_TOKEN_PATTERN = Pattern.compile("^[\\p{L}][\\p{L}'-]{1,}$");

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
        applyAuthorIntentPenalties(filtered, query);

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
        String normalized = SearchExternalProviderUtils.normalizeOrderBy(orderBy);
        Comparator<Book> primarySort = switch (normalized) {
            case "relevance" -> Comparator
                .comparingDouble(this::relevanceScoreForSort)
                .reversed();
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
            default -> Comparator
                .comparing(Book::getPublishedDate, Comparator.nullsLast(java.util.Date::compareTo))
                .reversed();
        };
        return CoverPrioritizer.bookComparatorWithPrimarySort(insertionOrder, primarySort);
    }

    private double relevanceScoreForSort(Book book) {
        Double parsed = parseNumericQualifier(book, "search.relevanceScore");
        return parsed != null ? parsed : 0.0d;
    }

    private void applyAuthorIntentPenalties(List<Book> books, String query) {
        if (books == null || books.isEmpty() || !isLikelyAuthorQuery(query)) {
            return;
        }

        for (Book book : books) {
            if (book == null || hasAuthorMatch(book, query)) {
                continue;
            }
            String currentMatchType = Optional.ofNullable(book.getQualifiers())
                .map(qualifiers -> qualifiers.get("search.matchType"))
                .map(Object::toString)
                .orElse("");
            if ("EXACT_TITLE".equalsIgnoreCase(currentMatchType)) {
                book.addQualifier("search.matchType", "FUZZY");
            } else if (!StringUtils.hasText(currentMatchType)) {
                book.addQualifier("search.matchType", "UNKNOWN");
            }

            Double currentScore = parseNumericQualifier(book, "search.relevanceScore");
            if (currentScore != null && currentScore > 0.95d) {
                book.addQualifier("search.relevanceScore", 0.65d);
            }
        }
    }

    private Double parseNumericQualifier(Book book, String key) {
        if (book == null || !StringUtils.hasText(key) || book.getQualifiers() == null) {
            return null;
        }
        Object value = book.getQualifiers().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean hasAuthorMatch(Book book, String query) {
        if (book == null || book.getAuthors() == null || book.getAuthors().isEmpty()) {
            return false;
        }
        String normalizedQuery = normalizeForComparison(query);
        if (!StringUtils.hasText(normalizedQuery)) {
            return false;
        }
        Set<String> queryTokens = tokenSet(normalizedQuery);
        if (queryTokens.isEmpty()) {
            return false;
        }

        for (String author : book.getAuthors()) {
            String normalizedAuthor = normalizeForComparison(author);
            if (!StringUtils.hasText(normalizedAuthor)) {
                continue;
            }
            if (normalizedAuthor.contains(normalizedQuery) || normalizedQuery.contains(normalizedAuthor)) {
                return true;
            }
            Set<String> authorTokens = tokenSet(normalizedAuthor);
            if (authorTokens.containsAll(queryTokens)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyAuthorQuery(String query) {
        String normalized = normalizeForComparison(query);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        String[] tokens = normalized.split(" ");
        if (tokens.length < 2 || tokens.length > 3) {
            return false;
        }
        for (String token : tokens) {
            if (!AUTHOR_QUERY_TOKEN_PATTERN.matcher(token).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeForComparison(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}0-9\\s'-]", " ")
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static Set<String> tokenSet(String value) {
        Set<String> tokens = new HashSet<>();
        if (!StringUtils.hasText(value)) {
            return tokens;
        }
        for (String token : value.split(" ")) {
            if (StringUtils.hasText(token)) {
                tokens.add(token);
            }
        }
        return tokens;
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
