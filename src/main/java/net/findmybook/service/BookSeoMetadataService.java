package net.findmybook.service;

import java.util.regex.Pattern;
import java.util.Optional;
import net.findmybook.application.seo.BookSeoMetadataUseCase;
import net.findmybook.application.seo.RouteSeoMetadataUseCase;
import net.findmybook.application.seo.SeoPresentationDefaults;
import net.findmybook.application.seo.SeoRouteManifestUseCase;
import net.findmybook.domain.seo.RouteManifest;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.model.Book;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import net.findmybook.support.seo.BookOpenGraphImageResolver;
import net.findmybook.support.seo.BookOpenGraphPropertyFactory;
import net.findmybook.support.seo.BookStructuredDataRenderer;
import net.findmybook.support.seo.CanonicalUrlResolver;
import net.findmybook.support.seo.OpenGraphHeadTagRenderer;
import net.findmybook.support.seo.RouteStructuredDataRenderer;
import net.findmybook.support.seo.SeoMarkupFormatter;
import net.findmybook.support.seo.SeoRouteManifestProvider;
import net.findmybook.support.seo.SpaShellDocumentRenderer;
import net.findmybook.support.seo.SpaShellRenderContext;
import net.findmybook.util.ApplicationConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Facade for controller-facing SEO contracts.
 *
 * <p>Delegates metadata composition to application-layer SEO use cases
 * and returns canonical {@link SeoMetadata} and {@link RouteManifest}
 * domain types directly.
 */
@Service
public class BookSeoMetadataService {

    private final RouteSeoMetadataUseCase routeSeoMetadataUseCase;
    private final BookSeoMetadataUseCase bookSeoMetadataUseCase;
    private final SeoRouteManifestUseCase seoRouteManifestUseCase;
    private final SpaShellDocumentRenderer spaShellDocumentRenderer;

    /**
     * Backward-compatible constructor retained for tests that instantiate the service manually.
     *
     * @param localDiskCoverCacheService local cover cache dependency
     */
    public BookSeoMetadataService(LocalDiskCoverCacheService localDiskCoverCacheService) {
        CanonicalUrlResolver canonicalUrlResolver = new CanonicalUrlResolver();
        SeoMarkupFormatter seoMarkupFormatter = new SeoMarkupFormatter();
        SeoRouteManifestProvider seoRouteManifestProvider = new SeoRouteManifestProvider();
        SeoRouteManifestUseCase manifestUseCase = new SeoRouteManifestUseCase(seoRouteManifestProvider);
        RouteStructuredDataRenderer routeStructuredDataRenderer = new RouteStructuredDataRenderer();

        this.routeSeoMetadataUseCase = new RouteSeoMetadataUseCase(
            canonicalUrlResolver, manifestUseCase, routeStructuredDataRenderer, seoMarkupFormatter,
            new net.findmybook.support.seo.RouteOpenGraphPngRenderer());
        this.bookSeoMetadataUseCase = new BookSeoMetadataUseCase(
            new BookStructuredDataRenderer(new ObjectMapper(), seoMarkupFormatter),
            new BookOpenGraphPropertyFactory(seoMarkupFormatter),
            new BookOpenGraphImageResolver(localDiskCoverCacheService),
            canonicalUrlResolver,
            seoMarkupFormatter,
            routeStructuredDataRenderer,
            bookId -> Optional.empty()
        );
        this.seoRouteManifestUseCase = manifestUseCase;
        this.spaShellDocumentRenderer = new SpaShellDocumentRenderer(
            seoMarkupFormatter,
            new OpenGraphHeadTagRenderer(seoMarkupFormatter),
            canonicalUrlResolver
        );
    }

    @Autowired
    public BookSeoMetadataService(RouteSeoMetadataUseCase routeSeoMetadataUseCase,
                                  BookSeoMetadataUseCase bookSeoMetadataUseCase,
                                  SeoRouteManifestUseCase seoRouteManifestUseCase,
                                  SpaShellDocumentRenderer spaShellDocumentRenderer) {
        this.routeSeoMetadataUseCase = routeSeoMetadataUseCase;
        this.bookSeoMetadataUseCase = bookSeoMetadataUseCase;
        this.seoRouteManifestUseCase = seoRouteManifestUseCase;
        this.spaShellDocumentRenderer = spaShellDocumentRenderer;
    }

