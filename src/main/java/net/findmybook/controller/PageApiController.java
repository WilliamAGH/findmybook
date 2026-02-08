package net.findmybook.controller;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import net.findmybook.config.SitemapProperties;
import net.findmybook.dto.BookCard;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.SitemapService;
import net.findmybook.service.SitemapService.AuthorSection;
import net.findmybook.service.SitemapService.BookSitemapItem;
import net.findmybook.service.SitemapService.PagedResult;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.cover.CoverPrioritizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;

/**
 * REST endpoints that provide typed payloads for SPA-rendered public pages.
 */
@RestController
@RequestMapping("/api/pages")
public class PageApiController {

    private static final Logger log = LoggerFactory.getLogger(PageApiController.class);
    private static final int MAX_BESTSELLERS = 8;
    private static final int MAX_RECENT_BOOKS = 8;
    private static final int DEFAULT_CATEGORY_FACET_LIMIT = 24;
    private static final int MAX_CATEGORY_FACET_LIMIT = 200;
    private static final int DEFAULT_CATEGORY_MIN_BOOKS = 1;

    private final HomePageSectionsService homePageSectionsService;
    private final SitemapService sitemapService;
    private final SitemapProperties sitemapProperties;
    private final AffiliateLinkService affiliateLinkService;
    private final BookSeoMetadataService bookSeoMetadataService;
    private final int maxDescriptionLength;

