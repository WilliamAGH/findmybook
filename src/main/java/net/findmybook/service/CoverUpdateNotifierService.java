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
package net.findmybook.service;

import net.findmybook.exception.CoverDownloadException;
import net.findmybook.exception.CoverProcessingException;
import net.findmybook.exception.CoverTooLargeException;
import net.findmybook.exception.S3CoverUploadException;
import net.findmybook.exception.UnsafeUrlException;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageDetails;
import net.findmybook.service.event.BookCoverUpdatedEvent;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.image.CoverPersistenceService;
import net.findmybook.service.image.S3BookCoverService;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.core.MessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Lazy
public class CoverUpdateNotifierService {

    private static final Logger logger = LoggerFactory.getLogger(CoverUpdateNotifierService.class);
    private final MessageSendingOperations<String> messagingTemplate;
    private final S3BookCoverService s3BookCoverService;
    private final CoverPersistenceService coverPersistenceService;
    private final ObjectProvider<CoverUpdateNotifierService> selfProvider;
    private final MeterRegistry meterRegistry;

    // Metrics
    private final Counter s3UploadAttempts;
    private final Counter s3UploadSuccesses;
    private final Counter s3UploadFailures;
    private final Counter s3UploadRetriesTotal;
    private final Timer s3UploadDuration;

