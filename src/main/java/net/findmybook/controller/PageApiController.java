package net.findmybook.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import net.findmybook.application.page.PublicPagePayloadUseCase;
import net.findmybook.config.SitemapProperties;
import net.findmybook.controller.PageApiPayloads.CategoriesFacetsPayload;
import net.findmybook.controller.PageApiPayloads.HomePayload;
import net.findmybook.controller.PageApiPayloads.OpenGraphMetaPayload;
import net.findmybook.controller.PageApiPayloads.PageMetadataPayload;
import net.findmybook.controller.PageApiPayloads.SitemapAuthorPayload;
import net.findmybook.controller.PageApiPayloads.SitemapBookPayload;
import net.findmybook.controller.PageApiPayloads.SitemapPayload;
import net.findmybook.domain.seo.RouteManifest;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.SitemapService;
import net.findmybook.service.SitemapService.AuthorSection;
import net.findmybook.service.SitemapService.BookSitemapItem;
import net.findmybook.service.SitemapService.PagedResult;
import net.findmybook.util.PagingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST endpoints that provide typed payloads for SPA-rendered public pages.
 */
@RestController
@RequestMapping("/api/pages")
public class PageApiController {

    private final PublicPagePayloadUseCase publicPagePayloadUseCase;
    private final SitemapService sitemapService;
    private final SitemapProperties sitemapProperties;
    private final HomePageSectionsService homePageSectionsService;
    private final BookSeoMetadataService bookSeoMetadataService;
    private final int maxDescriptionLength;

    /**
     * Creates the page payload controller.
     *
     * @param publicPagePayloadUseCase use case for homepage/affiliate/category payloads
     * @param sitemapService service that loads sitemap buckets and pages
     * @param sitemapProperties configured sitemap base URL and sizes
     * @param homePageSectionsService service used for metadata book lookups
     * @param bookSeoMetadataService service that computes route metadata payloads
     * @param maxDescriptionLength configured maximum description length for route SEO metadata
     */
    public PageApiController(PublicPagePayloadUseCase publicPagePayloadUseCase,
                             SitemapService sitemapService,
                             SitemapProperties sitemapProperties,
                             HomePageSectionsService homePageSectionsService,
                             BookSeoMetadataService bookSeoMetadataService,
                             @Value("${app.seo.max-description-length:160}") int maxDescriptionLength) {
        this.publicPagePayloadUseCase = publicPagePayloadUseCase;
        this.sitemapService = sitemapService;
        this.sitemapProperties = sitemapProperties;
        this.homePageSectionsService = homePageSectionsService;
        this.bookSeoMetadataService = bookSeoMetadataService;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    /**
     * Returns typed homepage cards for bestsellers and recently viewed sections.
     *
     * @return asynchronous payload used by the SPA home route
     */
    @GetMapping("/home")
    public Mono<HomePayload> homePayload(@RequestParam(name = "popularWindow", required = false) String popularWindow,
                                         @RequestParam(name = "popularLimit", required = false) Integer popularLimit,
                                         @RequestParam(name = "recordView", required = false, defaultValue = "true") boolean recordView) {
        return publicPagePayloadUseCase.loadHomePayload(popularWindow, popularLimit, recordView);
    }

    /**
     * Returns typed affiliate links for a single book.
     *
     * @param identifier slug, UUID, or ISBN identifier
     * @return affiliate links when book exists, otherwise 404
     */
    @GetMapping("/book/{identifier}/affiliate-links")
    public Mono<ResponseEntity<Map<String, String>>> affiliateLinks(@PathVariable String identifier) {
        return publicPagePayloadUseCase.loadAffiliateLinks(identifier);
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
        return publicPagePayloadUseCase.loadCategoryFacets(limit, minBooks);
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
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Route path is required"));
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
        if ("/error".equals(normalizedPath)) {
            return Mono.just(ResponseEntity.ok(toPageMetadataPayload(
                bookSeoMetadataService.errorMetadata(HttpStatus.INTERNAL_SERVER_ERROR.value(), normalizedPath),
                HttpStatus.INTERNAL_SERVER_ERROR.value()
            )));
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
                    SeoMetadata metadata = bookSeoMetadataService.bookMetadata(book, maxDescriptionLength);
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
    public ResponseEntity<RouteManifest> routes() {
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

    private PageMetadataPayload toPageMetadataPayload(SeoMetadata metadata, int statusCode) {
        List<OpenGraphMetaPayload> openGraphProperties = metadata.openGraphProperties().stream()
            .map(property -> new OpenGraphMetaPayload(property.property(), property.content()))
            .toList();

        return new PageMetadataPayload(
            metadata.title(),
            metadata.description(),
            metadata.canonicalUrl(),
            metadata.keywords(),
            metadata.ogImage(),
            metadata.robots(),
            metadata.openGraphType(),
            openGraphProperties,
            metadata.structuredDataJson(),
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

}
