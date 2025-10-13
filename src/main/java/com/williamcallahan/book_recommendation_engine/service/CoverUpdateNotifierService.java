/**
 * Service for real-time notification of book cover image updates and search progress
 *
 * @author William Callahan
 *
 * Features:
 * - Notifies connected clients of book cover image updates via WebSockets
 * - Transforms BookCoverUpdatedEvent into WebSocket messages
 * - Provides topic-based routing by book ID
 * - Includes image source information in notifications
 * - Uses lazy initialization to prevent circular dependency issues
 * - Handles null/incomplete events gracefully with logging
 * - Supports real-time search result updates and progress notifications
 * - Routes search events to query-specific WebSocket topics
 */
package com.williamcallahan.book_recommendation_engine.service;

import com.williamcallahan.book_recommendation_engine.model.image.CoverImageSource;
import com.williamcallahan.book_recommendation_engine.model.image.ImageDetails;
import com.williamcallahan.book_recommendation_engine.service.event.BookCoverUpdatedEvent;
import com.williamcallahan.book_recommendation_engine.service.event.SearchProgressEvent;
import com.williamcallahan.book_recommendation_engine.service.event.SearchResultsUpdatedEvent;
import com.williamcallahan.book_recommendation_engine.service.event.BookUpsertEvent;
import com.williamcallahan.book_recommendation_engine.service.image.CoverPersistenceService;
import com.williamcallahan.book_recommendation_engine.service.image.S3BookCoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.messaging.core.MessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Lazy
public class CoverUpdateNotifierService {

    private static final Logger logger = LoggerFactory.getLogger(CoverUpdateNotifierService.class);
    private final MessageSendingOperations<String> messagingTemplate;
    private final S3BookCoverService s3BookCoverService;
    private final CoverPersistenceService coverPersistenceService;
    private final MeterRegistry meterRegistry;
    private CoverUpdateNotifierService self;  // Self-injection for transaction propagation

    // Metrics
    private final Counter s3UploadAttempts;
    private final Counter s3UploadSuccesses;
    private final Counter s3UploadFailures;
    private final Counter s3UploadRetriesTotal;
    private final Timer s3UploadDuration;

    /**
     * Constructs CoverUpdateNotifierService with required dependencies
     * - Uses lazy-loaded messaging template to avoid circular dependencies
     * - Validates WebSocket configuration is properly initialized
     * 
     * @param messagingTemplate Template for sending WebSocket messages
     * @param webSocketConfig WebSocket broker configuration
     */
    public CoverUpdateNotifierService(@Lazy MessageSendingOperations<String> messagingTemplate,
                                      WebSocketMessageBrokerConfigurer webSocketConfig,
                                      @Lazy @Nullable S3BookCoverService s3BookCoverService,
                                      CoverPersistenceService coverPersistenceService,
                                      MeterRegistry meterRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.s3BookCoverService = s3BookCoverService;
        this.coverPersistenceService = coverPersistenceService;
        this.meterRegistry = meterRegistry;

        // Initialize metrics
        this.s3UploadAttempts = Counter.builder("book.cover.s3.upload.attempts")
            .description("Total number of S3 cover upload attempts")
            .register(meterRegistry);
        this.s3UploadSuccesses = Counter.builder("book.cover.s3.upload.success")
            .description("Number of successful S3 cover uploads")
            .register(meterRegistry);
        this.s3UploadFailures = Counter.builder("book.cover.s3.upload.failure")
            .description("Number of failed S3 cover uploads (after all retries)")
            .register(meterRegistry);
        this.s3UploadRetriesTotal = Counter.builder("book.cover.s3.upload.retries")
            .description("Total number of S3 upload retry attempts")
            .register(meterRegistry);
        this.s3UploadDuration = Timer.builder("book.cover.s3.upload.duration")
            .description("Duration of S3 cover upload operations")
            .register(meterRegistry);

        logger.info("CoverUpdateNotifierService initialized with metrics tracking, WebSocketConfig should be ready.");
    }

    /**
     * Self-injection setter for transaction propagation across reactive thread boundaries.
     * Enables @Transactional methods to work correctly when called from reactive callbacks.
     */
    @Autowired
    public void setSelf(CoverUpdateNotifierService self) {
        this.self = self;
    }

