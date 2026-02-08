package net.findmybook.application.cover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        s3BookCoverService = Mockito.mock(S3BookCoverService.class);
        coverPersistenceService = Mockito.mock(CoverPersistenceService.class);
        when(s3BookCoverService.isUploadEnabled()).thenReturn(true);
        meterRegistry = new SimpleMeterRegistry();
        coordinator = new CoverS3UploadCoordinator(
            Optional.of(s3BookCoverService),
            coverPersistenceService,
            meterRegistry
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
        assertThat(uploadResult.s3Key()).isEqualTo("covers/legacy-code.webp");
        assertThat(uploadResult.s3CdnUrl())
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
    void should_SkipUpload_When_S3UploadsDisabled() {
        UUID bookId = UUID.randomUUID();
        when(s3BookCoverService.isUploadEnabled()).thenReturn(false);
        BookUpsertEvent event = new BookUpsertEvent(
            bookId.toString(),
            "legacy-code",
            "Legacy Code",
            true,
            "GOOGLE_BOOKS",
            Map.of("thumbnail", "https://covers.example.com/thumb.jpg"),
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

    @Test
    void should_CountFailureOnly_When_PersistenceUpdateReportsUnsuccessfulStatus() {
        UUID bookId = UUID.randomUUID();
        String bookIdString = bookId.toString();
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

        BookUpsertEvent event = new BookUpsertEvent(
            bookIdString,
            "legacy-code",
            "Legacy Code",
            true,
            "GOOGLE_BOOKS",
            Map.of("thumbnail", "https://covers.example.com/thumb.jpg"),
            null,
            "GOOGLE_BOOKS"
        );

        when(s3BookCoverService.uploadCoverToS3Async(
            "https://covers.example.com/thumb.jpg",
            bookIdString,
            "GOOGLE_BOOKS"
        )).thenReturn(Mono.just(details));
        when(coverPersistenceService.updateAfterS3Upload(eq(bookId), any(CoverPersistenceService.S3UploadResult.class)))
            .thenReturn(new CoverPersistenceService.PersistenceResult(false, details.getUrlOrPath(), 600, 900, true));

        coordinator.triggerUpload(event);

        verify(coverPersistenceService, timeout(1000).times(1)).updateAfterS3Upload(eq(bookId), any());
        assertCounterEventuallyEquals("book.cover.s3.upload.success", 0.0d);
        assertCounterEventuallyEquals("book.cover.s3.upload.failure", 1.0d);
    }

    @Test
    void should_CountSingleFailureAndSkipPersistence_When_UploadDetailsMissingStorageKey() {
        UUID bookId = UUID.randomUUID();
        String bookIdString = bookId.toString();
        ImageDetails details = new ImageDetails(
            "https://cdn.example.com/covers/legacy-code.webp",
            "GOOGLE_BOOKS",
            bookIdString,
            CoverImageSource.GOOGLE_BOOKS,
            ImageResolutionPreference.UNKNOWN,
            600,
            900
        );

        BookUpsertEvent event = new BookUpsertEvent(
            bookIdString,
            "legacy-code",
            "Legacy Code",
            true,
            "GOOGLE_BOOKS",
            Map.of("thumbnail", "https://covers.example.com/thumb.jpg"),
            null,
            "GOOGLE_BOOKS"
        );

        when(s3BookCoverService.uploadCoverToS3Async(
            "https://covers.example.com/thumb.jpg",
            bookIdString,
            "GOOGLE_BOOKS"
        )).thenReturn(Mono.just(details));

        coordinator.triggerUpload(event);

        verify(s3BookCoverService, timeout(1000).times(1)).uploadCoverToS3Async(
            "https://covers.example.com/thumb.jpg",
            bookIdString,
            "GOOGLE_BOOKS"
        );
        verify(coverPersistenceService, never()).updateAfterS3Upload(any(), any());
        assertCounterEventuallyEquals("book.cover.s3.upload.success", 0.0d);
        assertCounterEventuallyEquals("book.cover.s3.upload.failure", 1.0d);
    }

    private void assertCounterEventuallyEquals(String metricName, double expectedValue) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (Double.compare(counterValue(metricName), expectedValue) == 0) {
                return;
            }
            Thread.onSpinWait();
        }
        assertThat(counterValue(metricName)).isEqualTo(expectedValue);
    }

    private double counterValue(String metricName) {
        return meterRegistry.get(metricName).counter().count();
    }
}
