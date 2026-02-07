package net.findmybook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import io.github.resilience4j.ratelimiter.RateLimiter;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void s3ClientCreation_shouldAllowMissingEndpointOverride() {
        S3Config config = new S3Config("test-access-key", "test-secret", "", "us-east-1");

        S3Client client = config.s3Client();

        assertNotNull(client);
        client.close();
    }

    @Test
    void searchHealthIndicator_shouldIgnoreManagementNamespaceEvents() {
        WebClient.Builder sharedBuilder = mock(WebClient.Builder.class);
        SearchPageHealthIndicator indicator = new SearchPageHealthIndicator(sharedBuilder, true);

        WebServerApplicationContext managementContext = mock(WebServerApplicationContext.class);
        when(managementContext.getServerNamespace()).thenReturn("management");
        WebServerInitializedEvent managementEvent = mock(WebServerInitializedEvent.class);
        when(managementEvent.getApplicationContext()).thenReturn(managementContext);
        WebServer managementServer = mock(WebServer.class);
        when(managementEvent.getWebServer()).thenReturn(managementServer);
        when(managementServer.getPort()).thenReturn(8081);

        indicator.onApplicationEvent(managementEvent);

        verify(sharedBuilder, never()).clone();
        StepVerifier.create(indicator.health())
            .assertNext(health -> assertEquals(Status.UNKNOWN, health.getStatus()))
            .verifyComplete();
    }

    @Test
    void searchHealthIndicator_shouldUseClonedBuilderForPrimaryServer() {
        WebClient.Builder sharedBuilder = mock(WebClient.Builder.class);
        WebClient.Builder clonedBuilder = mock(WebClient.Builder.class);
        WebClient webClient = mock(WebClient.class);
        when(sharedBuilder.clone()).thenReturn(clonedBuilder);
        when(clonedBuilder.baseUrl(anyString())).thenReturn(clonedBuilder);
        when(clonedBuilder.build()).thenReturn(webClient);

        SearchPageHealthIndicator indicator = new SearchPageHealthIndicator(sharedBuilder, true);
        WebServerApplicationContext appContext = mock(WebServerApplicationContext.class);
        when(appContext.getServerNamespace()).thenReturn(null);
        WebServerInitializedEvent appEvent = mock(WebServerInitializedEvent.class);
        when(appEvent.getApplicationContext()).thenReturn(appContext);
        WebServer appServer = mock(WebServer.class);
        when(appEvent.getWebServer()).thenReturn(appServer);
        when(appServer.getPort()).thenReturn(8095);

        indicator.onApplicationEvent(appEvent);

        verify(sharedBuilder).clone();
        verify(clonedBuilder).baseUrl("http://localhost:8095");
    }

    @Test
    void appRateLimiter_shouldUsePerMinuteRefreshPeriod() {
        AppRateLimiterConfig config = new AppRateLimiterConfig(10);

        RateLimiter limiter = config.googleBooksRateLimiter();

        assertEquals(Duration.ofMinutes(1), limiter.getRateLimiterConfig().getLimitRefreshPeriod());
    }

    @Test
    void appRateLimiter_openLibraryShouldUseFiveSecondPacing() {
        AppRateLimiterConfig config = new AppRateLimiterConfig(10);

        RateLimiter limiter = config.openLibraryRateLimiter();

        assertEquals(Duration.ofSeconds(5), limiter.getRateLimiterConfig().getLimitRefreshPeriod());
        assertEquals(1, limiter.getRateLimiterConfig().getLimitForPeriod());
    }

    @Test
    void devRateLimiter_shouldUseServiceNameAndPerMinuteRefreshPeriod() {
        DevModeConfig config = new DevModeConfig(1440, 10, 10);

        RateLimiter limiter = config.googleBooksRateLimiter();

        assertEquals("googleBooksServiceRateLimiter", limiter.getName());
        assertEquals(Duration.ofMinutes(1), limiter.getRateLimiterConfig().getLimitRefreshPeriod());
    }
}
