package net.findmybook.service;

import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.util.SearchQueryUtils;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

/**
 * Announces completion of the canonical search snapshot without discovering or persisting books.
 * Result membership and ordering remain owned by the snapshot returned through pagination.
 */
final class SearchRealtimeCoordinator {

    private final Optional<ApplicationEventPublisher> eventPublisher;

    SearchRealtimeCoordinator(Optional<ApplicationEventPublisher> eventPublisher) {
        this.eventPublisher = eventPublisher != null ? eventPublisher : Optional.empty();
    }

    void trigger(SearchPaginationService.SearchRequest request, SearchPaginationService.SearchPage page) {
        if (request.startIndex() != 0
            || SearchQueryUtils.isWildcard(request.query())
            || eventPublisher.isEmpty()) {
            return;
        }
        String queryHash = SearchQueryUtils.topicKey(
            request.query(),
            request.orderBy(),
            request.coverSource().name(),
            request.resolutionPreference().name(),
            request.publishedYear(),
            page.maxResults()
        );
        // Result events are incremental appends in the UI, not snapshot replacements.
        eventPublisher.get().publishEvent(new SearchProgressEvent(
            request.query(), SearchProgressEvent.SearchStatus.COMPLETE, "Search complete", queryHash
        ));
    }
}
