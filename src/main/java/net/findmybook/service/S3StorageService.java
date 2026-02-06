package net.findmybook.service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.findmybook.config.S3EnvironmentCondition;
import net.findmybook.service.s3.S3FetchResult;
import net.findmybook.support.s3.S3ObjectStorageGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Application-facing facade for generic S3 object storage operations.
 *
 * <p>This service intentionally stays thin and delegates all AWS SDK behavior to
 * {@link S3ObjectStorageGateway} so callers can remain focused on business workflow.</p>
 */
@Service
@Conditional(S3EnvironmentCondition.class)
public class S3StorageService {

    private final S3ObjectStorageGateway s3ObjectStorageGateway;

    /**
     * Constructs a storage facade backed by the central S3 gateway.
     */
    public S3StorageService(S3Client s3Client,
                            @Value("${s3.bucket-name:${S3_BUCKET}}") String bucketName,
                            @Value("${s3.cdn-url:${S3_CDN_URL:#{null}}}") String publicCdnUrl,
                            @Value("${s3.server-url:${S3_SERVER_URL:#{null}}}") String serverUrl) {
        this.s3ObjectStorageGateway = new S3ObjectStorageGateway(s3Client, bucketName, publicCdnUrl, serverUrl);
    }

    /**
     * Validates startup configuration for the backing gateway.
     */
    @PostConstruct
    void validateConfiguration() {
        s3ObjectStorageGateway.validateConfiguration();
    }

    /**
     * Uploads a file asynchronously and returns the public URL.
     */
    public CompletableFuture<String> uploadFileAsync(String keyName,
                                                     InputStream inputStream,
                                                     long contentLength,
                                                     String contentType) {
        return s3ObjectStorageGateway.uploadFileAsync(keyName, inputStream, contentLength, contentType);
    }

    /**
     * Fetches a UTF-8 payload for a storage key.
     */
    public CompletableFuture<S3FetchResult<String>> fetchUtf8ObjectAsync(String keyName) {
        return s3ObjectStorageGateway.fetchUtf8ObjectAsync(keyName);
    }

    /**
     * Returns the configured S3 bucket name.
     */
    public String getBucketName() {
        return s3ObjectStorageGateway.bucketName();
    }

    /**
     * Lists objects in S3 for the provided prefix.
     */
    public List<StoredObjectMetadata> listObjects(String prefix) {
        return s3ObjectStorageGateway.listObjects(prefix).stream()
            .map(s3Object -> new StoredObjectMetadata(s3Object.key(), s3Object.size()))
            .toList();
    }

    /**
     * Downloads an object as raw bytes.
     */
    public @Nullable byte[] downloadFileAsBytes(String key) {
        return s3ObjectStorageGateway.downloadFileAsBytes(key);
    }

    /**
     * Copies one key to another within the configured bucket.
     */
    public boolean copyObject(String sourceKey, String destinationKey) {
        return s3ObjectStorageGateway.copyObject(sourceKey, destinationKey);
    }

    /**
     * Deletes an object key from S3.
     */
    public boolean deleteObject(String key) {
        return s3ObjectStorageGateway.deleteObject(key);
    }

    /**
     * Value object representing object metadata needed by application services.
     */
    public record StoredObjectMetadata(String key, @Nullable Long sizeBytes) {
    }
}
