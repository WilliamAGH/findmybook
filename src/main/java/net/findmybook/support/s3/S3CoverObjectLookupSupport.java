package net.findmybook.support.s3;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.findmybook.service.image.S3CoverKeyResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Centralizes S3 cover object existence checks with bounded in-memory caching.
 */
@Component
public class S3CoverObjectLookupSupport {

    private final S3Client s3Client;
    private final S3CoverKeyResolver s3CoverKeyResolver;
    private final String s3BucketName;
    private final Cache<String, Boolean> objectExistsCache;

    public S3CoverObjectLookupSupport(@Nullable S3Client s3Client,
                                      S3CoverKeyResolver s3CoverKeyResolver,
                                      @Value("${s3.bucket-name}") String s3BucketName) {
        this.s3Client = s3Client;
        this.s3CoverKeyResolver = s3CoverKeyResolver;
        this.s3BucketName = s3BucketName;
        this.objectExistsCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();
    }

    /**
     * Resolves the first existing key from all source-compatible candidate keys.
     */
    public Mono<String> locateExistingKeyAsync(String bookId, String fileExtension, String rawSource) {
        ensureClientConfigured();
        return Flux.fromIterable(s3CoverKeyResolver.buildCandidateKeys(bookId, fileExtension, rawSource))
            .concatMap(key -> headObjectExistsAsync(key)
                .filter(Boolean::booleanValue)
                .map(exists -> key))
            .next();
    }

    /**
     * Resolves the first existing key from all source-compatible candidate keys.
     */
    public Optional<String> locateExistingKeySync(String bookId, String fileExtension, String rawSource) {
        ensureClientConfigured();
        for (String key : s3CoverKeyResolver.buildCandidateKeys(bookId, fileExtension, rawSource)) {
            if (headObjectExistsSync(key)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks all configured source labels plus unknown and returns whether any key exists.
     */
    public Mono<Boolean> coverExistsAsyncAcrossSources(String bookId, String fileExtension, List<String> sourceLabels) {
        ensureClientConfigured();
        return Flux.concat(
                Flux.fromIterable(sourceLabels)
                    .concatMap(label -> locateExistingKeyAsync(bookId, fileExtension, label).hasElement()),
                locateExistingKeyAsync(bookId, fileExtension, "unknown").hasElement()
            )
            .filter(Boolean::booleanValue)
            .next()
            .defaultIfEmpty(false);
    }

    /**
     * Checks if an object exists in S3 and returns asynchronously.
     */
    public Mono<Boolean> headObjectExistsAsync(String s3Key) {
        Boolean cached = objectExistsCache.getIfPresent(s3Key);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(() -> headObjectExistsSync(s3Key))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Checks if an object exists in S3 synchronously.
     */
    public boolean headObjectExistsSync(String s3Key) {
        Boolean cached = objectExistsCache.getIfPresent(s3Key);
        if (cached != null) {
            return cached;
        }

        ensureClientConfigured();
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(s3BucketName).key(s3Key).build());
            objectExistsCache.put(s3Key, true);
            return true;
        } catch (NoSuchKeyException exception) {
            objectExistsCache.put(s3Key, false);
            return false;
        } catch (S3Exception s3Exception) {
            if (s3Exception.statusCode() == 404) {
                objectExistsCache.put(s3Key, false);
                return false;
            }
            throw new IllegalStateException(
                "Failed to check S3 object existence for key " + s3Key + " (status " + s3Exception.statusCode() + ")",
                s3Exception
            );
        } catch (SdkClientException sdkClientException) {
            throw new IllegalStateException("Failed to check S3 object existence for key " + s3Key, sdkClientException);
        }
    }

    /**
     * Marks an object key as present to avoid redundant immediate head checks.
     */
    public void markObjectExists(String s3Key) {
        objectExistsCache.put(s3Key, true);
    }

    /**
     * Returns the configured bucket name for S3 cover storage.
     */
    public String bucketName() {
        return s3BucketName;
    }

    private void ensureClientConfigured() {
        if (s3Client == null) {
            throw new IllegalStateException("S3 client is not configured");
        }
    }
}
