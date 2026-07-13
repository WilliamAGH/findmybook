package net.findmybook.support.seo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import javax.imageio.ImageIO;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImages;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.service.image.CoverUrlSafetyValidator;
import net.findmybook.service.image.LocalDiskCoverCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final int BITMAP_HEADER_BYTES = 54;
    private static final int BITMAP_INFO_HEADER_BYTES = 40;
    private static final int OVERSIZED_BITMAP_DIMENSION = 100_000;
    private static final int RGB_BITS_PER_PIXEL = 24;
    private static final Duration TEST_REMOTE_FETCH_TIMEOUT = Duration.ofSeconds(1);

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

    @Test
    void should_ReturnNull_When_RemoteCoverExceedsDownloadLimit() {
        byte[] oversizedPayload = new byte[BookOpenGraphCoverImageLoader.MAX_DOWNLOAD_BYTES + 1];
        BookOpenGraphCoverImageLoader loader = remoteLoader(oversizedPayload, MediaType.IMAGE_PNG);

        assertNull(loader.load("https://findmybook.net/images/oversized-cover.png"));
    }

    @Test
    void should_ReturnNull_When_RemoteCoverDimensionsExceedPixelLimit() {
        BookOpenGraphCoverImageLoader loader = remoteLoader(
            oversizedBitmapHeader(),
            MediaType.parseMediaType("image/bmp")
        );

        assertNull(loader.load("https://findmybook.net/images/pixel-bomb.bmp"));
    }

    @Test
    void should_ReturnNormalizedImage_When_RemoteCoverIsSmall() throws IOException {
        BufferedImage sourceImage = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream encodedImage = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(sourceImage, "png", encodedImage));
        BookOpenGraphCoverImageLoader loader = remoteLoader(encodedImage.toByteArray(), MediaType.IMAGE_PNG);

        BufferedImage loadedImage = loader.load("https://findmybook.net/images/small-cover.png");

        assertNotNull(loadedImage);
        assertEquals(2, loadedImage.getWidth());
        assertEquals(3, loadedImage.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, loadedImage.getType());
    }

    @Test
    void should_ReturnNormalizedImage_When_ClasspathCoverExists() {
        ExchangeFunction unexpectedRemoteExchange = request -> Mono.error(
            new AssertionError("Classpath image loading must not perform a remote request")
        );
        BookOpenGraphCoverImageLoader loader = new BookOpenGraphCoverImageLoader(
            WebClient.builder().exchangeFunction(unexpectedRemoteExchange),
            new CanonicalUrlResolver(),
            new CoverUrlSafetyValidator(),
            TEST_REMOTE_FETCH_TIMEOUT
        );

        BufferedImage loadedImage = loader.load("/images/og-logo.png");

        assertNotNull(loadedImage);
        assertEquals(BufferedImage.TYPE_INT_RGB, loadedImage.getType());
    }

    private BookOpenGraphCoverImageLoader remoteLoader(byte[] responseBody, MediaType mediaType) {
        DataBuffer responseBuffer = DefaultDataBufferFactory.sharedInstance.wrap(responseBody);
        ExchangeFunction exchangeFunction = request -> Mono.just(
            ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, mediaType.toString())
                .body(Flux.just(responseBuffer))
                .build()
        );
        return new BookOpenGraphCoverImageLoader(
            WebClient.builder().exchangeFunction(exchangeFunction),
            new CanonicalUrlResolver(),
            new CoverUrlSafetyValidator(),
            TEST_REMOTE_FETCH_TIMEOUT
        );
    }

    private byte[] oversizedBitmapHeader() {
        ByteBuffer header = ByteBuffer.allocate(BITMAP_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'B');
        header.put((byte) 'M');
        header.putInt(BITMAP_HEADER_BYTES);
        header.putShort((short) 0);
        header.putShort((short) 0);
        header.putInt(BITMAP_HEADER_BYTES);
        header.putInt(BITMAP_INFO_HEADER_BYTES);
        header.putInt(OVERSIZED_BITMAP_DIMENSION);
        header.putInt(OVERSIZED_BITMAP_DIMENSION);
        header.putShort((short) 1);
        header.putShort((short) RGB_BITS_PER_PIXEL);
        header.putInt(0);
        header.putInt(0);
        header.putInt(0);
        header.putInt(0);
        header.putInt(0);
        header.putInt(0);
        return header.array();
    }
}
