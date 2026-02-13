package net.findmybook.application.cover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.findmybook.application.cover.BrowserCoverIngestUseCase.BrowserCoverIngestResult;
import net.findmybook.application.cover.BrowserCoverIngestUseCase.CoverImageRejectedException;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.model.image.CoverRejectionReason;
import net.findmybook.model.image.ProcessedImage;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.service.image.CoverUrlSafetyValidator;
import net.findmybook.service.image.ImageProcessingService;
import net.findmybook.service.image.S3BookCoverService;
import net.findmybook.util.cover.CoverUrlResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class BrowserCoverIngestUseCaseTest {

    @Mock
    private BookCoverResolutionService bookCoverResolutionService;
    @Mock
    private S3BookCoverService s3BookCoverService;
    @Mock
    private ImageProcessingService imageProcessingService;
    @Mock
    private CoverPersistenceService coverPersistenceService;
    @Mock
    private CoverUrlSafetyValidator coverUrlSafetyValidator;

    @InjectMocks
    private BrowserCoverIngestUseCase useCase;

    private static final String IDENTIFIER = "clean-code";
    private static final String SOURCE_URL = "https://books.google.com/covers/clean-code.jpg";
    private static final String SOURCE = "GOOGLE_BOOKS";
    private static final byte[] IMAGE_BYTES = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};

    @Test
    void should_ThrowNoSuchElement_When_BookNotFound() {
        when(bookCoverResolutionService.resolveCover(IDENTIFIER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.ingest(IDENTIFIER, mockImage(), SOURCE_URL, SOURCE))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining(IDENTIFIER);
    }

    @Test
    void should_ThrowIllegalArgument_When_ImagePayloadIsNull() {
        stubResolvedPayload();

        assertThatThrownBy(() -> useCase.ingest(IDENTIFIER, null, SOURCE_URL, SOURCE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
    }

    @Test
    void should_ThrowIllegalArgument_When_SourceUrlIsNotAllowlisted() {
        stubResolvedPayload();
        when(coverUrlSafetyValidator.isAllowedImageUrl(anyString())).thenReturn(false);

        assertThatThrownBy(() -> useCase.ingest(IDENTIFIER, mockImage(), SOURCE_URL, SOURCE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowlisted");
    }

    @Test
    void should_ThrowIllegalArgument_When_SourceUrlDoesNotMatchKnownCandidates() {
        String nonMatchingUrl = "https://books.google.com/covers/other-book.jpg";
        stubResolvedPayload();
        when(coverUrlSafetyValidator.isAllowedImageUrl(anyString())).thenReturn(true);

        assertThatThrownBy(() -> useCase.ingest(IDENTIFIER, mockImage(), nonMatchingUrl, SOURCE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cover URLs");
    }

    @Test
    void should_ThrowCoverImageRejected_When_ImageProcessingFails() {
        stubResolvedPayload();
        stubUrlValidation();
        when(imageProcessingService.processImageForS3(any(byte[].class), anyString()))
            .thenReturn(CompletableFuture.completedFuture(
                ProcessedImage.rejected(CoverRejectionReason.PLACEHOLDER_TOO_SMALL)));

        assertThatThrownBy(() -> useCase.ingest(IDENTIFIER, mockImage(), SOURCE_URL, SOURCE))
            .isInstanceOf(CoverImageRejectedException.class);
    }

    @Test
    void should_ThrowIllegalState_When_S3UploadReturnsEmptyResult() {
        stubResolvedPayload();
        stubUrlValidation();
        stubSuccessfulProcessing();
        when(s3BookCoverService.uploadProcessedCoverToS3Async(any()))
            .thenReturn(Mono.empty());

        assertThatThrownBy(() -> useCase.ingest(IDENTIFIER, mockImage(), SOURCE_URL, SOURCE))
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .rootCause().hasMessageContaining("without an image payload");
    }

    @Test
    void should_ThrowIllegalState_When_PersistenceReportsUnsuccessful() {
        stubResolvedPayload();
        stubUrlValidation();
        stubSuccessfulProcessing();
        stubSuccessfulUpload();
        when(coverPersistenceService.updateAfterS3Upload(any(UUID.class), any()))
            .thenReturn(new CoverPersistenceService.PersistenceResult(false, null, null, null, false));

        assertThatThrownBy(() -> useCase.ingest(IDENTIFIER, mockImage(), SOURCE_URL, SOURCE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("persist");
    }

    @Test
    void should_ReturnIngestResult_When_FullPipelineSucceeds() {
        UUID bookUuid = stubResolvedPayload();
        stubUrlValidation();
        stubSuccessfulProcessing();
        stubSuccessfulUpload();
        when(coverPersistenceService.updateAfterS3Upload(eq(bookUuid), any()))
            .thenReturn(new CoverPersistenceService.PersistenceResult(
                true, "https://cdn.example.com/covers/clean-code.webp", 600, 900, true));

        BrowserCoverIngestResult result = useCase.ingest(IDENTIFIER, mockImage(), SOURCE_URL, SOURCE);

        assertThat(result.bookId()).isEqualTo(bookUuid.toString());
        assertThat(result.storedCoverUrl()).isEqualTo("https://cdn.example.com/covers/clean-code.webp");
        assertThat(result.storageKey()).isEqualTo("covers/clean-code.webp");
        assertThat(result.width()).isEqualTo(600);
        assertThat(result.height()).isEqualTo(900);
        assertThat(result.highResolution()).isTrue();

        verify(coverPersistenceService).updateAfterS3Upload(eq(bookUuid), any());
    }

    // --- helpers ---

    private UUID stubResolvedPayload() {
        UUID bookUuid = UUID.randomUUID();
        String bookId = bookUuid.toString();
        CoverUrlResolver.ResolvedCover cover = new CoverUrlResolver.ResolvedCover(
            SOURCE_URL, "covers/clean-code.webp", false, 600, 900, true);
        BookCoverResolutionService.ResolvedCoverPayload payload =
            new BookCoverResolutionService.ResolvedCoverPayload(bookId, cover, null, SOURCE);

        when(bookCoverResolutionService.resolveCover(IDENTIFIER)).thenReturn(Optional.of(payload));
        when(bookCoverResolutionService.resolveBookUuid(IDENTIFIER, bookId)).thenReturn(Optional.of(bookUuid));
        return bookUuid;
    }

    private void stubUrlValidation() {
        when(coverUrlSafetyValidator.isAllowedImageUrl(anyString())).thenReturn(true);
    }

    private void stubSuccessfulProcessing() {
        ProcessedImage processed = ProcessedImage.success(IMAGE_BYTES, "webp", "image/webp", 600, 900, false);
        when(imageProcessingService.processImageForS3(any(byte[].class), anyString()))
            .thenReturn(CompletableFuture.completedFuture(processed));
    }

    private void stubSuccessfulUpload() {
        ImageDetails details = new ImageDetails(
            "https://cdn.example.com/covers/clean-code.webp",
            SOURCE,
            "clean-code",
            CoverImageSource.GOOGLE_BOOKS,
            ImageResolutionPreference.UNKNOWN,
            600, 900);
        details.setStorageKey("covers/clean-code.webp");
        when(s3BookCoverService.uploadProcessedCoverToS3Async(any())).thenReturn(Mono.just(details));
    }

    private MockMultipartFile mockImage() {
        return new MockMultipartFile("image", "cover.jpg", "image/jpeg", IMAGE_BYTES);
    }
}