    /**
     * Creates the page payload controller.
     *
     * @param homePageSectionsService service that hydrates homepage/book sections
     * @param sitemapService service that loads sitemap buckets and pages
     * @param sitemapProperties configured sitemap base URL and sizes
     * @param affiliateLinkService service that computes outbound purchase links
     * @param bookSeoMetadataService service that computes route metadata payloads
     * @param maxDescriptionLength configured maximum description length for route SEO metadata
     */
    public PageApiController(HomePageSectionsService homePageSectionsService,
                             SitemapService sitemapService,
                             SitemapProperties sitemapProperties,
                             AffiliateLinkService affiliateLinkService,
                             BookSeoMetadataService bookSeoMetadataService,
                             @Value("${app.seo.max-description-length:170}") int maxDescriptionLength) {
        this.homePageSectionsService = homePageSectionsService;
        this.sitemapService = sitemapService;
        this.sitemapProperties = sitemapProperties;
        this.affiliateLinkService = affiliateLinkService;
        this.bookSeoMetadataService = bookSeoMetadataService;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    /**
     * Returns typed homepage cards for bestsellers and recently viewed sections.
     *
     * @return asynchronous payload used by the SPA home route
     */
    @GetMapping("/home")
    public Mono<HomePayload> homePayload() {
        Mono<List<BookCard>> bestsellers = homePageSectionsService.loadCurrentBestsellers(MAX_BESTSELLERS)
            .map(cards -> retainRenderableCovers(cards, MAX_BESTSELLERS))
            .timeout(Duration.ofSeconds(3));
        Mono<List<BookCard>> recentBooks = homePageSectionsService.loadRecentBooks(MAX_RECENT_BOOKS)
            .map(cards -> retainRenderableCovers(cards, MAX_RECENT_BOOKS))
            .timeout(Duration.ofSeconds(3));

        return Mono.zip(bestsellers, recentBooks)
            .map(tuple -> new HomePayload(tuple.getT1(), tuple.getT2()))
            .onErrorMap(ex -> {
                log.warn("Failed to build /api/pages/home payload: {}", ex.getMessage());
                return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Homepage payload load failed", ex);
            });
    }

    private static List<BookCard> retainRenderableCovers(List<BookCard> cards, int maxItems) {
        if (cards == null || cards.isEmpty() || maxItems <= 0) {
            return List.of();
        }

        List<BookCard> filtered = new ArrayList<>(Math.min(cards.size(), maxItems));
        for (BookCard card : cards) {
            if (card == null || CoverPrioritizer.score(card) <= 0) {
                continue;
            }
            filtered.add(card);
            if (filtered.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(filtered);
    }

    /**
     * Returns typed affiliate links for a single book.
     *
     * @param identifier slug, UUID, or ISBN identifier
     * @return affiliate links when book exists, otherwise 404
     */
    @GetMapping("/book/{identifier}/affiliate-links")
    public Mono<ResponseEntity<Map<String, String>>> affiliateLinks(@PathVariable String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        return homePageSectionsService.locateBook(identifier)
            .map(book -> ResponseEntity.ok(affiliateLinkService.generateLinks(book)))
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * Returns typed sitemap data for SPA rendering.
     *
     * @param view authors/books toggle
     * @param letter active bucket letter
     * @param page requested page (1-indexed)
     * @return sitemap payload for the selected view and bucket
     */
    @GetMapping("/sitemap")
    public ResponseEntity<SitemapPayload> sitemapPayload(@RequestParam(name = "view", required = false) String view,
                                                         @RequestParam(name = "letter", required = false) String letter,
                                                         @RequestParam(name = "page", required = false, defaultValue = "1") int page) {
        String normalizedView = normalizeView(view);
        String bucket = sitemapService.normalizeBucket(letter);
        int safePage = PagingUtils.atLeast(page, 1);

        if ("books".equals(normalizedView)) {
            PagedResult<BookSitemapItem> books = sitemapService.getBooksByLetter(bucket, safePage);
            return ResponseEntity.ok(new SitemapPayload(
                normalizedView,
                bucket,
                safePage,
                books.totalPages(),
                books.totalItems(),
                SitemapService.LETTER_BUCKETS,
                sitemapProperties.getBaseUrl(),
                books.items().stream().map(PageApiController::toSitemapBookPayload).toList(),
                List.of()
            ));
        }

        PagedResult<AuthorSection> authors = sitemapService.getAuthorsByLetter(bucket, safePage);
        return ResponseEntity.ok(new SitemapPayload(
            normalizedView,
            bucket,
            safePage,
            authors.totalPages(),
            authors.totalItems(),
            SitemapService.LETTER_BUCKETS,
            sitemapProperties.getBaseUrl(),
            List.of(),
            authors.items().stream().map(PageApiController::toSitemapAuthorPayload).toList()
        ));
    }

    /**
     * Returns top category/genre facets for the categories route.
     *
     * @param limit maximum number of facets to return
     * @param minBooks minimum number of books required to include a facet
     * @return typed category facets payload
     */
    @GetMapping("/categories/facets")
    public ResponseEntity<CategoriesFacetsPayload> categoryFacets(
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestParam(name = "minBooks", required = false) Integer minBooks
    ) {
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

    /**
     * Returns canonical metadata for an SPA route path.
     *
     * @param path route path to resolve (for example: /search, /book/{slug})
     * @return metadata payload with canonical URL, OpenGraph image, and status semantics
     */
    @GetMapping("/meta")
    public Mono<ResponseEntity<PageMetadataPayload>> metadata(@RequestParam(name = "path", required = false) String path) {
        if (!StringUtils.hasText(path)) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return Mono.just(ResponseEntity.ok(toPageMetadataPayload(bookSeoMetadataService.homeMetadata(), HttpStatus.OK.value())));
        }
        if ("/search".equals(normalizedPath)) {
            return Mono.just(ResponseEntity.ok(toPageMetadataPayload(bookSeoMetadataService.searchMetadata(), HttpStatus.OK.value())));
        }
        if ("/explore".equals(normalizedPath)) {
            return Mono.just(ResponseEntity.ok(toPageMetadataPayload(bookSeoMetadataService.exploreMetadata(), HttpStatus.OK.value())));
        }
        if ("/categories".equals(normalizedPath)) {
            return Mono.just(ResponseEntity.ok(toPageMetadataPayload(bookSeoMetadataService.categoriesMetadata(), HttpStatus.OK.value())));
        }
        if ("/sitemap".equals(normalizedPath)) {
            return Mono.just(ResponseEntity.ok(toPageMetadataPayload(
                bookSeoMetadataService.sitemapMetadata(bookSeoMetadataService.defaultSitemapPath()),
                HttpStatus.OK.value()
            )));
        }

        Matcher bookMatcher = bookSeoMetadataService.bookRoutePattern().matcher(normalizedPath);
        if (bookMatcher.matches()) {
            String identifier = decodePathSegment(bookMatcher.group(1));
            if (!StringUtils.hasText(identifier)) {
                return Mono.just(ResponseEntity.ok(
                    toPageMetadataPayload(bookSeoMetadataService.notFoundMetadata(normalizedPath), HttpStatus.NOT_FOUND.value())
                ));
            }
            return homePageSectionsService.locateBook(identifier)
                .map(book -> {
                    if (book == null) {
                        return ResponseEntity.ok(
                            toPageMetadataPayload(bookSeoMetadataService.notFoundMetadata(normalizedPath), HttpStatus.NOT_FOUND.value())
                        );
                    }
                    BookSeoMetadataService.SeoMetadata metadata = bookSeoMetadataService.bookMetadata(book, maxDescriptionLength);
                    return ResponseEntity.ok(toPageMetadataPayload(metadata, HttpStatus.OK.value()));
                })
                .switchIfEmpty(Mono.just(ResponseEntity.ok(
                    toPageMetadataPayload(bookSeoMetadataService.notFoundMetadata(normalizedPath), HttpStatus.NOT_FOUND.value())
                )));
        }

        Matcher sitemapMatcher = bookSeoMetadataService.sitemapRoutePattern().matcher(normalizedPath);
        if (sitemapMatcher.matches()) {
            String view = "books".equalsIgnoreCase(sitemapMatcher.group(1)) ? "books" : "authors";
            String bucket = sitemapService.normalizeBucket(decodePathSegment(sitemapMatcher.group(2)));
            int safePage = parseSitemapPage(sitemapMatcher.group(3));
            String canonicalPath = "/sitemap/" + view + "/" + bucket + "/" + safePage;
            return Mono.just(ResponseEntity.ok(toPageMetadataPayload(
                bookSeoMetadataService.sitemapMetadata(canonicalPath),
                HttpStatus.OK.value()
            )));
        }

        return Mono.just(ResponseEntity.ok(
            toPageMetadataPayload(bookSeoMetadataService.notFoundMetadata(normalizedPath), HttpStatus.NOT_FOUND.value())
        ));
    }

    /**
     * Returns the backend-defined public route contract consumed by the SPA router.
     */
    @GetMapping("/routes")
    public ResponseEntity<BookSeoMetadataService.RouteManifest> routes() {
        return ResponseEntity.ok(bookSeoMetadataService.routeManifest());
    }

    private static String normalizeView(String view) {
        if (!StringUtils.hasText(view)) {
            return "authors";
        }
        String candidate = view.trim().toLowerCase(Locale.ROOT);
        return "books".equals(candidate) ? "books" : "authors";
    }

    private String normalizeRoutePath(String rawPath) {
        String candidate = rawPath.trim();
        String withoutQuery = candidate;
        int queryIndex = candidate.indexOf('?');
        if (queryIndex >= 0) {
            withoutQuery = candidate.substring(0, queryIndex);
        }
        int fragmentIndex = withoutQuery.indexOf('#');
        if (fragmentIndex >= 0) {
            withoutQuery = withoutQuery.substring(0, fragmentIndex);
        }
        if (!withoutQuery.startsWith("/")) {
            withoutQuery = "/" + withoutQuery;
        }
        return withoutQuery;
    }

    private static String decodePathSegment(String value) {
        try {
            return UriUtils.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private static int parseSitemapPage(String rawPageNumber) {
        try {
            long parsedPageNumber = Long.parseLong(rawPageNumber);
            long clampedPageNumber = Math.min(parsedPageNumber, Integer.MAX_VALUE);
            return PagingUtils.atLeast((int) clampedPageNumber, 1);
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private PageMetadataPayload toPageMetadataPayload(BookSeoMetadataService.SeoMetadata metadata, int statusCode) {
        return new PageMetadataPayload(
            metadata.title(),
            metadata.description(),
            metadata.canonicalUrl(),
            metadata.keywords(),
            metadata.ogImage(),
            statusCode
        );
    }

    private static SitemapBookPayload toSitemapBookPayload(BookSitemapItem item) {
        return new SitemapBookPayload(item.bookId(), item.slug(), item.title(), item.updatedAt());
    }

    private static SitemapAuthorPayload toSitemapAuthorPayload(AuthorSection section) {
        List<SitemapBookPayload> books = section.books().stream()
            .map(PageApiController::toSitemapBookPayload)
            .toList();

        return new SitemapAuthorPayload(section.authorId(), section.authorName(), section.updatedAt(), books);
    }

    /**
     * Typed homepage payload for SPA home cards.
     *
     * @param currentBestsellers bestseller cards section
     * @param recentBooks recent-books section
     */
    public record HomePayload(List<BookCard> currentBestsellers, List<BookCard> recentBooks) {
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
     * @param statusCode semantic HTTP status for route presentation
     */
    public record PageMetadataPayload(String title,
                                      String description,
                                      String canonicalUrl,
                                      String keywords,
                                      String ogImage,
                                      int statusCode) {
    }
}
