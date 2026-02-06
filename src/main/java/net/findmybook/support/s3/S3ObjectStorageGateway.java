package net.findmybook.support.s3;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.findmybook.service.s3.S3FetchResult;
import net.findmybook.util.CompressionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Infrastructure adapter for generic S3 object storage operations.
 *
 * <p>This gateway centralizes all direct AWS SDK usage so higher layers can remain focused on
 * workflow orchestration instead of SDK request/response details.</p>
 */
public final class S3ObjectStorageGateway {

    private static final Logger logger = LoggerFactory.getLogger(S3ObjectStorageGateway.class);

    private final S3Client s3Client;
    private final String bucketName;
    private final String publicCdnUrl;
    private final String serverUrl;

    public S3ObjectStorageGateway(@Nullable S3Client s3Client,
                                  String bucketName,
                                  String publicCdnUrl,
                                  String serverUrl) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.publicCdnUrl = publicCdnUrl;
        this.serverUrl = serverUrl;
    }

    /**
     * Validates startup configuration for this adapter.
     */
    public void validateConfiguration() {
        if (s3Client == null) {
            logger.warn("S3 object storage gateway initialized without an S3 client. S3 operations are disabled.");
            return;
        }
        if (!hasText(bucketName)) {
            throw new IllegalStateException("S3 bucket name must be configured when S3 object storage is active.");
        }
    }

    /**
     * Uploads bytes from the input stream and returns a public object URL.
     */
    public CompletableFuture<String> uploadFileAsync(String keyName,
                                                     InputStream inputStream,
                                                     long contentLength,
                                                     String contentType) {
        return Mono.fromCallable(() -> {
                S3Client client = requireClient("upload", keyName);
                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .contentType(contentType)
                    .build();

                client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, contentLength));
                logger.info("Successfully uploaded {} to S3 bucket {}", keyName, bucketName);
                return resolvePublicUrl(keyName);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .toFuture();
    }

    /**
     * Fetches a UTF-8 object payload and transparently handles optional GZIP encoding.
     */
    public CompletableFuture<S3FetchResult<String>> fetchUtf8ObjectAsync(String keyName) {
        if (s3Client == null) {
            logger.warn("S3 client is null. Cannot fetch UTF-8 object for key {}.", keyName);
            return CompletableFuture.completedFuture(S3FetchResult.disabled());
        }

        return Mono.<S3FetchResult<String>>fromCallable(() -> {
                GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .build();
                ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
                byte[] payload = objectBytes.asByteArray();
                String contentEncoding = objectBytes.response().contentEncoding();
                Optional<String> decodedPayload = decodeUtf8Payload(keyName, payload, contentEncoding);
                if (decodedPayload.isEmpty()) {
                    return S3FetchResult.serviceError("Failed to decode content for key " + keyName);
                }
                logger.info("Successfully fetched UTF-8 payload from S3 key {}", keyName);
                return S3FetchResult.success(decodedPayload.get());
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(NoSuchKeyException.class, exception -> {
                if (logger.isTraceEnabled()) {
                    logger.trace("S3 key {} not found: {}", keyName, exception.getMessage());
                }
                return Mono.just(S3FetchResult.notFound());
            })
            .onErrorResume(S3Exception.class, exception -> {
                logger.error("S3 error fetching key {}: {}", keyName, resolveS3ErrorMessage(exception), exception);
                return Mono.just(S3FetchResult.serviceError(resolveS3ErrorMessage(exception)));
            })
            .onErrorResume(SdkClientException.class, exception -> {
                logger.error("S3 client error fetching key {}: {}", keyName, exception.getMessage(), exception);
                return Mono.just(S3FetchResult.serviceError(exception.getMessage()));
            })
            .onErrorResume(IllegalArgumentException.class, exception -> {
                logger.error("Invalid fetch request for key {}: {}", keyName, exception.getMessage(), exception);
                return Mono.just(S3FetchResult.serviceError(exception.getMessage()));
            })
            .toFuture();
    }

    /**
     * Lists objects for the configured bucket with optional prefix filtering.
     */
    public List<S3Object> listObjects(String prefix) {
        if (s3Client == null) {
            throw new IllegalStateException("S3 client is not configured. Cannot list objects.");
        }

        logger.info("Listing objects in bucket {} with prefix '{}'", bucketName, prefix);
        List<S3Object> allObjects = new ArrayList<>();
        String continuationToken = null;

        try {
            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .continuationToken(continuationToken);
                if (hasText(prefix)) {
                    requestBuilder.prefix(prefix);
                }

                var response = s3Client.listObjectsV2(requestBuilder.build());
                allObjects.addAll(response.contents());
                continuationToken = response.nextContinuationToken();
                logger.debug("Fetched {} objects from current page.", response.contents().size());
            } while (continuationToken != null);
            logger.info("Finished listing S3 objects. Total found: {}", allObjects.size());
        } catch (S3Exception exception) {
            throw new IllegalStateException(
                "S3 error listing objects in bucket " + bucketName + ": " + resolveS3ErrorMessage(exception),
                exception
            );
        } catch (SdkClientException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Unexpected error listing objects in bucket " + bucketName + ": " + exception.getMessage(),
                exception
            );
        }
        return allObjects;
    }

    /**
     * Downloads an object as bytes.
     */
    public @Nullable byte[] downloadFileAsBytes(String key) {
        if (s3Client == null) {
            throw new IllegalStateException("S3 client is not configured. Cannot download key " + key);
        }
        try {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                request -> request.bucket(bucketName).key(key)
            );
            logger.info("Successfully downloaded {} from bucket {}", key, bucketName);
            return objectBytes.asByteArray();
        } catch (NoSuchKeyException exception) {
            logger.warn("S3 key not found: bucket={}, key={}", bucketName, key);
            return null;
        } catch (S3Exception exception) {
            throw new IllegalStateException(
                "S3 error downloading key " + key + " from bucket " + bucketName + ": " + resolveS3ErrorMessage(exception),
                exception
            );
        } catch (SdkClientException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Unexpected error downloading key " + key + " from bucket " + bucketName + ": " + exception.getMessage(),
                exception
            );
        }
    }

    /**
     * Copies one object key to another inside the same bucket.
     */
    public boolean copyObject(String sourceKey, String destinationKey) {
        if (s3Client == null) {
            throw new IllegalStateException(
                "S3 client is not configured. Cannot copy object from " + sourceKey + " to " + destinationKey
            );
        }

        try {
            CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(sourceKey)
                .destinationBucket(bucketName)
                .destinationKey(destinationKey)
                .build();
            s3Client.copyObject(copyRequest);
            logger.info("Copied object from {} to {}", sourceKey, destinationKey);
            return true;
        } catch (S3Exception exception) {
            throw new IllegalStateException(
                "S3 error copying object from " + sourceKey + " to " + destinationKey + ": " + resolveS3ErrorMessage(exception),
                exception
            );
        } catch (SdkClientException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Unexpected error copying object from " + sourceKey + " to " + destinationKey + ": " + exception.getMessage(),
                exception
            );
        }
    }

    /**
     * Deletes an object key from the bucket.
     */
    public boolean deleteObject(String key) {
        if (s3Client == null) {
            throw new IllegalStateException("S3 client is not configured. Cannot delete object " + key);
        }

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
            s3Client.deleteObject(deleteRequest);
            logger.info("Deleted object {}", key);
            return true;
        } catch (S3Exception exception) {
            throw new IllegalStateException(
                "S3 error deleting object " + key + ": " + resolveS3ErrorMessage(exception),
                exception
            );
        } catch (SdkClientException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Unexpected error deleting object " + key + ": " + exception.getMessage(),
                exception
            );
        }
    }

    /**
     * Returns the configured bucket name.
     */
    public String bucketName() {
        return bucketName;
    }

    private S3Client requireClient(String operation, String keyName) {
        if (s3Client == null) {
            throw new IllegalStateException("S3 client is not configured for " + operation + " operation (key: " + keyName + ")");
        }
        return s3Client;
    }

    private Optional<String> decodeUtf8Payload(String keyName, byte[] payload, String contentEncoding) {
        try {
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                return Optional.of(CompressionUtils.decodeUtf8ExpectingGzip(payload));
            }
            return Optional.of(CompressionUtils.decodeUtf8WithOptionalGzip(payload));
        } catch (IOException exception) {
            logger.error("Failed to decode GZIP payload for key {}: {}", keyName, exception.getMessage(), exception);
            return Optional.empty();
        }
    }

    private String resolvePublicUrl(String keyName) {
        String normalizedKey = normalizePathSegment(keyName);

        if (hasText(publicCdnUrl)) {
            return joinPath(normalizeBaseUrl(publicCdnUrl), normalizedKey);
        }
        if (hasText(serverUrl)) {
            return joinPath(joinPath(normalizeBaseUrl(serverUrl), bucketName), normalizedKey);
        }
        return "https://" + bucketName + ".s3.amazonaws.com/" + normalizedKey;
    }

    private static String normalizeBaseUrl(String value) {
        String trimmed = Objects.requireNonNullElse(value, "").trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizePathSegment(String value) {
        String trimmed = Objects.requireNonNullElse(value, "").trim();
        if (trimmed.startsWith("/")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    private static String joinPath(String base, String suffix) {
        if (!hasText(base)) {
            return suffix;
        }
        if (base.endsWith("/")) {
            return base + suffix;
        }
        return base + "/" + suffix;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String resolveS3ErrorMessage(S3Exception exception) {
        if (exception.awsErrorDetails() != null && exception.awsErrorDetails().errorMessage() != null) {
            return exception.awsErrorDetails().errorMessage();
        }
        return exception.getMessage();
    }
}