    public SeoMetadata homeMetadata() {
        return routeSeoMetadataUseCase.homeMetadata();
    }

    public SeoMetadata searchMetadata() {
        return routeSeoMetadataUseCase.searchMetadata();
    }

    public SeoMetadata exploreMetadata() {
        return routeSeoMetadataUseCase.exploreMetadata();
    }

    public SeoMetadata categoriesMetadata() {
        return routeSeoMetadataUseCase.categoriesMetadata();
    }

    public SeoMetadata bookFallbackMetadata(String identifier) {
        return bookSeoMetadataUseCase.bookFallbackMetadata(identifier);
    }

    public SeoMetadata bookMetadata(Book book, int maxDescriptionLength) {
        return bookSeoMetadataUseCase.bookMetadata(book, maxDescriptionLength);
    }

    /**
     * Renders the dynamic OpenGraph image bytes for a resolved book route.
     *
     * @param book canonical book metadata
     * @param identifier route identifier used in cache keying and fallback labels
     * @return encoded PNG bytes for {@code og:image}
     */
    @Cacheable(
        value = "bookOgImages",
        key = "#identifier + ':' + (#book != null && #book.getId() != null ? #book.getId() : 'unknown')",
        sync = true
    )
    public byte[] renderBookOpenGraphImage(Book book, String identifier) {
        return bookSeoMetadataUseCase.bookOpenGraphImage(book, identifier);
    }

    /**
     * Renders fallback OpenGraph image bytes when the book route cannot be resolved.
     *
     * @param identifier unresolved route identifier
     * @return encoded fallback PNG bytes
     */
    @Cacheable(value = "bookOgImages", key = "'missing:' + #identifier", sync = true)
    public byte[] renderFallbackBookOpenGraphImage(String identifier) {
        return bookSeoMetadataUseCase.bookOpenGraphFallbackImage(identifier);
    }

    /**
     * Renders the branded OpenGraph PNG for non-book routes (homepage, search, explore, etc.).
     *
     * <p>The rendered image is deterministic and cached after the first call via
     * the underlying {@link RouteOpenGraphPngRenderer} in-process cache.
     *
     * @return encoded 1200x630 PNG bytes
     */
    public byte[] renderRouteOpenGraphImage() {
        return routeSeoMetadataUseCase.renderRouteOpenGraphImage();
    }

    public SeoMetadata sitemapMetadata(String canonicalPath) {
        return routeSeoMetadataUseCase.sitemapMetadata(canonicalPath);
    }

    public SeoMetadata notFoundMetadata(String requestPath) {
        return routeSeoMetadataUseCase.notFoundMetadata(requestPath);
    }

    public SeoMetadata errorMetadata(int statusCode, String requestPath) {
        return routeSeoMetadataUseCase.errorMetadata(statusCode, requestPath);
    }

    public RouteManifest routeManifest() {
        return seoRouteManifestUseCase.routeManifest();
    }

    public String routeManifestJson() {
        return seoRouteManifestUseCase.routeManifestJson();
    }

    public String defaultSitemapPath() {
        return seoRouteManifestUseCase.defaultSitemapPath();
    }

    public Pattern bookRoutePattern() {
        return seoRouteManifestUseCase.bookRoutePattern();
    }

    public Pattern sitemapRoutePattern() {
        return seoRouteManifestUseCase.sitemapRoutePattern();
    }

    /**
     * Renders the full SPA shell HTML document for server-side rendering.
     *
     * @param seoMetadata route-level SEO metadata to embed in the shell
     * @return fully rendered HTML document string
     */
    public String renderSpaShell(SeoMetadata seoMetadata) {
        var ctx = new SpaShellRenderContext(
            seoMetadata,
            routeSeoMetadataUseCase.homeMetadata(),
            SeoPresentationDefaults.PAGE_TITLE_SUFFIX,
            SeoPresentationDefaults.BRAND_NAME,
            SeoPresentationDefaults.DEFAULT_DESCRIPTION,
            SeoPresentationDefaults.DEFAULT_KEYWORDS,
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW,
            SeoPresentationDefaults.OPEN_GRAPH_TYPE_WEBSITE,
            SeoPresentationDefaults.OPEN_GRAPH_IMAGE_ALT,
            SeoPresentationDefaults.THEME_COLOR,
            routeManifestJson(),
            ApplicationConstants.Urls.BASE_URL
        );
        return spaShellDocumentRenderer.render(ctx);
    }
}
