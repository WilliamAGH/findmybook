package net.findmybook.support.seo;

import java.util.Locale;
import net.findmybook.model.Book;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the canonical Open Graph image for book routes from available cover sources.
 */
@Component
public class BookOpenGraphImageResolver {

    private static final String PLACEHOLDER_COVER_MARKER = "placeholder-book-cover";
    private static final String HTTP_SCHEME_PREFIX = "http";

    private final LocalDiskCoverCacheService localDiskCoverCacheService;

    public BookOpenGraphImageResolver(LocalDiskCoverCacheService localDiskCoverCacheService) {
        this.localDiskCoverCacheService = localDiskCoverCacheService;
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

        String coverUrl = null;
        if (book.getCoverImages() != null && StringUtils.hasText(book.getCoverImages().getPreferredUrl())) {
            coverUrl = book.getCoverImages().getPreferredUrl();
        } else if (StringUtils.hasText(book.getExternalImageUrl())) {
            coverUrl = book.getExternalImageUrl();
        } else if (StringUtils.hasText(book.getS3ImagePath())) {
            coverUrl = book.getS3ImagePath();
        }

        String placeholder = localDiskCoverCacheService.getLocalPlaceholderPath();
        if (isSocialPreviewImage(coverUrl, placeholder)) {
            return coverUrl;
        }
        return fallbackImage;
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