    /**
     * Constructs CoverUpdateNotifierService with required dependencies
     * - Uses lazy-loaded messaging template to avoid circular dependencies
     * - Uses constructor-injected collaborators required for event fan-out and persistence
     * 
     * @param messagingTemplate Template for sending WebSocket messages
     * @param s3BookCoverService S3 book cover service, may be null if S3 is disabled
     * @param selfProvider Provider for self-proxy to handle transactions
     * @param coverPersistenceService Service for persisting cover metadata
     * @param meterRegistry Registry for metrics
     */
    public CoverUpdateNotifierService(@Lazy MessageSendingOperations<String> messagingTemplate,
                                      @Lazy S3BookCoverService s3BookCoverService,
                                      ObjectProvider<CoverUpdateNotifierService> selfProvider,
                                      CoverPersistenceService coverPersistenceService,
                                      MeterRegistry meterRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.s3BookCoverService = s3BookCoverService;
        this.selfProvider = selfProvider;
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
        this.messagingTemplate.convertAndSend(destination, (Object) payload);
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
        
        // Convert books into the same shape expected by search.html rendering.
        payload.put("newResults", event.getNewResults().stream()
            .map(book -> {
                Map<String, Object> bookData = new HashMap<>();
                bookData.put("id", book.getId());
                bookData.put("slug", StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId());
                bookData.put("title", book.getTitle());
                bookData.put("authors", book.getAuthors());
                bookData.put("description", book.getDescription());
                bookData.put("publishedDate", book.getPublishedDate());
                bookData.put("pageCount", book.getPageCount());
                bookData.put("categories", book.getCategories());
                bookData.put("isbn10", book.getIsbn10());
                bookData.put("isbn13", book.getIsbn13());
                bookData.put("publisher", book.getPublisher());
                bookData.put("language", book.getLanguage());
                bookData.put("source", event.getSource());

                Object matchType = null;
                Object relevance = null;
                if (book.getQualifiers() != null) {
                    matchType = book.getQualifiers().get("search.matchType");
                    relevance = book.getQualifiers().get("search.relevanceScore");
                }
                if (matchType != null) {
                    bookData.put("matchType", matchType.toString());
                }
                if (relevance != null) {
                    bookData.put("relevanceScore", relevance);
                }

                Map<String, Object> coverData = new HashMap<>();
                coverData.put("s3ImagePath", book.getS3ImagePath());
                coverData.put("externalImageUrl", book.getExternalImageUrl());
                if (book.getCoverImages() != null) {
                    coverData.put("preferredUrl", book.getCoverImages().getPreferredUrl());
                    coverData.put("fallbackUrl", book.getCoverImages().getFallbackUrl());
                    if (book.getCoverImages().getSource() != null) {
                        coverData.put("source", book.getCoverImages().getSource().name());
                    }
                }
                bookData.put("cover", coverData);
                return bookData;
            })
            .toList());

        logger.info("Sending search results update to {}: {} new results from {}, complete: {}", 
            destination, event.getNewResults().size(), event.getSource(), event.isComplete());
        this.messagingTemplate.convertAndSend(destination, (Object) payload);
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
        this.messagingTemplate.convertAndSend(destination, (Object) payload);
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
        this.messagingTemplate.convertAndSend(destination, (Object) payload);

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
            executeS3Upload(event, canonicalImageUrl, source, bookUuid);
        } catch (IllegalArgumentException _) {
            logger.warn("Received BookUpsertEvent with non-UUID bookId '{}'; skipping S3 upload.", event.getBookId());
        }
    }

    private void executeS3Upload(BookUpsertEvent event, String canonicalImageUrl, String source, UUID bookUuid) {
        s3UploadAttempts.increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        s3BookCoverService.uploadCoverToS3Async(canonicalImageUrl, event.getBookId(), source)
            .retryWhen(buildRetrySpec(event))
            .switchIfEmpty(createEmptyFallback(event, canonicalImageUrl))
            .subscribe(
                details -> handleUploadSuccess(details, event, bookUuid, sample),
                error -> handleUploadError(error, event, sample)
            );
    }

    private Retry buildRetrySpec(BookUpsertEvent event) {
        return Retry.backoff(3, Duration.ofSeconds(2))
            .maxBackoff(Duration.ofSeconds(10))
            .filter(throwable -> shouldRetryThrowable(throwable, event))
            .doBeforeRetry(retrySignal -> logRetryAttempt(retrySignal, event));
    }

    private boolean shouldRetryThrowable(Throwable throwable, BookUpsertEvent event) {
        if (throwable instanceof S3CoverUploadException ex) {
            boolean shouldRetry = ex.isRetryable();
            String retryType = shouldRetry ? "retryable" : "non-retryable";
            logger.warn("{} S3 upload for book {} ({}: {}): {}",
                       shouldRetry ? "Retrying" : "NOT retrying",
                       event.getBookId(), retryType, ex.getClass().getSimpleName(), ex.getMessage());
            return shouldRetry;
        }
        logger.warn("Retrying S3 upload for book {} (unknown error): {}", event.getBookId(), throwable.getMessage());
        return true;
    }

    private void logRetryAttempt(reactor.util.retry.Retry.RetrySignal retrySignal, BookUpsertEvent event) {
        s3UploadRetriesTotal.increment();
        logger.info("Retry attempt {} for book {} after error: {}",
                   retrySignal.totalRetries() + 1,
                   event.getBookId(),
                   retrySignal.failure().getMessage());
    }

    private Mono<ImageDetails> createEmptyFallback(BookUpsertEvent event, String canonicalImageUrl) {
        return Mono.defer(() -> {
            logger.error("S3 upload pipeline returned no image details for book {} after retries. Canonical URL: {}",
                event.getBookId(), canonicalImageUrl);
            return Mono.error(new IllegalStateException("S3 upload pipeline completed without emitting ImageDetails"));
        });
    }

    /**
     * Persists S3 metadata in a NEW transaction after successful upload.
     * This method is called from reactive thread boundaries and uses self-injection
     * to ensure proper transaction propagation.
     *
     * @param bookId UUID of the book
     * @param details Image details from S3 upload (storage key, dimensions, CDN URL), may be null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistS3MetadataInNewTransaction(UUID bookId, ImageDetails details) {
        if (details == null) {
            logger.warn("S3 upload returned null details for book {}. Metadata persistence skipped.", bookId);
            return;
        }

        String storageKey = details.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            logger.warn("S3 upload for book {} yielded no storage key; metadata unchanged.", bookId);
            return;
        }

        coverPersistenceService.updateAfterS3Upload(bookId,
            new CoverPersistenceService.S3UploadResult(
                details.getStorageKey(),
                details.getUrlOrPath(),
                details.getWidth(),
                details.getHeight(),
                details.getCoverImageSource() != null ? details.getCoverImageSource() : CoverImageSource.UNDEFINED
            ));
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

    private void handleUploadSuccess(ImageDetails details, BookUpsertEvent event, UUID bookUuid, Timer.Sample sample) {
        sample.stop(s3UploadDuration);
        if (details == null || !StringUtils.hasText(details.getStorageKey())) {
            s3UploadFailures.increment();
            logger.error(
                "S3 upload returned non-persistable details for book {}. storageKey='{}'. Treating as failure.",
                event.getBookId(),
                details != null ? details.getStorageKey() : null
            );
            return;
        }
        s3UploadSuccesses.increment();
        resolveSelfProxy().persistS3MetadataInNewTransaction(bookUuid, details);
    }

    private void handleUploadError(Throwable error, BookUpsertEvent event, Timer.Sample sample) {
        sample.stop(s3UploadDuration);
        s3UploadFailures.increment();

        switch (error) {
            case CoverDownloadException ex -> {
                String causeMessage = ex.getCause() != null ? ex.getCause().getMessage() : "Unknown cause";
                logger.error("S3 upload PERMANENTLY FAILED for book {} after retries: " +
                           "Download failed from {}. Cause: {}",
                           event.getBookId(), ex.getImageUrl(), causeMessage);
            }
            case CoverProcessingException ex ->
                logger.error("S3 upload FAILED for book {} (non-retryable): " +
                           "Processing failed: {}",
                           event.getBookId(), ex.getMessage());
            case CoverTooLargeException ex ->
                logger.error("S3 upload FAILED for book {} (non-retryable): " +
                           "Image too large: {} bytes (max: {} bytes)",
                           event.getBookId(), ex.getActualSize(), ex.getMaxSize());
            case UnsafeUrlException ex ->
                logger.error("S3 upload FAILED for book {} (non-retryable): " +
                           "Unsafe URL blocked: {}",
                           event.getBookId(), ex.getImageUrl());
            default ->
                logger.error("S3 upload FAILED for book {} after retries: {}. " +
                           "Book persisted to database but cover image will NOT be available in S3. " +
                           "Metrics: attempts={}, successes={}, failures={}",
                           event.getBookId(), error.getMessage(),
                           s3UploadAttempts.count(), s3UploadSuccesses.count(), s3UploadFailures.count(), error);
        }
    }

    private CoverUpdateNotifierService resolveSelfProxy() {
        try {
            return selfProvider.getObject();
        } catch (BeansException ex) {
            throw new IllegalStateException("Failed to resolve transactional self-proxy for CoverUpdateNotifierService", ex);
        }
    }
}
