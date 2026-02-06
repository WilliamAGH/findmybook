package net.findmybook.service.image;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletionException;
import net.findmybook.exception.S3UploadException;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.model.image.ImageProvenanceData;
import net.findmybook.model.image.ImageResolutionPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class LocalDiskCoverCacheServiceTest {

    @Mock
    private S3BookCoverService s3BookCoverService;

    private LocalDiskCoverCacheService service;

    @BeforeEach
    void setUp() {
        service = new LocalDiskCoverCacheService(s3BookCoverService);
    }

    @Test
    void should_FailFuture_When_ImageUrlMissing() {
        assertThatThrownBy(
            () -> service.cacheRemoteImageAsync(" ", "book-1", new ImageProvenanceData(), "OPEN_LIBRARY").join()
        )
            .isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(S3UploadException.class);
    }

    @Test
    void should_PropagateS3UploadFailure_When_DelegatedUploadFails() {
        when(s3BookCoverService.uploadCoverToS3Async(
            eq("https://covers.openlibrary.org/b/id/1-L.jpg"),
            eq("book-1"),
            eq("OPEN_LIBRARY"),
            any(ImageProvenanceData.class)
        )).thenReturn(Mono.error(new S3UploadException("book-1", "https://covers.openlibrary.org/b/id/1-L.jpg", new IllegalStateException("boom"))));

        assertThatThrownBy(
            () -> service.cacheRemoteImageAsync(
                "https://covers.openlibrary.org/b/id/1-L.jpg",
                "book-1",
                new ImageProvenanceData(),
                "OPEN_LIBRARY"
            ).join()
        )
            .isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(S3UploadException.class);
    }

    @Test
    void should_ReturnS3Details_When_DelegatedUploadSucceeds() {
        ImageDetails details = new ImageDetails(
            "https://cdn.example.com/books/v1/book-1/open-library.jpg",
            "S3",
            "books/v1/book-1/open-library.jpg",
            CoverImageSource.UNDEFINED,
            ImageResolutionPreference.ORIGINAL
        );
        details.setStorageKey("books/v1/book-1/open-library.jpg");

        when(s3BookCoverService.uploadCoverToS3Async(
            eq("https://covers.openlibrary.org/b/id/1-L.jpg"),
            eq("book-1"),
            eq("OPEN_LIBRARY"),
            any(ImageProvenanceData.class)
        )).thenReturn(Mono.just(details));

        service.cacheRemoteImageAsync(
            "https://covers.openlibrary.org/b/id/1-L.jpg",
            "book-1",
            new ImageProvenanceData(),
            "OPEN_LIBRARY"
        ).join();
    }
}
