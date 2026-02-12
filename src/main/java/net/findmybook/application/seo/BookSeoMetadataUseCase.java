package net.findmybook.application.seo;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.domain.seo.BookSeoMetadataSnapshot;
import net.findmybook.domain.seo.BookSeoMetadataSnapshotReader;
import net.findmybook.domain.seo.OpenGraphProperty;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.model.Book;
import net.findmybook.support.seo.BookGraphRenderRequest;
import net.findmybook.support.seo.BookOpenGraphImageResolver;
import net.findmybook.support.seo.BookOpenGraphPropertyFactory;
import net.findmybook.support.seo.BookStructuredDataRenderer;
import net.findmybook.support.seo.CanonicalUrlResolver;
import net.findmybook.support.seo.RouteGraphRenderRequest;
import net.findmybook.support.seo.RouteStructuredDataRenderer;
import net.findmybook.support.seo.SeoMarkupFormatter;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.SeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/**
 * Composes book-route SEO metadata and structured data payloads.
 *
 * <p>This use case owns book-detail SEO decisions and isolates mapping from
 * support-level metadata helpers into domain SEO contracts.
 */
@Service
public class BookSeoMetadataUseCase {
    private static final Logger log = LoggerFactory.getLogger(BookSeoMetadataUseCase.class);

    private static final String BOOK_FALLBACK_TITLE = "Book Details";
    private static final String BOOK_FALLBACK_DESCRIPTION =
        "Detailed metadata, editions, and recommendations for this book on findmybook.";
    private static final String BOOK_FALLBACK_KEYWORDS =
        "findmybook book details, book metadata, book recommendations";
    private static final String BOOK_ROUTE_PREFIX = "/book/";
    private static final String BOOK_OPEN_GRAPH_ROUTE_PREFIX = "/api/pages/og/book/";
    private static final String WEB_PAGE_SCHEMA_TYPE = "WebPage";

    private final BookStructuredDataRenderer bookStructuredDataRenderer;
    private final BookOpenGraphPropertyFactory bookOpenGraphPropertyFactory;
    private final BookOpenGraphImageResolver bookOpenGraphImageResolver;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final SeoMarkupFormatter seoMarkupFormatter;
    private final RouteStructuredDataRenderer routeStructuredDataRenderer;
    private final BookSeoMetadataSnapshotReader bookSeoMetadataSnapshotReader;

    /**
     * Creates the use case with required rendering collaborators.
     */
    public BookSeoMetadataUseCase(BookStructuredDataRenderer bookStructuredDataRenderer,
                                  BookOpenGraphPropertyFactory bookOpenGraphPropertyFactory,
                                  BookOpenGraphImageResolver bookOpenGraphImageResolver,
                                  CanonicalUrlResolver canonicalUrlResolver,
                                  SeoMarkupFormatter seoMarkupFormatter,
                                  RouteStructuredDataRenderer routeStructuredDataRenderer,
                                  BookSeoMetadataSnapshotReader bookSeoMetadataSnapshotReader) {
        this.bookStructuredDataRenderer = bookStructuredDataRenderer;
        this.bookOpenGraphPropertyFactory = bookOpenGraphPropertyFactory;
        this.bookOpenGraphImageResolver = bookOpenGraphImageResolver;
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.seoMarkupFormatter = seoMarkupFormatter;
        this.routeStructuredDataRenderer = routeStructuredDataRenderer;
        this.bookSeoMetadataSnapshotReader = bookSeoMetadataSnapshotReader;
    }

    /**
     * Builds fallback metadata for unresolved book routes.
     *
     * @param identifier unresolved route identifier
     * @return fallback book SEO metadata
     */
    public SeoMetadata bookFallbackMetadata(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            throw new IllegalArgumentException("identifier must not be blank when generating fallback SEO metadata");
        }
        String title = BOOK_FALLBACK_TITLE;
        String description = BOOK_FALLBACK_DESCRIPTION;
        String canonicalUrl = canonicalUrlResolver.normalizePublicUrl(BOOK_ROUTE_PREFIX + identifier);
        String keywords = BOOK_FALLBACK_KEYWORDS;
        String ogImage = bookOpenGraphImageUrl(identifier);
        String robots = SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW;

        String fullTitle = seoMarkupFormatter.pageTitle(
            title,
            SeoPresentationDefaults.PAGE_TITLE_SUFFIX,
            SeoPresentationDefaults.BRAND_NAME
        );

        String structuredDataJson = routeStructuredDataRenderer.renderRouteGraph(
            new RouteGraphRenderRequest(
                canonicalUrl,
                fullTitle,
                description,
                ogImage,
                SeoPresentationDefaults.BRAND_NAME,
                ApplicationConstants.Urls.BASE_URL,
                WEB_PAGE_SCHEMA_TYPE
            )
        );

