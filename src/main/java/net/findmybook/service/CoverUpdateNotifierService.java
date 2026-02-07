package net.findmybook.service;

import net.findmybook.application.cover.CoverS3UploadCoordinator;
import net.findmybook.application.realtime.CoverRealtimePayloadFactory;
import net.findmybook.service.event.BookCoverUpdatedEvent;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.core.MessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Relays internal cover/search events to websocket topics.
 *
 * <p>This service keeps realtime topic routing in one place while delegating
 * payload mapping and S3 upload orchestration to dedicated collaborators.</p>
 */
@Service
public class CoverUpdateNotifierService {

    private static final Logger logger = LoggerFactory.getLogger(CoverUpdateNotifierService.class);

    private final MessageSendingOperations<String> messagingTemplate;
    private final CoverRealtimePayloadFactory payloadFactory;
    private final CoverS3UploadCoordinator coverS3UploadCoordinator;

    /**
     * Creates the realtime notifier with collaborators for payload mapping and upload workflows.
     */
    public CoverUpdateNotifierService(@Lazy MessageSendingOperations<String> messagingTemplate,
                                      CoverRealtimePayloadFactory payloadFactory,
                                      CoverS3UploadCoordinator coverS3UploadCoordinator) {
        this.messagingTemplate = messagingTemplate;
        this.payloadFactory = payloadFactory;
        this.coverS3UploadCoordinator = coverS3UploadCoordinator;
    }

    /**
     * Publishes book cover updates to subscribers of the affected book topic.
     */
    @EventListener
    public void handleBookCoverUpdated(BookCoverUpdatedEvent event) {
        if (event == null || !StringUtils.hasText(event.getGoogleBookId()) || !StringUtils.hasText(event.getNewCoverUrl())) {
            logger.warn("Received BookCoverUpdatedEvent with missing fields. identifierKey={}, googleBookId={}, newCoverUrl={}",
                event != null ? event.getIdentifierKey() : null,
                event != null ? event.getGoogleBookId() : null,
                event != null ? event.getNewCoverUrl() : null);
            return;
        }

        String destination = coverUpdateTopic(event.getGoogleBookId());
        logger.info("Sending cover update to {}: URL={}, source={}", destination, event.getNewCoverUrl(), event.getSource());
        messagingTemplate.convertAndSend(destination, payloadFactory.createCoverUpdatePayload(event));
    }

    /**
     * Publishes incremental search-result updates to subscribers of the search topic.
     */
    @EventListener
    public void handleSearchResultsUpdated(SearchResultsUpdatedEvent event) {
        if (event == null || !StringUtils.hasText(event.getQueryHash()) || event.getNewResults() == null) {
            logger.warn("Received SearchResultsUpdatedEvent with missing fields. queryHash={}, searchQuery={}, source={}",
                event != null ? event.getQueryHash() : null,
                event != null ? event.getSearchQuery() : null,
                event != null ? event.getSource() : null);
            return;
        }

        String destination = searchResultsTopic(event.getQueryHash());
        logger.info("Sending search results update to {}: {} new results from {}, complete={}",
            destination,
            event.getNewResults().size(),
            event.getSource(),
            event.isComplete());
        messagingTemplate.convertAndSend(destination, payloadFactory.createSearchResultsPayload(event));
    }

    /**
     * Publishes search-progress updates to subscribers of the search topic.
     */
    @EventListener
    public void handleSearchProgress(SearchProgressEvent event) {
        if (event == null || !StringUtils.hasText(event.getQueryHash()) || event.getStatus() == null) {
            logger.warn("Received SearchProgressEvent with missing fields. queryHash={}, searchQuery={}, status={}",
                event != null ? event.getQueryHash() : null,
                event != null ? event.getSearchQuery() : null,
                event != null ? event.getStatus() : null);
            return;
        }

        String destination = searchProgressTopic(event.getQueryHash());
        logger.debug("Sending search progress to {}: {} - {}", destination, event.getStatus(), event.getMessage());
        messagingTemplate.convertAndSend(destination, payloadFactory.createSearchProgressPayload(event));
    }

    /**
     * Publishes book upsert events and triggers post-upsert S3 cover upload orchestration.
     */
    @EventListener
    public void handleBookUpsert(BookUpsertEvent event) {
        if (event == null || !StringUtils.hasText(event.getBookId())) {
            logger.warn("Received BookUpsertEvent with missing bookId");
            return;
        }

        String destination = bookUpsertTopic(event.getBookId());
        logger.info("Sending book upsert to {}: title={}, isNew={}", destination, event.getTitle(), event.isNew());
        messagingTemplate.convertAndSend(destination, payloadFactory.createBookUpsertPayload(event));

        coverS3UploadCoordinator.triggerUpload(event);
    }

    private String coverUpdateTopic(String googleBookId) {
        return "/topic/book/" + googleBookId + "/coverUpdate";
    }

    private String searchResultsTopic(String queryHash) {
        return "/topic/search/" + queryHash + "/results";
    }

    private String searchProgressTopic(String queryHash) {
        return "/topic/search/" + queryHash + "/progress";
    }

    private String bookUpsertTopic(String bookId) {
        return "/topic/book/" + bookId + "/upsert";
    }
}
