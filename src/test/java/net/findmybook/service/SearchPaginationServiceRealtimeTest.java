package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.support.search.SearchPageAssembler;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.SearchQueryUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SearchPaginationServiceRealtimeTest extends AbstractSearchPaginationServiceTest {

    @Test
    void should_OnlyPublishCompletion_When_SnapshotAlreadyOwnsMembershipAndOrder() {
        SearchPaginationService.SearchRequest request = searchRequest("distributed systems", 0, 1, "title");
        Book postgresBook = new Book();
        postgresBook.setId("postgres-book");
        postgresBook.setTitle("A Postgres Result");
        postgresBook.setInPostgres(true);
        Book nextBook = new Book();
        nextBook.setId("next-postgres-book");
        nextBook.setTitle("B Postgres Result");
        nextBook.setInPostgres(true);
        SearchPaginationService.SearchPage snapshot = snapshot(request, List.of(postgresBook, nextBook), 1);

        coordinator(Optional.of(eventPublisher)).trigger(request, snapshot);

        SearchProgressEvent completion = captureOnlyCompletion();
        assertThat(completion.getSearchQuery()).isEqualTo(request.query());
        assertThat(completion.getMessage()).isEqualTo("Search complete");
        assertThat(completion.getSource()).isNull();
        assertThat(snapshot.uniqueResults()).containsExactly(postgresBook, nextBook);
        assertThat(snapshot.pageItems()).containsExactly(postgresBook);
        assertThat(snapshot.totalUnique()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void should_PublishCompletion_When_SnapshotHasNoPostgresResults(boolean containsExternalResult) {
        SearchPaginationService.SearchRequest request = searchRequest("distributed systems", 0, 12, "relevance");
        List<Book> books = containsExternalResult
            ? List.of(buildOpenLibraryCandidate("OL-snapshot", "External Result"))
            : List.of();

        coordinator(Optional.of(eventPublisher)).trigger(request, snapshot(request, books, 12));

        captureOnlyCompletion();
    }

    @Test
    void should_PublishRealtimeEventsOnFilterScopedTopic_When_FiltersArePresent() {
        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "distributed systems", 0, 12, "title", CoverImageSource.OPEN_LIBRARY,
            ImageResolutionPreference.HIGH_ONLY, 2024
        );

        coordinator(Optional.of(eventPublisher)).trigger(request, snapshot(request, List.of(), 12));

        String expectedQueryHash = SearchQueryUtils.topicKey(
            "distributed systems", "title", "OPEN_LIBRARY", "HIGH_ONLY", 2024, 12
        );
        assertThat(captureOnlyCompletion().getQueryHash()).isEqualTo(expectedQueryHash);
    }

    @Test
    void should_PublishRealtimeEventsOnClampedTopic_When_MaxResultsExceedsLimit() {
        when(bookSearchService.searchBooks("distributed systems", SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP))
            .thenReturn(List.of());
        SearchPaginationService realtimeService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(eventPublisher),
            true
        );
        SearchPaginationService.SearchRequest request = searchRequest("distributed systems", 0, 500, "relevance");
        SearchPaginationService.SearchPage page = realtimeService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.maxResults()).isEqualTo(100);
        String expectedQueryHash = SearchQueryUtils.topicKey(
            "distributed systems",
            "relevance",
            "ANY",
            "ANY",
            null,
            100
        );
        assertThat(captureOnlyCompletion().getQueryHash()).isEqualTo(expectedQueryHash);
    }

    @Test
    void should_NotPublish_When_RequestIsForLaterPage() {
        SearchPaginationService.SearchRequest request = searchRequest("distributed systems", 12, 12, "relevance");

        coordinator(Optional.of(eventPublisher)).trigger(request, snapshot(request, List.of(), 12));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void should_NotPublish_When_QueryIsWildcard() {
        SearchPaginationService.SearchRequest request = searchRequest("*", 0, 12, "relevance");

        coordinator(Optional.of(eventPublisher)).trigger(request, snapshot(request, List.of(), 12));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void should_NotStartProviderWork_When_NoEventPublisherIsConfigured() {
        SearchPaginationService.SearchRequest request = searchRequest("distributed systems", 0, 12, "relevance");

        coordinator(Optional.empty()).trigger(request, snapshot(request, List.of(), 12));

        verifyNoInteractions(eventPublisher);
    }

    private SearchRealtimeCoordinator coordinator(Optional<ApplicationEventPublisher> publisher) {
        return new SearchRealtimeCoordinator(publisher);
    }

    private SearchPaginationService.SearchPage snapshot(SearchPaginationService.SearchRequest request,
                                                        List<Book> books,
                                                        int effectivePageSize) {
        return new SearchPageAssembler().buildPage(
            request.query(), request.orderBy(), request.coverSource(), request.resolutionPreference(),
            request.publishedYear(), books,
            new PagingUtils.Window(0, effectivePageSize, SearchPaginationService.SEARCH_SNAPSHOT_WINDOW_CAP)
        );
    }

    private SearchProgressEvent captureOnlyCompletion() {
        ArgumentCaptor<SearchProgressEvent> event = ArgumentCaptor.forClass(SearchProgressEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        verifyNoMoreInteractions(eventPublisher);
        assertThat(event.getValue().getStatus()).isEqualTo(SearchProgressEvent.SearchStatus.COMPLETE);
        return event.getValue();
    }
}
