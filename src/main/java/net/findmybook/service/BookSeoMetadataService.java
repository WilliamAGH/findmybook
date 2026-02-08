package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.SeoUtils;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

/**
 * Builds and applies SEO metadata for server-rendered pages.
 */
@Service
public class BookSeoMetadataService {

    private static final String PLACEHOLDER_COVER_MARKER = "placeholder-book-cover";
    private static final String HTTP_SCHEME_PREFIX = "http";

    private final LocalDiskCoverCacheService localDiskCoverCacheService;

    public BookSeoMetadataService(LocalDiskCoverCacheService localDiskCoverCacheService) {
        this.localDiskCoverCacheService = localDiskCoverCacheService;
    }

    public SeoMetadata homeMetadata() {
        return new SeoMetadata(
            "Home",
            "Discover your next favorite book with our recommendation engine. Explore recently viewed books and new arrivals.",
            ApplicationConstants.Urls.BASE_URL + "/",
            "book recommendations, find books, book suggestions, reading, literature, home",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE
        );
    }

    public SeoMetadata searchMetadata() {
        return new SeoMetadata(
            "Search Books",
            "Search our extensive catalog of books by title, author, or ISBN. Find detailed information and recommendations.",
            ApplicationConstants.Urls.BASE_URL + "/search",
            "book search, find books by title, find books by author, isbn lookup, book catalog",
            ApplicationConstants.Urls.DEFAULT_SOCIAL_IMAGE
        );
    }

    public SeoMetadata bookFallbackMetadata(String identifier) {
        return new SeoMetadata(
            "Book Details",
            "Detailed information about the selected book.",
            ApplicationConstants.Urls.BASE_URL + "/book/" + identifier,
            "book, literature, reading, book details",
            ApplicationConstants.Urls.OG_LOGO
        );
    }

    public SeoMetadata bookMetadata(Book book, int maxDescriptionLength) {
        if (book == null) {
            throw new IllegalArgumentException("Book must not be null when generating SEO metadata");
        }
        String title = StringUtils.hasText(book.getTitle()) ? book.getTitle() : "Book Details";
        String description = SeoUtils.truncateDescription(book.getDescription(), maxDescriptionLength);
        String canonicalIdentifier = StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
        String canonicalUrl = ApplicationConstants.Urls.BASE_URL + "/book/" + canonicalIdentifier;
        String keywords = SeoUtils.generateKeywords(book);
        String ogImage = resolveOgImage(book);

        return new SeoMetadata(title, description, canonicalUrl, keywords, ogImage);
    }

    public void apply(Model model, SeoMetadata seoMetadata) {
        model.addAttribute("title", seoMetadata.title());
        model.addAttribute("description", seoMetadata.description());
        model.addAttribute("canonicalUrl", seoMetadata.canonicalUrl().startsWith(HTTP_SCHEME_PREFIX)
            ? seoMetadata.canonicalUrl()
            : ApplicationConstants.Urls.BASE_URL + seoMetadata.canonicalUrl());
        model.addAttribute("keywords", seoMetadata.keywords());
        model.addAttribute("ogImage", seoMetadata.ogImage());
    }

    private String resolveOgImage(Book book) {
        String coverUrl = book.getS3ImagePath();
        String placeholder = localDiskCoverCacheService.getLocalPlaceholderPath();
        if (StringUtils.hasText(coverUrl)
            && (placeholder == null || !placeholder.equals(coverUrl))
            && !coverUrl.contains(PLACEHOLDER_COVER_MARKER)) {
            return coverUrl;
        }
        return ApplicationConstants.Urls.OG_LOGO;
    }

    public record SeoMetadata(
        String title,
        String description,
        String canonicalUrl,
        String keywords,
        String ogImage
    ) {}
}
