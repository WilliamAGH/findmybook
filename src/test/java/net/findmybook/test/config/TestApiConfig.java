/**
 * Test configuration that prevents real API calls during tests
 * - Creates test-specific mock services
 * - Configures caching optimized for test environment
 * - Forces all external API calls to use mock data
 * - Loads test data from classpath resources
 *
 * @author William Callahan
 */
package net.findmybook.test.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.findmybook.service.S3StorageService;
import net.findmybook.service.s3.S3FetchResult;
import net.findmybook.util.S3Paths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Test-specific configuration that prevents real API calls
 * Only active in test environment to ensure predictable test behavior
 */
@Configuration
@Profile("test")
public class TestApiConfig {
    private static final Logger logger = LoggerFactory.getLogger(TestApiConfig.class);
    
    private final ObjectMapper objectMapper;
    
    @Autowired
    public TestApiConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * Creates an S3 storage service that never makes real AWS calls
     * - Returns mock data for specific test book IDs
     * - Returns empty for anything else
     * - Records all attempted S3 operations for verification
     * 
     * @return Mock S3StorageService for testing
     */
    @Bean
    @Primary
    @Qualifier("testS3StorageService")
    public S3StorageService testS3StorageService() {
        return new TestS3StorageService(objectMapper);
    }
    
    /**
     * Test-specific mock implementation of the S3StorageService
     * Prevents real S3 calls during testing
     */
    private static class TestS3StorageService extends S3StorageService {
        private final ObjectMapper objectMapper;
        private final List<String> fetchRequests = new ArrayList<>();
        private final List<String> uploadRequests = new ArrayList<>();
        
        public TestS3StorageService(ObjectMapper objectMapper) {
            super(null, "test-bucket", "https://test-cdn.example.com/", "https://test.example.com/");
            this.objectMapper = objectMapper;
        }
        
        @Override
        public CompletableFuture<S3FetchResult<String>> fetchUtf8ObjectAsync(String keyName) {
            fetchRequests.add(keyName);
            logger.debug("Test S3 fetch for key: {}", keyName);

            if (keyName != null && keyName.startsWith(S3Paths.GOOGLE_BOOK_CACHE_PREFIX) && keyName.endsWith(".json")) {
                String volumeId = keyName.substring(S3Paths.GOOGLE_BOOK_CACHE_PREFIX.length(), keyName.length() - 5);
                try {
                    Resource mockResource = new ClassPathResource("mock-responses/books/" + volumeId + ".json");
                    if (mockResource.exists()) {
                        String json = new String(mockResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        return CompletableFuture.completedFuture(S3FetchResult.success(json));
                    }

                    ObjectNode fakeBook = objectMapper.createObjectNode();
                    fakeBook.put("id", volumeId);
                    fakeBook.put("kind", "books#volume");

                    ObjectNode volumeInfo = objectMapper.createObjectNode();
                    volumeInfo.put("title", "Test Book " + volumeId);

                    ArrayNode authors = objectMapper.createArrayNode();
                    authors.add("Test Author");
                    volumeInfo.set("authors", authors);

                    volumeInfo.put("description", "This is a test book for unit tests");
                    fakeBook.set("volumeInfo", volumeInfo);

                    return CompletableFuture.completedFuture(S3FetchResult.success(fakeBook.toString()));
                } catch (IOException e) {
                    logger.warn("Error creating mock S3 response: {}", e.getMessage());
                    return CompletableFuture.completedFuture(S3FetchResult.serviceError("Mock data loading error: " + e.getMessage()));
                }
            }

            return CompletableFuture.completedFuture(S3FetchResult.notFound());
        }

        @Override
        public CompletableFuture<String> uploadFileAsync(String keyName, InputStream inputStream, long contentLength, String contentType) {
            uploadRequests.add(keyName);
            logger.debug("Test S3 upload for key: {}", keyName);
            return CompletableFuture.completedFuture("mock://" + keyName);
        }
    }
    
    /**
     * Initializer for test environment to ensure predictable test behavior
     * Logs activation of test profile and mock services
     */
    @PostConstruct
    public void testEnvironmentInitializer() {
        logger.info("=============================================================");
        logger.info("Test profile active - Using mock services for all external calls");
        logger.info("This prevents real API calls to Google Books and other services");
        logger.info("Tests will use pre-defined mock data from classpath resources");
        logger.info("=============================================================");
    }

    // LongitoodService bean removed - now provided by TestBookCoverConfig to avoid duplicate bean definition

    // S3Client bean removed - now provided by TestBookCoverConfig to avoid duplicate bean definition

    // LocalDiskCoverCacheService bean removed - now provided by TestBookCoverConfig to avoid duplicate bean definition
}
