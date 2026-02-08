package net.findmybook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Duration;

@Component("s3HealthIndicator")
public class S3HealthIndicator implements ReactiveHealthIndicator {

    // S3Client is thread-safe and immutable per AWS SDK v2; storing reference is safe
    private final S3Client s3Client;
    private final String bucketName;
    private final boolean s3Enabled;
    private static final Duration S3_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Constructs S3HealthIndicator with optional S3Client.
     *
     * @param s3Client the S3 client, or null if S3 is disabled
     * @param bucketName the S3 bucket name
     * @param s3Enabled whether S3 is enabled
     */
    public S3HealthIndicator(
            S3Client s3Client,
            @Value("${s3.bucket-name:}") String bucketName,
            @Value("${s3.enabled:false}") boolean s3Enabled) {
        this.s3Client = s3Client; // reference only; not exposing mutable rep
        this.bucketName = bucketName;
        this.s3Enabled = s3Enabled;
    }

    @Override
    public Mono<Health> health() {
        if (!s3Enabled) {
            return Mono.just(Health.up().withDetail("s3_status", "disabled_by_config").build());
        }

        if (s3Client == null) {
            return Mono.just(Health.down()
                    .withDetail("s3_status", "misconfigured_or_disabled")
                    .withDetail("detail", "S3Client bean is not available, check S3 configuration and credentials.")
                    .build());
        }

        if (bucketName == null || bucketName.isEmpty()) {
            return Mono.just(Health.down()
                    .withDetail("s3_status", "misconfigured")
                    .withDetail("detail", "S3 bucket name is not configured.")
                    .build());
        }

        HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
                .bucket(bucketName)
                .build();

        return Mono.fromCallable(() -> {
                    s3Client.headBucket(headBucketRequest);
                    return Health.up()
                            .withDetail("s3_status", "available")
                            .withDetail("bucket", bucketName)
                            .build();
                })
                .timeout(S3_TIMEOUT)
                .subscribeOn(Schedulers.boundedElastic()) // Perform S3 call on a separate thread
                .onErrorResume(S3Exception.class, ex -> {
                    var details = ex.awsErrorDetails();
                    String error = (details != null)
                            ? details.errorCode() + ": " + details.errorMessage()
                            : ex.getMessage();
                    return Mono.just(Health.down()
                            .withDetail("s3_status", "s3_error")
                            .withDetail("bucket", bucketName)
                            .withDetail("error", error)
                            .build());
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> Mono.just(Health.down()
                        .withDetail("s3_status", "timeout")
                        .withDetail("bucket", bucketName)
                        .withDetail("message", ex.getMessage())
                        .build()))
                .onErrorResume(SdkClientException.class, ex -> Mono.just(Health.down()
                        .withDetail("s3_status", "sdk_client_error")
                        .withDetail("bucket", bucketName)
                        .withDetail("error", ex.getClass().getName())
                        .withDetail("message", ex.getMessage())
                        .build()))
                .onErrorResume(Throwable.class, ex -> Mono.just(Health.down()
                        .withDetail("s3_status", "unexpected_error")
                        .withDetail("bucket", bucketName)
                        .withDetail("error", ex.getClass().getName())
                        .withDetail("message", ex.getMessage())
                        .build()));
    }
}