    /**
     * Validates that self-injection succeeded during bean initialization.
     * Without self-injection, transaction propagation from reactive threads will fail silently.
     */
    @PostConstruct
    void validateSelfInjection() {
        if (self == null) {
            logger.error("❌ CRITICAL: Self-injection failed for CoverUpdateNotifierService");
            logger.error("❌ Transaction propagation for S3 metadata persistence WILL FAIL");
            logger.error("❌ S3 uploads may succeed but metadata updates to book_image_links will be silently lost");
            throw new IllegalStateException("Self-injection failed - cannot ensure transaction safety for S3 metadata persistence");
        } else {
            logger.debug("✅ Self-injection validated for CoverUpdateNotifierService - transaction safety enabled");
        }
    }

    /**
     * Processes book cover update events and broadcasts to WebSocket clients
     * - Creates topic-specific messages for each book update
     * - Validates event data for completeness
     * - Includes all relevant cover metadata in message payload
     * - Routes messages to book-specific WebSocket topics
     * 
     * @param event The BookCoverUpdatedEvent containing updated cover information
     */
    @EventListener
    public void handleBookCoverUpdated(BookCoverUpdatedEvent event) {
        if (event.getGoogleBookId() == null || event.getNewCoverUrl() == null) {
            logger.warn("Received BookCoverUpdatedEvent with null googleBookId or newCoverUrl. IdentifierKey: {}, URL: {}, GoogleBookId: {}", 
                event.getIdentifierKey(), event.getNewCoverUrl(), event.getGoogleBookId());
            return;
        }

        String destination = "/topic/book/" + event.getGoogleBookId() + "/coverUpdate";
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("googleBookId", event.getGoogleBookId());
        payload.put("newCoverUrl", event.getNewCoverUrl());
        payload.put("identifierKey", event.getIdentifierKey());
        if (event.getSource() != null) {
            payload.put("sourceName", event.getSource().name()); // e.g., GOOGLE_BOOKS
            payload.put("sourceDisplayName", event.getSource().getDisplayName()); // e.g., "Google Books"
        } else {
            payload.put("sourceName", CoverImageSource.UNDEFINED.name());
            payload.put("sourceDisplayName", CoverImageSource.UNDEFINED.getDisplayName());
        }

        logger.info("Sending cover update to {}: URL = {}, Source = {}", destination, event.getNewCoverUrl(), event.getSource() != null ? event.getSource().name() : "UNDEFINED");
        this.messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * Processes search results update events and broadcasts to WebSocket clients
     * - Creates topic-specific messages for search result updates
     * - Includes new books and source information
     * - Routes messages to query-specific WebSocket topics
     * 
     * @param event The SearchResultsUpdatedEvent containing new search results
     */
    @EventListener
    public void handleSearchResultsUpdated(SearchResultsUpdatedEvent event) {
        if (event.getQueryHash() == null || event.getNewResults() == null) {
            logger.warn("Received SearchResultsUpdatedEvent with null queryHash or newResults. Query: {}, Source: {}", 
                event.getSearchQuery(), event.getSource());
            return;
        }

        String destination = "/topic/search/" + event.getQueryHash() + "/results";
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("searchQuery", event.getSearchQuery());
        payload.put("source", event.getSource());
        payload.put("totalResultsNow", event.getTotalResultsNow());
        payload.put("isComplete", event.isComplete());
        payload.put("newResultsCount", event.getNewResults().size());
        
        // Convert books to a simplified format for WebSocket transmission
        payload.put("newResults", event.getNewResults().stream()
            .map(book -> {
                Map<String, Object> bookData = new HashMap<>();
                bookData.put("id", book.getId());
                bookData.put("title", book.getTitle());
                bookData.put("authors", book.getAuthors());
                bookData.put("s3ImagePath", book.getS3ImagePath());
                bookData.put("externalImageUrl", book.getExternalImageUrl());
                bookData.put("publishedDate", book.getPublishedDate());
                bookData.put("description", book.getDescription());
                bookData.put("pageCount", book.getPageCount());
                bookData.put("categories", book.getCategories());
                bookData.put("isbn10", book.getIsbn10());
                bookData.put("isbn13", book.getIsbn13());
                bookData.put("publisher", book.getPublisher());
                bookData.put("language", book.getLanguage());
                if (book.getCoverImages() != null) {
                    Map<String, String> coverImages = new HashMap<>();
                    coverImages.put("small", book.getCoverImages().getFallbackUrl());
                    coverImages.put("large", book.getCoverImages().getPreferredUrl());
                    bookData.put("coverImages", coverImages);
                }
                return bookData;
            })
            .collect(Collectors.toList()));

        logger.info("Sending search results update to {}: {} new results from {}, complete: {}", 
            destination, event.getNewResults().size(), event.getSource(), event.isComplete());
        this.messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * Processes search progress events and broadcasts to WebSocket clients
     * - Creates topic-specific messages for search progress updates
     * - Includes status and progress information for beautiful loading indicators
     * - Routes messages to query-specific WebSocket topics
     * 
     * @param event The SearchProgressEvent containing progress information
     */
    @EventListener
    public void handleSearchProgress(SearchProgressEvent event) {
        if (event.getQueryHash() == null) {
            logger.warn("Received SearchProgressEvent with null queryHash. Query: {}, Status: {}", 
                event.getSearchQuery(), event.getStatus());
            return;
        }

        String destination = "/topic/search/" + event.getQueryHash() + "/progress";
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("searchQuery", event.getSearchQuery());
        payload.put("status", event.getStatus().name());
        payload.put("message", event.getMessage());
        payload.put("source", event.getSource());

        logger.debug("Sending search progress to {}: {} - {}", destination, event.getStatus(), event.getMessage());
        this.messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * Broadcast book upsert notifications to book-specific topics
     */
    @EventListener
    public void handleBookUpsert(BookUpsertEvent event) {
        if (event == null || event.getBookId() == null) {
            logger.warn("Received BookUpsertEvent with null content");
            return;
        }
        String destination = "/topic/book/" + event.getBookId() + "/upsert";
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookId", event.getBookId());
        payload.put("slug", event.getSlug());
        payload.put("title", event.getTitle());
        payload.put("isNew", event.isNew());
        payload.put("context", event.getContext());
        if (event.getCanonicalImageUrl() != null) {
            payload.put("canonicalImageUrl", event.getCanonicalImageUrl());
        }
        logger.info("Sending book upsert to {}: {} (new={})", destination, event.getTitle(), event.isNew());
        this.messagingTemplate.convertAndSend(destination, payload);

        if (s3BookCoverService == null) {
            logger.error("CRITICAL: S3BookCoverService is NULL for book {}. S3 uploads are DISABLED. " +
                "Check S3 environment variables (S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY, S3_BUCKET). " +
                "Verify S3Config bean creation and S3EnvironmentCondition.", event.getBookId());
            return;
        }

        triggerS3Upload(event);
    }
    /**
     * Kicks off the asynchronous S3 upload pipeline using the metadata carried by the
     * upsert event.
     */
    private void triggerS3Upload(BookUpsertEvent event) {
        String canonicalImageUrl = resolveCanonicalImageUrl(event);
        if (canonicalImageUrl == null || canonicalImageUrl.isBlank()) {
            logger.warn("BookUpsertEvent for book {} has NO cover URL. Event metadata: canonicalImageUrl='{}', imageLinks={}, source='{}'. " +
                "S3 upload SKIPPED - this book will have no S3 cover. Check BookUpsertService event enrichment.",
                event.getBookId(), event.getCanonicalImageUrl(), event.getImageLinks(), event.getSource());
            return;
        }

        String source = event.getSource() != null ? event.getSource() : "UNKNOWN";
        try {
            UUID bookUuid = UUID.fromString(event.getBookId());

            // Increment upload attempt counter and start timing
            s3UploadAttempts.increment();
            Timer.Sample sample = Timer.start(meterRegistry);

            s3BookCoverService.uploadCoverToS3Async(canonicalImageUrl, event.getBookId(), source)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(10))
                    .filter(this::isRetryableError)
                    .doBeforeRetry(retrySignal -> {
                        s3UploadRetriesTotal.increment();
                        logger.warn("Retrying S3 upload for book {} (attempt {}/3) after error: {}",
                            event.getBookId(), retrySignal.totalRetries() + 1, retrySignal.failure().getMessage());
                    })
                )
                .subscribe(
                    details -> {
                        sample.stop(s3UploadDuration);
                        s3UploadSuccesses.increment();
                        self.persistS3MetadataInNewTransaction(bookUuid, details);
                    },
                    error -> {
                        sample.stop(s3UploadDuration);
                        s3UploadFailures.increment();
                        logger.error("S3 upload PERMANENTLY FAILED for book {} ({}) after 3 retry attempts: {}. " +
                            "Book persisted to database but cover image will NOT be available in S3. " +
                            "Manual intervention may be required. " +
                            "Metrics: attempts={}, successes={}, failures={}",
                            event.getBookId(), canonicalImageUrl, error.getMessage(),
                            s3UploadAttempts.count(), s3UploadSuccesses.count(), s3UploadFailures.count(), error);
                    }
                );
        } catch (IllegalArgumentException ex) {
            logger.warn("Received BookUpsertEvent with non-UUID bookId '{}'; skipping S3 upload.", event.getBookId());
        }
    }

    /**
     * Determines if an error is retryable for S3 upload operations.
     * Retryable errors include network issues and temporary service failures.
     * Non-retryable errors include validation failures and permanent errors.
     */
    private boolean isRetryableError(Throwable error) {
        // Network and I/O errors are retryable
        if (error instanceof IOException ||
            error instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
            return true;
        }

        // S3 throttling errors are retryable (503 Service Unavailable, 429 Too Many Requests)
        if (error instanceof software.amazon.awssdk.services.s3.model.S3Exception s3Exception) {
            int statusCode = s3Exception.statusCode();
            return statusCode == 503 || statusCode == 429 || statusCode >= 500;
        }

        // WebClient errors may be retryable based on status code
        String errorMessage = error.getMessage();
        if (errorMessage != null) {
            return errorMessage.contains("503") ||
                   errorMessage.contains("429") ||
                   errorMessage.contains("timeout") ||
                   errorMessage.contains("connection");
        }

        return false;
    }

    /**
     * Persists S3 metadata in a NEW transaction after successful upload.
     * This method is called from reactive thread boundaries and uses self-injection
     * to ensure proper transaction propagation.
     *
     * @param bookId UUID of the book
     * @param details Image details from S3 upload (storage key, dimensions, CDN URL)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistS3MetadataInNewTransaction(UUID bookId, @Nullable ImageDetails details) {
        if (details == null) {
            logger.debug("S3 upload returned null details for book {}.", bookId);
            return;
        }

        String storageKey = details.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            logger.debug("S3 upload for book {} yielded no storage key; metadata unchanged.", bookId);
            return;
        }

        // Validate that storageKey is not a placeholder error string
        if (storageKey.startsWith("processing-failed-") ||
            storageKey.startsWith("download-failed-") ||
            storageKey.startsWith("blocked-unsafe-url-") ||
            storageKey.startsWith("upload-process-exception-") ||
            storageKey.startsWith("processed-image-too-large-") ||
            storageKey.startsWith("processed-upload-skipped-") ||
            storageKey.startsWith("processed-upload-setup-exception-") ||
            storageKey.startsWith("processed-image-too-large-for-s3-")) {
            logger.error("S3 upload FAILED for book {}: storageKey indicates error condition: {}. " +
                "Will NOT persist invalid placeholder to database. Check S3BookCoverService logs for root cause.",
                bookId, storageKey);
            return;
        }

        coverPersistenceService.updateAfterS3Upload(
            bookId,
            details.getStorageKey(),
            details.getUrlOrPath(),
            details.getWidth(),
            details.getHeight(),
            details.getCoverImageSource() != null ? details.getCoverImageSource() : CoverImageSource.UNDEFINED
        );
        logger.info("Persisted S3 cover metadata for book {} (key {}).", bookId, details.getStorageKey());
    }


    /**
     * Resolves the best candidate URL to upload, preferring the canonical entry provided
     * by the producer but falling back to image link heuristics when needed.
     */
    private String resolveCanonicalImageUrl(BookUpsertEvent event) {
        if (event.getCanonicalImageUrl() != null && !event.getCanonicalImageUrl().isBlank()) {
            return event.getCanonicalImageUrl();
        }
        return selectPreferredImageUrl(event.getImageLinks());
    }

    /**
     * Applies a deterministic priority ordering across provider-specific image keys.
     */
    private String selectPreferredImageUrl(Map<String, String> imageLinks) {
        if (imageLinks == null || imageLinks.isEmpty()) {
            return null;
        }
        List<String> priorityOrder = List.of(
            "canonical",
            "extraLarge",
            "large",
            "medium",
            "small",
            "thumbnail",
            "smallThumbnail"
        );
        for (String key : priorityOrder) {
            String candidate = imageLinks.get(key);
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return imageLinks.values().stream()
            .filter(url -> url != null && !url.isBlank())
            .findFirst()
            .orElse(null);
    }
}