        return new SeoMetadata(
            title,
            description,
            canonicalUrl,
            keywords,
            ogImage,
            robots,
            SeoPresentationDefaults.OPEN_GRAPH_TYPE_WEBSITE,
            List.of(),
            structuredDataJson
        );
    }

    /**
     * Builds canonical SEO metadata for a resolved book detail route.
     *
     * @param book canonical book metadata
     * @param maxDescriptionLength maximum route description length
     * @return book detail SEO metadata with Open Graph and JSON-LD payloads
     */
    public SeoMetadata bookMetadata(Book book, int maxDescriptionLength) {
        if (book == null) {
            throw new IllegalArgumentException("Book must not be null when generating SEO metadata");
        }

        String fallbackTitle = StringUtils.hasText(book.getTitle()) ? book.getTitle().trim() : BOOK_FALLBACK_TITLE;
        String fallbackDescription = SeoUtils.truncateDescription(book.getDescription(), maxDescriptionLength);
        Optional<BookSeoMetadataSnapshot> persistedSnapshot = resolveSeoSnapshot(book);
        String title = persistedSnapshot
            .map(BookSeoMetadataSnapshot::seoTitle)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .orElse(fallbackTitle);
        String description = persistedSnapshot
            .map(BookSeoMetadataSnapshot::seoDescription)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .orElse(fallbackDescription);
        String canonicalIdentifier = StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
        String canonicalUrl = canonicalUrlResolver.normalizePublicUrl(BOOK_ROUTE_PREFIX + canonicalIdentifier);
        String keywords = SeoUtils.generateKeywords(book);
        String coverImage = canonicalUrlResolver.normalizePublicUrl(
            bookOpenGraphImageResolver.resolveBookImage(book, ApplicationConstants.Urls.OG_LOGO)
        );
        String ogImage = bookOpenGraphImageUrl(canonicalIdentifier);

        String fullTitle = seoMarkupFormatter.pageTitle(
            title,
            SeoPresentationDefaults.PAGE_TITLE_SUFFIX,
            SeoPresentationDefaults.BRAND_NAME
        );
        String structuredDataJson = bookStructuredDataRenderer.renderBookGraph(
            new BookGraphRenderRequest(
                book,
                canonicalUrl,
                fullTitle,
                title,
                description,
                coverImage,
                SeoPresentationDefaults.BRAND_NAME,
                ApplicationConstants.Urls.BASE_URL
            )
        );
        List<OpenGraphProperty> openGraphProperties = bookOpenGraphPropertyFactory.fromBook(book);

        return new SeoMetadata(
            title,
            description,
            canonicalUrl,
            keywords,
            ogImage,
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW,
            SeoPresentationDefaults.OPEN_GRAPH_TYPE_BOOK,
            openGraphProperties,
            structuredDataJson
        );
    }

    /**
     * Renders the dynamic OpenGraph PNG for a canonical book route.
     *
     * @param book canonical book metadata
     * @param identifier route identifier for fallback labels
     * @return encoded PNG bytes
     */
    public byte[] bookOpenGraphImage(Book book, String identifier) {
        if (book == null) {
            throw new IllegalArgumentException("Book must not be null when rendering OpenGraph image");
        }
        return bookOpenGraphImageResolver.renderBookOpenGraphImage(book, identifier);
    }

    /**
     * Renders the fallback OpenGraph PNG for unresolved book routes.
     *
     * @param identifier unresolved route identifier
     * @return encoded PNG bytes
     */
    public byte[] bookOpenGraphFallbackImage(String identifier) {
        return bookOpenGraphImageResolver.renderFallbackOpenGraphImage(identifier);
    }

    private Optional<BookSeoMetadataSnapshot> resolveSeoSnapshot(Book book) {
        if (book == null || !StringUtils.hasText(book.getId())) {
            return Optional.empty();
        }
        try {
            UUID bookId = UUID.fromString(book.getId().trim());
            return fetchSeoSnapshotWithFallback(bookId);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private Optional<BookSeoMetadataSnapshot> fetchSeoSnapshotWithFallback(UUID bookId) {
        try {
            return bookSeoMetadataSnapshotReader.fetchCurrent(bookId);
        } catch (DataAccessException dataAccessException) {
            log.error("Failed reading persisted book SEO metadata for {}. Falling back to legacy SEO metadata.",
                bookId,
                dataAccessException
            );
            return Optional.empty();
        }
    }

    private String bookOpenGraphImageUrl(String identifier) {
        String safeIdentifier = StringUtils.hasText(identifier)
            ? UriUtils.encodePathSegment(identifier.trim(), StandardCharsets.UTF_8)
            : "book-details";
        return canonicalUrlResolver.normalizePublicUrl(BOOK_OPEN_GRAPH_ROUTE_PREFIX + safeIdentifier);
    }
}
