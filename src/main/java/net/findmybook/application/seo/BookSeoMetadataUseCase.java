package net.findmybook.application.seo;

import java.util.List;
import net.findmybook.domain.seo.OpenGraphProperty;
import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.model.Book;
import net.findmybook.support.seo.BookOpenGraphImageResolver;
import net.findmybook.support.seo.BookOpenGraphPropertyFactory;
import net.findmybook.support.seo.BookStructuredDataRenderer;
import net.findmybook.support.seo.CanonicalUrlResolver;
import net.findmybook.support.seo.SeoMarkupFormatter;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.SeoUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Composes book-route SEO metadata and structured data payloads.
 *
 * <p>This use case owns book-detail SEO decisions and isolates mapping from
 * support-level metadata helpers into domain SEO contracts.
 */
@Service
public class BookSeoMetadataUseCase {

    private final BookStructuredDataRenderer bookStructuredDataRenderer;
    private final BookOpenGraphPropertyFactory bookOpenGraphPropertyFactory;
    private final BookOpenGraphImageResolver bookOpenGraphImageResolver;
    private final CanonicalUrlResolver canonicalUrlResolver;
    private final SeoMarkupFormatter seoMarkupFormatter;

    public BookSeoMetadataUseCase(BookStructuredDataRenderer bookStructuredDataRenderer,
                                  BookOpenGraphPropertyFactory bookOpenGraphPropertyFactory,
                                  BookOpenGraphImageResolver bookOpenGraphImageResolver,
                                  CanonicalUrlResolver canonicalUrlResolver,
                                  SeoMarkupFormatter seoMarkupFormatter) {
        this.bookStructuredDataRenderer = bookStructuredDataRenderer;
        this.bookOpenGraphPropertyFactory = bookOpenGraphPropertyFactory;
        this.bookOpenGraphImageResolver = bookOpenGraphImageResolver;
        this.canonicalUrlResolver = canonicalUrlResolver;
        this.seoMarkupFormatter = seoMarkupFormatter;
    }

    /**
     * Builds fallback metadata for unresolved book routes.
     *
     * @param identifier unresolved route identifier
     * @return fallback book SEO metadata
     */
    public SeoMetadata bookFallbackMetadata(String identifier) {
        return new SeoMetadata(
            "Book Details",
            "Detailed metadata, editions, and recommendations for this book on findmybook.",
            canonicalUrlResolver.normalizePublicUrl("/book/" + identifier),
            "findmybook book details, book metadata, book recommendations",
            ApplicationConstants.Urls.OG_LOGO,
            SeoPresentationDefaults.ROBOTS_INDEX_FOLLOW
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

        String title = StringUtils.hasText(book.getTitle()) ? book.getTitle() : "Book Details";
        String description = SeoUtils.truncateDescription(book.getDescription(), maxDescriptionLength);
        String canonicalIdentifier = StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
        String canonicalUrl = canonicalUrlResolver.normalizePublicUrl("/book/" + canonicalIdentifier);
        String keywords = SeoUtils.generateKeywords(book);
        String ogImage = canonicalUrlResolver.normalizePublicUrl(
            bookOpenGraphImageResolver.resolveBookImage(book, ApplicationConstants.Urls.OG_LOGO)
        );

        String fullTitle = seoMarkupFormatter.pageTitle(
            title,
            SeoPresentationDefaults.PAGE_TITLE_SUFFIX,
            SeoPresentationDefaults.BRAND_NAME
        );
        String structuredDataJson = bookStructuredDataRenderer.renderBookGraph(
            book,
            canonicalUrl,
            fullTitle,
            title,
            description,
            ogImage,
            SeoPresentationDefaults.BRAND_NAME,
            ApplicationConstants.Urls.BASE_URL
        );
        List<OpenGraphProperty> openGraphProperties = bookOpenGraphPropertyFactory.fromBook(book).stream()
            .map(property -> new OpenGraphProperty(property.property(), property.content()))
            .toList();

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
}

