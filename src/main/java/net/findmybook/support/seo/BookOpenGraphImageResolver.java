package net.findmybook.support.seo;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Locale;
import net.findmybook.model.Book;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import net.findmybook.util.cover.CoverUrlResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the canonical Open Graph image for book routes and renders dynamic
 * social-preview cards for crawler-facing route metadata.
 */
@Component
public class BookOpenGraphImageResolver {

    private static final String PLACEHOLDER_COVER_MARKER = "placeholder-book-cover";
    private static final String HTTP_SCHEME_PREFIX = "http";
    private static final String DEFAULT_CARD_TITLE = "Book Details";
    private static final int MAX_TITLE_LENGTH = 96;

    private final LocalDiskCoverCacheService localDiskCoverCacheService;
    private final BookOpenGraphCoverImageLoader bookOpenGraphCoverImageLoader;
    private final BookOpenGraphPngRenderer bookOpenGraphPngRenderer;

    public BookOpenGraphImageResolver(LocalDiskCoverCacheService localDiskCoverCacheService) {
        this(
            localDiskCoverCacheService,
            new BookOpenGraphCoverImageLoader(),
            new BookOpenGraphPngRenderer()
        );
    }

    @Autowired
    public BookOpenGraphImageResolver(LocalDiskCoverCacheService localDiskCoverCacheService,
                                      BookOpenGraphCoverImageLoader bookOpenGraphCoverImageLoader,
                                      BookOpenGraphPngRenderer bookOpenGraphPngRenderer) {
        this.localDiskCoverCacheService = localDiskCoverCacheService;
        this.bookOpenGraphCoverImageLoader = bookOpenGraphCoverImageLoader;
        this.bookOpenGraphPngRenderer = bookOpenGraphPngRenderer;
    }

    /**
     * Returns the first valid social-preview image URL from preferred cover sources.
     *
     * @param book canonical book metadata
     * @param fallbackImage fallback image when no candidate cover URL is suitable
     * @return renderable image URL for Open Graph tags
     */
    public String resolveBookImage(Book book, String fallbackImage) {
        if (book == null) {
            return fallbackImage;
        }

        String primaryCandidate = null;
        String fallbackCandidate = null;
        if (book.getCoverImages() != null && StringUtils.hasText(book.getCoverImages().getPreferredUrl())) {
            primaryCandidate = book.getCoverImages().getPreferredUrl();
            fallbackCandidate = book.getCoverImages().getFallbackUrl();
        } else if (StringUtils.hasText(book.getExternalImageUrl())) {
            primaryCandidate = book.getExternalImageUrl();
            fallbackCandidate = book.getS3ImagePath();
        } else if (StringUtils.hasText(book.getS3ImagePath())) {
            primaryCandidate = book.getS3ImagePath();
        }

        String coverUrl = CoverUrlResolver.resolve(
            primaryCandidate,
            fallbackCandidate,
            book.getCoverImageWidth(),
            book.getCoverImageHeight(),
            book.getIsCoverHighResolution()
        ).url();

        String placeholder = localDiskCoverCacheService.getLocalPlaceholderPath();
        if (isSocialPreviewImage(coverUrl, placeholder)) {
            return coverUrl;
        }
        return fallbackImage;
    }

    /**
     * Renders a 1200x630 Open Graph PNG for a canonical book route.
     *
     * @param book canonical book metadata
     * @param identifier route identifier used as display fallback
     * @return encoded PNG bytes
     */
    public byte[] renderBookOpenGraphImage(Book book, String identifier) {
        if (book == null) {
            return renderFallbackOpenGraphImage(identifier);
        }
        String title = resolveTitle(book, identifier);
        String subtitle = resolveSubtitle(book);
        BufferedImage coverImage = loadCoverImage(book);
        return bookOpenGraphPngRenderer.render(title, subtitle, coverImage);
    }

    /**
     * Renders a branded Open Graph PNG for unresolved book identifiers.
     *
     * @param identifier unresolved route identifier
     * @return encoded PNG bytes
     */
    public byte[] renderFallbackOpenGraphImage(String identifier) {
        String normalizedIdentifier = StringUtils.hasText(identifier) ? identifier.trim() : "";
        return bookOpenGraphPngRenderer.render(
            DEFAULT_CARD_TITLE,
            normalizedIdentifier,
            null
        );
    }

    private BufferedImage loadCoverImage(Book book) {
        String placeholder = localDiskCoverCacheService.getLocalPlaceholderPath();
        String resolvedCover = resolveBookImage(book, placeholder);
        if (!StringUtils.hasText(resolvedCover) || resolvedCover.contains(PLACEHOLDER_COVER_MARKER)) {
            return null;
        }
        return bookOpenGraphCoverImageLoader.load(resolvedCover);
    }

    private String resolveTitle(Book book, String identifier) {
        if (book != null && StringUtils.hasText(book.getTitle())) {
            return truncate(book.getTitle().trim(), MAX_TITLE_LENGTH);
        }
        if (StringUtils.hasText(identifier)) {
            return truncate(identifier.trim(), MAX_TITLE_LENGTH);
        }
        return DEFAULT_CARD_TITLE;
    }

    private String resolveSubtitle(Book book) {
        if (book != null && book.getAuthors() != null && !book.getAuthors().isEmpty()) {
            LinkedHashMap<String, String> uniqueAuthors = new LinkedHashMap<>();
            for (String rawAuthor : book.getAuthors()) {
                if (!StringUtils.hasText(rawAuthor)) {
                    continue;
                }
                String normalizedAuthor = rawAuthor.trim().replaceFirst("^(?i)by\\s+", "");
                if (!StringUtils.hasText(normalizedAuthor)) {
                    continue;
                }
                String dedupeKey = normalizedAuthor.toLowerCase(Locale.ROOT);
                uniqueAuthors.putIfAbsent(dedupeKey, normalizedAuthor);
                if (uniqueAuthors.size() >= 2) {
                    break;
                }
            }
            String authorLine = String.join(", ", uniqueAuthors.values());
            if (StringUtils.hasText(authorLine)) {
                return "by " + authorLine;
            }
        }
        return "";
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(1, maxLength - 3)) + "...";
    }

    private boolean isSocialPreviewImage(String candidate, String placeholder) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        String trimmedCandidate = candidate.trim();
        if (placeholder != null && placeholder.equals(trimmedCandidate)) {
            return false;
        }
        if (trimmedCandidate.contains(PLACEHOLDER_COVER_MARKER)) {
            return false;
        }
        return trimmedCandidate.startsWith("/") || trimmedCandidate.toLowerCase(Locale.ROOT).startsWith(HTTP_SCHEME_PREFIX);
    }
}
