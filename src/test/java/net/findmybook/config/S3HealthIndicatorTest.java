package net.findmybook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3HealthIndicatorTest {

    @Test
    void shouldReportUpWhenDisabledByConfig() {
        S3HealthIndicator indicator = new S3HealthIndicator(null, "covers", false);

        StepVerifier.create(indicator.health())
            .assertNext(health -> assertEquals(Status.UP, health.getStatus()))
            .verifyComplete();
    }

    @Test
    void shouldReportDownWhenClientMissing() {
        S3HealthIndicator indicator = new S3HealthIndicator(null, "covers", true);

        StepVerifier.create(indicator.health())
            .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
            .verifyComplete();
    }

    @Test
    void shouldReportDownWhenBucketMissing() {
        S3Client mockClient = mock(S3Client.class);
        S3HealthIndicator indicator = new S3HealthIndicator(mockClient, "", true);

        StepVerifier.create(indicator.health())
            .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
            .verifyComplete();
    }

    @Test
    void shouldReportDownWhenS3ThrowsServiceError() {
        S3Client mockClient = mock(S3Client.class);
        S3Exception s3Exception = (S3Exception) S3Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("NoSuchBucket")
                .errorMessage("Bucket not found")
                .build())
            .message("Bucket not found")
            .build();
        when(mockClient.headBucket(any(HeadBucketRequest.class))).thenThrow(s3Exception);

        S3HealthIndicator indicator = new S3HealthIndicator(mockClient, "missing-bucket", true);

        StepVerifier.create(indicator.health())
            .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
            .verifyComplete();
    }

    @Test
    void shouldReportDownWhenS3ThrowsUnexpectedError() {
        S3Client mockClient = mock(S3Client.class);
        when(mockClient.headBucket(any(HeadBucketRequest.class)))
            .thenThrow(new RuntimeException("boom"));

        S3HealthIndicator indicator = new S3HealthIndicator(mockClient, "covers", true);

        StepVerifier.create(indicator.health())
            .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
            .verifyComplete();
    }

    @Test
    void shouldReportUpWhenS3IsAvailable() {
        S3Client mockClient = mock(S3Client.class);
        when(mockClient.headBucket(any(HeadBucketRequest.class)))
            .thenReturn(HeadBucketResponse.builder().build());

        S3HealthIndicator indicator = new S3HealthIndicator(mockClient, "covers", true);

        StepVerifier.create(indicator.health())
            .assertNext(health -> assertEquals(Status.UP, health.getStatus()))
            .verifyComplete();
    }
}
