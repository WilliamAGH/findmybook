package net.findmybook.controller;

import java.time.Instant;
import java.util.List;
import net.findmybook.dto.BookCard;

/**
 * Typed API payloads returned by {@link PageApiController} for SPA-rendered public pages.
 */
public final class PageApiPayloads {

    private PageApiPayloads() {
    }

    /**
     * Typed homepage payload for SPA home cards.
     *
     * @param currentBestsellers bestseller cards section
     * @param recentBooks recent-books section
     * @param popularBooks most-viewed books for the selected window
     * @param popularWindow applied popularity window ({@code 30d}, {@code 90d}, {@code all})
     */
    public record HomePayload(List<BookCard> currentBestsellers,
                              List<BookCard> recentBooks,
                              List<BookCard> popularBooks,
                              String popularWindow) {
        /**
         * Backward-compatible constructor for callers that do not request popular-book cards.
         */
        public HomePayload(List<BookCard> currentBestsellers, List<BookCard> recentBooks) {
            this(currentBestsellers, recentBooks, List.of(), "30d");
        }
    }

    /**
     * Typed sitemap payload for SPA sitemap rendering.
     *
     * @param viewType active view type (authors or books)
     * @param activeLetter active bucket letter
     * @param pageNumber current page (1-indexed)
     * @param totalPages total page count for active view/bucket
     * @param totalItems total item count for active view/bucket
     * @param letters available letter buckets
     * @param baseUrl base URL used by sitemap links
     * @param books book list when in books view
     * @param authors author list when in authors view
     */
    public record SitemapPayload(String viewType,
                                 String activeLetter,
                                 int pageNumber,
                                 int totalPages,
                                 int totalItems,
                                 List<String> letters,
                                 String baseUrl,
                                 List<SitemapBookPayload> books,
                                 List<SitemapAuthorPayload> authors) {
    }

    /**
     * Typed sitemap book record.
     *
     * @param id canonical book ID
     * @param slug book slug used in public routes
     * @param title book title
     * @param updatedAt last known update timestamp
     */
    public record SitemapBookPayload(String id, String slug, String title, Instant updatedAt) {
    }

    /**
     * Typed sitemap author record with the author's indexed books.
     *
     * @param authorId canonical author ID
     * @param authorName display author name
     * @param lastModified latest author section modification timestamp
     * @param books books indexed under this author
     */
    public record SitemapAuthorPayload(String authorId,
                                       String authorName,
                                       Instant lastModified,
                                       List<SitemapBookPayload> books) {
    }

    /**
     * Typed categories/genres facet response payload.
     *
     * @param genres category facets sorted by popularity
     * @param generatedAt server time when the payload was assembled
     * @param limit effective facet limit
     * @param minBooks effective threshold for included facets
     */
    public record CategoriesFacetsPayload(List<CategoryFacetPayload> genres,
                                          Instant generatedAt,
                                          int limit,
                                          int minBooks) {
    }

    /**
     * Typed category facet entry.
     *
     * @param name display category/genre name
     * @param bookCount number of books associated with the category
     */
    public record CategoryFacetPayload(String name, int bookCount) {
    }

    /**
     * Typed metadata payload consumed by SPA head management.
     *
     * @param title page title without global suffix
     * @param description page description content
     * @param canonicalUrl canonical absolute URL for the current route
     * @param keywords SEO keywords list
     * @param ogImage OpenGraph/Twitter preview image URL
     * @param robots robots directive used for crawler indexing behavior
     * @param openGraphType OpenGraph object type (for example: website, book)
     * @param openGraphProperties additional route-specific OpenGraph properties
     * @param structuredDataJson route JSON-LD payload used for rich-result crawling
     * @param statusCode semantic HTTP status for route presentation
     */
    public record PageMetadataPayload(String title,
                                      String description,
                                      String canonicalUrl,
                                      String keywords,
                                      String ogImage,
                                      String robots,
                                      String openGraphType,
                                      List<OpenGraphMetaPayload> openGraphProperties,
                                      String structuredDataJson,
                                      int statusCode) {
    }

    /**
     * Typed OpenGraph key-value entry for SPA head updates.
     *
     * @param property OpenGraph property key
     * @param content property value
     */
    public record OpenGraphMetaPayload(String property, String content) {
    }
}
