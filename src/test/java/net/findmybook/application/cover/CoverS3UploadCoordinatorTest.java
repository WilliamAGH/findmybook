package net.findmybook.application.cover;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.exception.CoverProcessingException;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.service.image.S3BookCoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class CoverS3UploadCoordinatorTest {

    private S3BookCoverService s3BookCoverService;
    private CoverPersistenceService coverPersistenceService;
    private CoverS3UploadCoordinator coordinator;

    @BeforeEach
    void setUp() {
        s3BookCoverService = Mockito.mock(S3BookCoverService.class);
        coverPersistenceService = Mockito.mock(CoverPersistenceService.class);
        coordinator = new CoverS3UploadCoordinator(
            Optional.of(s3BookCoverService),
            coverPersistenceService,
            new SimpleMeterRegistry()
        );
    }

    @Test
    void should_UseImageLinksFallbackAndPersistMetadata_When_CanonicalUrlMissing() {
        UUID bookId = UUID.randomUUID();
        String bookIdString = bookId.toString();

        BookUpsertEvent event = new BookUpsertEvent(
            bookIdString,
            "legacy-code",
            "Legacy Code",
            true,
            "GOOGLE_BOOKS",
            Map.of("thumbnail", "https://covers.example.com/thumb.jpg"),
            null,
            null
        );

        ImageDetails details = new ImageDetails(
            "https://cdn.example.com/covers/legacy-code.webp",
            "GOOGLE_BOOKS",
            bookIdString,
            CoverImageSource.GOOGLE_BOOKS,
            ImageResolutionPreference.UNKNOWN,
            600,
            900
        );
        details.setStorageKey("covers/legacy-code.webp");

        when(s3BookCoverService.uploadCoverToS3Async(
            "https://covers.example.com/thumb.jpg",
            bookIdString,
            "UNKNOWN"
        )).thenReturn(Mono.just(details));

        when(coverPersistenceService.updateAfterS3Upload(eq(bookId), any(CoverPersistenceService.S3UploadResult.class)))
            .thenReturn(new CoverPersistenceService.PersistenceResult(true, details.getUrlOrPath(), 600, 900, true));

        coordinator.triggerUpload(event);

        verify(s3BookCoverService, times(1)).uploadCoverToS3Async(
            "https://covers.example.com/thumb.jpg",
            bookIdString,
            "UNKNOWN"
        );

        ArgumentCaptor<CoverPersistenceService.S3UploadResult> uploadResultCaptor =
            ArgumentCaptor.forClass(CoverPersistenceService.S3UploadResult.class);
        verify(coverPersistenceService, times(1)).updateAfterS3Upload(eq(bookId), uploadResultCaptor.capture());

        CoverPersistenceService.S3UploadResult uploadResult = uploadResultCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(uploadResult.s3Key()).isEqualTo("covers/legacy-code.webp");
        org.assertj.core.api.Assertions.assertThat(uploadResult.s3CdnUrl())
            .isEqualTo("https://cdn.example.com/covers/legacy-code.webp");
    }

    @Test
    void should_SkipUpload_When_NoCanonicalOrFallbackImageUrlExists() {
        UUID bookId = UUID.randomUUID();
        BookUpsertEvent event = new BookUpsertEvent(
            bookId.toString(),
            "legacy-code",
            "Legacy Code",
            false,
            "GOOGLE_BOOKS",
            Map.of(),
            null,
            "GOOGLE_BOOKS"
        );

        coordinator.triggerUpload(event);

        verify(s3BookCoverService, never()).uploadCoverToS3Async(any(), any(), any());
        verify(coverPersistenceService, never()).updateAfterS3Upload(any(), any());
    }

    @Test
    void should_AvoidRetryAndPersistence_When_UploadFailsWithNonRetryableError() {
        UUID bookId = UUID.randomUUID();
        String bookIdString = bookId.toString();

        BookUpsertEvent event = new BookUpsertEvent(
            bookIdString,
            "legacy-code",
            "Legacy Code",
            false,
            "GOOGLE_BOOKS",
            Map.of("thumbnail", "https://covers.example.com/thumb.jpg"),
            null,
            "GOOGLE_BOOKS"
        );

        when(s3BookCoverService.uploadCoverToS3Async(
            "https://covers.example.com/thumb.jpg",
            bookIdString,
            "GOOGLE_BOOKS"
        )).thenReturn(Mono.error(new CoverProcessingException(bookIdString, "https://covers.example.com/thumb.jpg", "invalid-format")));

        coordinator.triggerUpload(event);

        verify(s3BookCoverService, times(1)).uploadCoverToS3Async(
            "https://covers.example.com/thumb.jpg",
            bookIdString,
            "GOOGLE_BOOKS"
        );
        verify(coverPersistenceService, never()).updateAfterS3Upload(any(), any());
    }
}
