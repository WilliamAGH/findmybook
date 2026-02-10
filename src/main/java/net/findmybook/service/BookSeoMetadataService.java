package net.findmybook.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;
import net.findmybook.application.seo.BookSeoMetadataUseCase;
import net.findmybook.application.seo.RouteSeoMetadataUseCase;
import net.findmybook.application.seo.SeoPresentationDefaults;
import net.findmybook.application.seo.SeoRouteManifestUseCase;
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
import org.springframework.stereotype.Service;

/**
 * Compatibility adapter for controller-facing SEO contracts.
 *
 * <p>This service keeps existing controller/test call sites stable while
 * delegating metadata composition to application-layer SEO use cases.
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
            canonicalUrlResolver, manifestUseCase, routeStructuredDataRenderer, seoMarkupFormatter);
        this.bookSeoMetadataUseCase = new BookSeoMetadataUseCase(
            new BookStructuredDataRenderer(new ObjectMapper(), seoMarkupFormatter),
            new BookOpenGraphPropertyFactory(seoMarkupFormatter),
            new BookOpenGraphImageResolver(localDiskCoverCacheService),
            canonicalUrlResolver,
            seoMarkupFormatter,
            routeStructuredDataRenderer
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
        return toServiceSeoMetadata(routeSeoMetadataUseCase.homeMetadata());
    }

    public SeoMetadata searchMetadata() {
        return toServiceSeoMetadata(routeSeoMetadataUseCase.searchMetadata());
    }

    public SeoMetadata exploreMetadata() {
        return toServiceSeoMetadata(routeSeoMetadataUseCase.exploreMetadata());
    }

    public SeoMetadata categoriesMetadata() {
        return toServiceSeoMetadata(routeSeoMetadataUseCase.categoriesMetadata());
    }

    public SeoMetadata bookFallbackMetadata(String identifier) {
        return toServiceSeoMetadata(bookSeoMetadataUseCase.bookFallbackMetadata(identifier));
    }

    public SeoMetadata bookMetadata(Book book, int maxDescriptionLength) {
        return toServiceSeoMetadata(bookSeoMetadataUseCase.bookMetadata(book, maxDescriptionLength));
    }

    public SeoMetadata sitemapMetadata(String canonicalPath) {
        return toServiceSeoMetadata(routeSeoMetadataUseCase.sitemapMetadata(canonicalPath));
    }

    public SeoMetadata notFoundMetadata(String requestPath) {
        return toServiceSeoMetadata(routeSeoMetadataUseCase.notFoundMetadata(requestPath));
    }

    public SeoMetadata errorMetadata(int statusCode, String requestPath) {
        return toServiceSeoMetadata(routeSeoMetadataUseCase.errorMetadata(statusCode, requestPath));
    }

    public RouteManifest routeManifest() {
        return toServiceRouteManifest(seoRouteManifestUseCase.routeManifest());
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

    public String renderSpaShell(SeoMetadata seoMetadata) {
        var ctx = new SpaShellRenderContext(
            toDomainSeoMetadata(seoMetadata),
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

    private static SeoMetadata toServiceSeoMetadata(net.findmybook.domain.seo.SeoMetadata metadata) {
        return new SeoMetadata(
            metadata.title(),
            metadata.description(),
            metadata.canonicalUrl(),
            metadata.keywords(),
            metadata.ogImage(),
            metadata.robots(),
            metadata.openGraphType(),
            metadata.openGraphProperties(),
            metadata.structuredDataJson()
        );
    }

    private static net.findmybook.domain.seo.SeoMetadata toDomainSeoMetadata(SeoMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return new net.findmybook.domain.seo.SeoMetadata(
            metadata.title(),
            metadata.description(),
            metadata.canonicalUrl(),
            metadata.keywords(),
            metadata.ogImage(),
            metadata.robots(),
            metadata.openGraphType(),
            metadata.openGraphProperties(),
            metadata.structuredDataJson()
        );
    }

    private static RouteManifest toServiceRouteManifest(net.findmybook.domain.seo.RouteManifest manifest) {
        List<RouteDefinition> routeDefinitions = manifest.publicRoutes().stream()
            .map(BookSeoMetadataService::toServiceRouteDefinition)
            .toList();
        return new RouteManifest(manifest.version(), routeDefinitions, manifest.passthroughPrefixes());
    }

    private static RouteDefinition toServiceRouteDefinition(net.findmybook.domain.seo.RouteDefinition routeDefinition) {
        return new RouteDefinition(
            routeDefinition.name(),
            routeDefinition.matchType(),
            routeDefinition.pattern(),
            routeDefinition.paramNames(),
            routeDefinition.defaults(),
            routeDefinition.allowedQueryParams(),
            routeDefinition.canonicalPathTemplate()
        );
    }

    public record RouteManifest(
        int version,
        List<RouteDefinition> publicRoutes,
        List<String> passthroughPrefixes
    ) {
    }

    public record RouteDefinition(
        String name,
        String matchType,
        String pattern,
        List<String> paramNames,
        Map<String, String> defaults,
        List<String> allowedQueryParams,
        String canonicalPathTemplate
    ) {
    }

    public record SeoMetadata(
        String title,
        String description,
        String canonicalUrl,
        String keywords,
        String ogImage,
        String robots,
        String openGraphType,
        List<net.findmybook.domain.seo.OpenGraphProperty> openGraphProperties,
        String structuredDataJson
    ) {
        public SeoMetadata(String title,
                           String description,
                           String canonicalUrl,
                           String keywords,
                           String ogImage,
                           String robots) {
            this(
                title,
                description,
                canonicalUrl,
                keywords,
                ogImage,
                robots,
                SeoPresentationDefaults.OPEN_GRAPH_TYPE_WEBSITE,
                List.of(),
                ""
            );
        }

        public SeoMetadata {
            openGraphType = hasText(openGraphType)
                ? openGraphType
                : SeoPresentationDefaults.OPEN_GRAPH_TYPE_WEBSITE;
            openGraphProperties = openGraphProperties == null ? List.of() : List.copyOf(openGraphProperties);
            structuredDataJson = structuredDataJson == null ? "" : structuredDataJson;
        }

        private static boolean hasText(String candidate) {
            return candidate != null && !candidate.trim().isEmpty();
        }
    }
}
