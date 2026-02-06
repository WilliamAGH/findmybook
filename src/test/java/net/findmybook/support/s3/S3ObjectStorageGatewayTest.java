package net.findmybook.support.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import net.findmybook.service.s3.S3FetchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageGatewayTest {

    @Mock
    private S3Client s3Client;

    @Test
    void should_ReturnCdnUrl_When_UploadSucceedsWithPublicCdn() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());
        S3ObjectStorageGateway gateway = new S3ObjectStorageGateway(
            s3Client,
            "book-finder",
            "https://cdn.example.com/",
            "https://sfo3.digitaloceanspaces.com"
        );

        String uploadedUrl = gateway.uploadFileAsync(
            "/covers/book.jpg",
            new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
            3L,
            "image/jpeg"
        ).join();

        assertThat(uploadedUrl).isEqualTo("https://cdn.example.com/covers/book.jpg");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void should_ReturnServerUrl_When_PublicCdnIsMissing() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());
        S3ObjectStorageGateway gateway = new S3ObjectStorageGateway(
            s3Client,
            "book-finder",
            "",
            "https://sfo3.digitaloceanspaces.com/"
        );

        String uploadedUrl = gateway.uploadFileAsync(
            "covers/book.jpg",
            new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
            3L,
            "image/jpeg"
        ).join();

        assertThat(uploadedUrl).isEqualTo("https://sfo3.digitaloceanspaces.com/book-finder/covers/book.jpg");
    }

    @Test
    void should_ReturnDisabledStatus_When_FetchRunsWithoutConfiguredClient() {
        S3ObjectStorageGateway gateway = new S3ObjectStorageGateway(
            null,
            "book-finder",
            "https://cdn.example.com",
            "https://sfo3.digitaloceanspaces.com"
        );

        S3FetchResult<String> result = gateway.fetchUtf8ObjectAsync("covers/book.jpg").join();

        assertThat(result.isDisabled()).isTrue();
    }

    @Test
    void should_ReturnNotFoundStatus_When_FetchKeyDoesNotExist() {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
            .thenThrow(NoSuchKeyException.builder().message("missing").build());
        S3ObjectStorageGateway gateway = new S3ObjectStorageGateway(
            s3Client,
            "book-finder",
            "https://cdn.example.com",
            "https://sfo3.digitaloceanspaces.com"
        );

        S3FetchResult<String> result = gateway.fetchUtf8ObjectAsync("covers/missing.jpg").join();

        assertThat(result.isNotFound()).isTrue();
    }

    @Test
    void should_ReturnSuccessStatus_When_FetchReturnsUtf8Payload() {
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes.fromByteArray(
            GetObjectResponse.builder().build(),
            "payload".getBytes(StandardCharsets.UTF_8)
        );
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
        S3ObjectStorageGateway gateway = new S3ObjectStorageGateway(
            s3Client,
            "book-finder",
            "https://cdn.example.com",
            "https://sfo3.digitaloceanspaces.com"
        );

        S3FetchResult<String> result = gateway.fetchUtf8ObjectAsync("covers/book.jpg").join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).contains("payload");
    }
}
