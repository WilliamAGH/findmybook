package net.findmybook.controller.dto.search;

import net.findmybook.controller.dto.BookDto;
import net.findmybook.controller.dto.BookDtoMapper;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.BookSearchService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.SlugGenerator;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Maps search-domain responses to explicit controller API contracts.
 *
 * <p>This mapper centralizes search contract assembly so controller endpoints can stay focused on
 * HTTP orchestration while DTO construction remains deterministic and reusable.</p>
 */
public final class SearchContractMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SearchContractMapper() {
    }

    /**
     * Maps a paged search result into the external API payload shape.
     *
     * @param page search page from application services
     * @return immutable API response
     */
    public static SearchResponse fromSearchPage(SearchPaginationService.SearchPage page) {
        List<SearchHitDto> hits = page.pageItems().stream()
            .map(SearchContractMapper::toSearchHit)
            .filter(Objects::nonNull)
            .toList();
        String queryHash = SearchQueryUtils.topicKey(page.query());

        return new SearchResponse(
            page.query(),
            queryHash,
            page.startIndex(),
            page.maxResults(),
            page.totalUnique(),
            page.hasMore(),
            page.nextStartIndex(),
            page.prefetchedCount(),
            page.orderBy(),
            page.coverSource() != null ? page.coverSource().name() : CoverImageSource.ANY.name(),
            page.resolutionPreference() != null ? page.resolutionPreference().name() : ImageResolutionPreference.ANY.name(),
            hits
        );
    }

    /**
     * Builds an explicit empty search payload preserving request metadata.
     *
     * @param query normalized query text
     * @param request normalized request contract
     * @return empty search payload
     */
    public static SearchResponse emptySearchResponse(String query, SearchPaginationService.SearchRequest request) {
        return new SearchResponse(
            query,
            SearchQueryUtils.topicKey(query),
            request.startIndex(),
            request.maxResults(),
            0,
            false,
            request.startIndex(),
            0,
            request.orderBy(),
            request.coverSource().name(),
            request.resolutionPreference().name(),
            List.of()
        );
    }

    /**
     * Maps author search results into a stable API response contract.
     *
     * @param query normalized query text
     * @param limit effective result limit
     * @param results author search hits
     * @return API response contract with deterministic ordering
     */
    public static AuthorSearchResponse fromAuthorResults(String query,
                                                         int limit,
                                                         List<BookSearchService.AuthorResult> results) {
        List<BookSearchService.AuthorResult> safeResults = results == null ? List.of() : results;
        List<AuthorHitDto> hits = safeResults.stream()
            .sorted(Comparator.comparingDouble(BookSearchService.AuthorResult::relevanceScore).reversed())
            .limit(Math.max(0, limit))
            .map(SearchContractMapper::toAuthorHit)
            .toList();
        return new AuthorSearchResponse(query, limit, hits);
    }

    /**
     * Returns an empty author response preserving query/limit context.
     *
     * @param query normalized query text
     * @param limit effective result limit
     * @return empty author payload
     */
    public static AuthorSearchResponse emptyAuthorSearchResponse(String query, int limit) {
        return new AuthorSearchResponse(query, limit, List.of());
    }

    private static AuthorHitDto toAuthorHit(BookSearchService.AuthorResult authorResult) {
        String effectiveId = authorResult.authorId();
        if (!StringUtils.hasText(effectiveId)) {
            String slug = SlugGenerator.slugify(authorResult.authorName());
            effectiveId = "external-author-" + (StringUtils.hasText(slug) ? slug : "unknown");
        }
        return new AuthorHitDto(
            effectiveId,
            authorResult.authorName(),
            authorResult.bookCount(),
            authorResult.relevanceScore()
        );
    }

    private static SearchHitDto toSearchHit(Book book) {
        if (book == null) {
            return null;
        }
        JsonNode qualifiers = readQualifiersNode(book);
        String matchType = readQualifierText(qualifiers, "search.matchType");
        Double relevance = readQualifierScore(qualifiers, "search.relevanceScore");

        BookDto dto = BookDtoMapper.toDto(book);
        return new SearchHitDto(dto, matchType, relevance);
    }

    private static JsonNode readQualifiersNode(Book book) {
        if (book.getQualifiers() == null || book.getQualifiers().isEmpty()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        return OBJECT_MAPPER.valueToTree(book.getQualifiers());
    }

    private static String readQualifierText(JsonNode qualifiers, String key) {
        JsonNode valueNode = qualifiers.get(key);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        String text = valueNode.asString(null);
        return StringUtils.hasText(text) ? text : null;
    }

    private static Double readQualifierScore(JsonNode qualifiers, String key) {
        JsonNode valueNode = qualifiers.get(key);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isNumber()) {
            return valueNode.doubleValue();
        }
        try {
            return Double.parseDouble(valueNode.asString(""));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
