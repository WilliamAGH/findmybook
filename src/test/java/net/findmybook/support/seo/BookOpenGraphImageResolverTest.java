package net.findmybook.support.seo;

import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImages;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookOpenGraphImageResolverTest {

    @Test
    void should_ReturnPreferredCoverUrl_When_PreferredCoverIsRenderable() {
        LocalDiskCoverCacheService cacheService = mock(LocalDiskCoverCacheService.class);
        when(cacheService.getLocalPlaceholderPath()).thenReturn("/assets/placeholder-book-cover.png");

        Book book = new Book();
        book.setCoverImages(new CoverImages(
            "https://cdn.findmybook.net/covers/book.jpg",
            "https://cdn.findmybook.net/covers/book-fallback.jpg",
            CoverImageSource.GOOGLE_BOOKS
        ));

        BookOpenGraphImageResolver resolver = new BookOpenGraphImageResolver(cacheService);
        String ogImage = resolver.resolveBookImage(book, "https://findmybook.net/images/og-logo.png");

        assertEquals("https://cdn.findmybook.net/covers/book.jpg", ogImage);
    }

    @Test
    void should_ReturnFallbackImage_When_CoverCandidateIsPlaceholder() {
        LocalDiskCoverCacheService cacheService = mock(LocalDiskCoverCacheService.class);
        when(cacheService.getLocalPlaceholderPath()).thenReturn("/assets/placeholder-book-cover.png");

        Book book = new Book();
        book.setExternalImageUrl("/assets/placeholder-book-cover.png");

        BookOpenGraphImageResolver resolver = new BookOpenGraphImageResolver(cacheService);
        String ogImage = resolver.resolveBookImage(book, "https://findmybook.net/images/og-logo.png");

        assertEquals("https://findmybook.net/images/og-logo.png", ogImage);
    }
}
