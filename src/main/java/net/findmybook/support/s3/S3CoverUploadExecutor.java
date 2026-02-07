package net.findmybook.support.s3;

import net.findmybook.exception.S3UploadException;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.util.cover.S3KeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Executes S3 cover upload flows including existing-object checks and canonical key migration.
 */
@Component
public class S3CoverUploadExecutor {

    private static final Logger logger = LoggerFactory.getLogger(S3CoverUploadExecutor.class);

    private final S3Client s3Client;
    private final S3CoverObjectLookupSupport s3CoverObjectLookupSupport;
    private final S3CoverUrlSupport s3CoverUrlSupport;

    public S3CoverUploadExecutor(@Nullable S3Client s3Client,
                                 S3CoverObjectLookupSupport s3CoverObjectLookupSupport,
                                 S3CoverUrlSupport s3CoverUrlSupport) {
        this.s3Client = s3Client;
        this.s3CoverObjectLookupSupport = s3CoverObjectLookupSupport;
        this.s3CoverUrlSupport = s3CoverUrlSupport;
    }

    /**
     * Uploads to canonical key unless an existing object already matches the processed payload.
     */
    public Mono<ImageDetails> uploadOrReuseExistingObject(CoverUploadPayload payload) {
        if (s3Client == null) {
            return Mono.error(new S3UploadException(
                "S3 uploads are unavailable because the S3 client is not configured",
                payload.bookId(),
                null,
                false,
                null
            ));
        }
        String canonicalKey = S3KeyGenerator.generateCoverKeyFromRawSource(
            payload.bookId(), payload.fileExtension(), payload.source());
        return s3CoverObjectLookupSupport.locateExistingKeyAsync(
                payload.bookId(), payload.fileExtension(), payload.source())
            .flatMap(existingKey -> handleExistingObject(existingKey, canonicalKey, payload))
            .switchIfEmpty(uploadToS3Internal(canonicalKey, payload));
    }

    private Mono<ImageDetails> handleExistingObject(String existingKey,
                                                    String canonicalKey,
                                                    CoverUploadPayload payload) {
        return Mono.<software.amazon.awssdk.services.s3.model.HeadObjectResponse>fromCallable(
                () -> s3Client.headObject(
                    HeadObjectRequest.builder()
                        .bucket(s3CoverObjectLookupSupport.bucketName())
                        .key(existingKey)
                        .build()
                )
            )
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(headResponse -> {
                if (existingKey.equals(canonicalKey)
                    && headResponse.contentLength() == payload.processedBytes().length) {
                    logger.info("Processed cover for book {} already exists in S3 with same size, skipping upload. Key: {}",
                        payload.bookId(), existingKey);
                    s3CoverObjectLookupSupport.markObjectExists(existingKey);
                    return Mono.just(s3CoverUrlSupport.buildImageDetailsFromKey(
                        existingKey, payload.processedImage()));
                }

                if (!existingKey.equals(canonicalKey)) {
                    logger.info("Book {} has legacy S3 key {}. Uploading canonical key {} for future lookups.",
                        payload.bookId(), existingKey, canonicalKey);
                }

                return uploadToS3Internal(canonicalKey, payload);
            })
            .onErrorResume(NoSuchKeyException.class,
                exception -> uploadToS3Internal(canonicalKey, payload))
            .onErrorResume(S3Exception.class, s3Exception -> {
                if (s3Exception.statusCode() == S3CoverObjectLookupSupport.HTTP_NOT_FOUND) {
                    return uploadToS3Internal(canonicalKey, payload);
                }
                logger.error(
                    "Failed to inspect existing S3 object for book {} (status {}): {}",
                    payload.bookId(),
                    s3Exception.statusCode(),
                    s3Exception.getMessage(),
                    s3Exception
                );
                return Mono.error(new S3UploadException(
                    "Failed to inspect existing S3 object before upload",
                    payload.bookId(),
                    null,
                    true,
                    s3Exception
                ));
            })
            .onErrorMap(
                SdkClientException.class,
                sdkClientException -> new S3UploadException(
                    "Failed to inspect existing S3 object before upload",
                    payload.bookId(),
                    null,
                    true,
                    sdkClientException
                )
            );
    }

    private Mono<ImageDetails> uploadToS3Internal(String s3Key, CoverUploadPayload payload) {
        return Mono.fromCallable(() -> {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(s3CoverObjectLookupSupport.bucketName())
                .key(s3Key)
                .contentType(payload.mimeType())
                .acl(ObjectCannedACL.PUBLIC_READ)
                .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(payload.processedBytes()));

            s3CoverObjectLookupSupport.markObjectExists(s3Key);
            logger.info("Successfully uploaded processed cover for book {} to S3. Key: {}",
                payload.bookId(), s3Key);
            return s3CoverUrlSupport.buildImageDetailsFromKey(s3Key, payload.processedImage());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
