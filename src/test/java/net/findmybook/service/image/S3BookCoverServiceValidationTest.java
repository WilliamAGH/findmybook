package net.findmybook.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import net.findmybook.exception.S3UploadException;
import net.findmybook.support.s3.S3CoverObjectLookupSupport;
import net.findmybook.support.s3.S3CoverStorageGateway;
import net.findmybook.support.s3.S3CoverStorageProperties;
import net.findmybook.support.s3.S3CoverUploadExecutor;
import net.findmybook.support.s3.S3CoverUrlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.s3.S3Client;

class S3BookCoverServiceValidationTest {

    private S3BookCoverService service;
    private ImageProcessingService imageProcessingService;
    private S3Client s3Client;
    private Environment environment;
    private CoverUrlSafetyValidator coverUrlSafetyValidator;
    private S3CoverKeyResolver s3CoverKeyResolver;
    private S3CoverUrlSupport s3CoverUrlSupport;

    @BeforeEach
    void setUp() {
        imageProcessingService = Mockito.mock(ImageProcessingService.class);
        s3Client = Mockito.mock(S3Client.class);
        environment = Mockito.mock(Environment.class);
        coverUrlSafetyValidator = new CoverUrlSafetyValidator();
        s3CoverKeyResolver = new S3CoverKeyResolver();
        s3CoverUrlSupport = buildUrlSupport(
            "https://cdn.example.com",
            "",
            "https://sfo3.digitaloceanspaces.com",
            "test-bucket"
        );
        service = buildService(true, true);
    }

    private S3BookCoverService buildService(boolean s3Enabled, boolean s3WriteEnabled) {
        S3CoverStorageProperties s3CoverStorageProperties = new S3CoverStorageProperties();
        s3CoverStorageProperties.setEnabled(s3Enabled);
        s3CoverStorageProperties.setWriteEnabled(s3WriteEnabled);

        S3CoverObjectLookupSupport s3CoverObjectLookupSupport =
            new S3CoverObjectLookupSupport(s3Client, s3CoverKeyResolver, "test-bucket");
        S3CoverUploadExecutor s3CoverUploadExecutor = new S3CoverUploadExecutor(
            s3Client,
            s3CoverKeyResolver,
            s3CoverObjectLookupSupport,
            s3CoverUrlSupport
        );

        S3CoverStorageGateway s3CoverStorageGateway = new S3CoverStorageGateway(
            s3Client,
            s3CoverStorageProperties,
            environment,
            s3CoverObjectLookupSupport,
            s3CoverUrlSupport,
            s3CoverUploadExecutor
        );

        return new S3BookCoverService(
            WebClient.builder(),
            imageProcessingService,
            coverUrlSafetyValidator,
            s3CoverStorageGateway,
            s3CoverStorageProperties,
            5_242_880L
        );
    }

    private S3CoverUrlSupport buildUrlSupport(String s3CdnUrl,
                                              String s3PublicCdnUrl,
                                              String s3ServerUrl,
                                              String s3BucketName) {
        S3CoverUrlSupport support = new S3CoverUrlSupport(
            s3CdnUrl,
            s3PublicCdnUrl,
            s3ServerUrl,
            s3BucketName
        );
        return support;
    }

    @Test
    void should_FailFast_When_ImageUrlMissingForUpload() {
        StepVerifier.create(service.uploadCoverToS3Async(" ", "book-1", "google-books"))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(S3UploadException.class);
                S3UploadException uploadException = (S3UploadException) error;
                assertThat(uploadException.isRetryable()).isFalse();
                assertThat(uploadException.getMessage()).contains("Image URL is required");
            })
            .verify();
    }

    @Test
    void should_FailFast_When_S3WritesDisabledForUpload() {
        service = buildService(true, false);

        StepVerifier.create(service.uploadCoverToS3Async("https://covers.openlibrary.org/b/id/1-L.jpg", "book-1", "open-library"))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(S3UploadException.class);
                S3UploadException uploadException = (S3UploadException) error;
                assertThat(uploadException.isRetryable()).isFalse();
                assertThat(uploadException.getMessage()).contains("S3 uploads are disabled");
            })
            .verify();
    }

    @Test
    void should_FailFast_When_S3ReadUnavailableForUpload() {
        service = buildService(false, true);

        StepVerifier.create(service.uploadCoverToS3Async("https://covers.openlibrary.org/b/id/1-L.jpg", "book-1", "open-library"))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(S3UploadException.class);
                S3UploadException uploadException = (S3UploadException) error;
                assertThat(uploadException.isRetryable()).isFalse();
                assertThat(uploadException.getMessage()).contains("S3 uploads are unavailable");
            })
            .verify();
    }
}
