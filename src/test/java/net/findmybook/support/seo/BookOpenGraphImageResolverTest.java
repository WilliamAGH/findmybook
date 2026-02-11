package net.findmybook.support.seo;

import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImages;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.image.CoverUrlSafetyValidator;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void should_RenderFallbackOpenGraphImage_When_BookCoverCannotBeResolved() {
        LocalDiskCoverCacheService cacheService = mock(LocalDiskCoverCacheService.class);
        when(cacheService.getLocalPlaceholderPath()).thenReturn("/assets/placeholder-book-cover.png");

        Book book = new Book();
        book.setTitle("Test Book");
        book.setAuthors(java.util.List.of("Author One"));

        BookOpenGraphImageResolver resolver = new BookOpenGraphImageResolver(cacheService);
        byte[] imageBytes = resolver.renderBookOpenGraphImage(book, "test-book");

        assertTrue(imageBytes.length > 32);
        assertEquals((byte) 0x89, imageBytes[0]);
        assertEquals((byte) 0x50, imageBytes[1]);
        assertEquals((byte) 0x4E, imageBytes[2]);
        assertEquals((byte) 0x47, imageBytes[3]);
    }

    @Test
    void should_RenderFallbackOpenGraphImage_When_IdentifierIsUnresolved() {
        LocalDiskCoverCacheService cacheService = mock(LocalDiskCoverCacheService.class);
        when(cacheService.getLocalPlaceholderPath()).thenReturn("/assets/placeholder-book-cover.png");

        BookOpenGraphImageResolver resolver = new BookOpenGraphImageResolver(cacheService);
        byte[] imageBytes = resolver.renderFallbackOpenGraphImage("missing-book");

        assertTrue(imageBytes.length > 32);
        assertEquals((byte) 0x89, imageBytes[0]);
        assertEquals((byte) 0x50, imageBytes[1]);
        assertEquals((byte) 0x4E, imageBytes[2]);
        assertEquals((byte) 0x47, imageBytes[3]);
    }
}

class BookOpenGraphCoverImageLoaderTest {

    @Test
    void should_ReturnNull_When_RemoteCoverFetchTimesOut() {
        ExchangeFunction neverRespondingExchange = request -> Mono.never();
        WebClient.Builder webClientBuilder = WebClient.builder().exchangeFunction(neverRespondingExchange);
        BookOpenGraphCoverImageLoader loader = new BookOpenGraphCoverImageLoader(
            webClientBuilder,
            new CanonicalUrlResolver(),
            new CoverUrlSafetyValidator(),
            Duration.ofMillis(10)
        );

        assertNull(loader.load("https://findmybook.net/images/non-existent-cover.png"));
    }
}
