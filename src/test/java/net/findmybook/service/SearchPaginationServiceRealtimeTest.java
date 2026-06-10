package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import net.findmybook.util.SearchQueryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchPaginationServiceRealtimeTest extends AbstractSearchPaginationServiceTest {

    @Test
    @DisplayName("search() publishes realtime external candidates when Postgres has baseline results")
    void searchPublishesRealtimeExternalCandidates() {
        UUID postgresId = UUID.randomUUID();

        when(bookSearchService.searchBooks("distributed systems", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresId, 0.96, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(postgresId, "Designing Data-Intensive Applications")
        ));

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("distributed systems", 12, "relevance", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-realtime", "Realtime Systems")));
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);
        when(googleBooksMapper.map(argThat(node -> "google-vol-realtime".equals(node.path("id").asString("")))))
            .thenReturn(googleAggregate("google-vol-realtime", "Realtime Systems", "https://example.test/realtime.jpg"));
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString(), eq(0), eq(24)))
            .thenReturn(Flux.empty());

        SearchPaginationService realtimeService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = realtimeService.search(searchRequest("distributed systems", 0, 12, "author")).block();

        assertThat(page).isNotNull();
        verify(googleApiFetcher, times(1))
            .streamSearchItems("distributed systems", 12, "relevance", null, true);
        verify(openLibraryBookDataService, timeout(2000).atLeastOnce())
            .queryBooksByEverything("distributed systems", "author");
        verify(eventPublisher, timeout(2000).atLeastOnce()).publishEvent((Object) argThat(AbstractSearchPaginationServiceTest::isGoogleRealtimeEvent));
    }

    @Test
    @DisplayName("search() skips realtime updates when fallback already merged external results")
    void should_SkipRealtime_When_FallbackAlreadyProvidedExternalResults() {
        UUID postgresId = UUID.randomUUID();
        when(bookSearchService.searchBooks("distributed systems", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresId, 0.96, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(postgresId, "Designing Data-Intensive Applications")
        ));
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString(), eq(0), eq(24)))
            .thenReturn(Flux.just(buildOpenLibraryCandidate("OL-REALTIME-1", "Open Realtime Result")));

        SearchPaginationService realtimeService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = realtimeService.search(searchRequest("distributed systems", 0, 12, "author")).block();

        assertThat(page).isNotNull();
        verify(eventPublisher, timeout(300).times(0)).publishEvent(any());
    }

    @Test
    @DisplayName("search() publishes realtime events on filter-scoped query hash")
    void should_PublishRealtimeEventsOnFilterScopedTopic_When_FiltersArePresent() {
        UUID postgresId = UUID.randomUUID();
        when(bookSearchService.searchBooks("distributed systems", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresId, 0.96, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchPublishedYears(anyList())).thenReturn(java.util.Map.of(postgresId, 2024));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                postgresId,
                "Designing Data-Intensive Applications",
                600,
                900,
                true,
                "https://example.test/baseline.jpg",
                LocalDate.of(2024, 1, 1)
            )
        ));

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("distributed systems", 12, "relevance", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-filtered", "Filtered Systems")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-filtered".equals(node.path("id").asString("")))))
            .thenReturn(BookAggregate.builder()
                .title("Filtered Systems")
                .authors(List.of("Google Author"))
                .publishedDate(LocalDate.of(2024, 1, 1))
                .slugBase("filtered-systems")
                .identifiers(BookAggregate.ExternalIdentifiers.builder()
                    .source("GOOGLE_BOOKS")
                    .externalId("google-vol-filtered")
                    .imageLinks(java.util.Map.of("thumbnail", "https://example.test/filtered.jpg"))
                    .build())
                .build());
        SearchPaginationService realtimeService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            java.util.Optional.of(googleApiFetcher),
            java.util.Optional.of(googleBooksMapper),
            java.util.Optional.empty(),
            java.util.Optional.of(bookDataOrchestrator),
            java.util.Optional.of(eventPublisher),
            true
        );
        SearchPaginationService.SearchRequest request = searchRequest("distributed systems", 0, 12, "title", 2024);
        SearchPaginationService.SearchPage page = realtimeService.search(request).block();

        assertThat(page).isNotNull();
        String expectedQueryHash = SearchQueryUtils.topicKey(
            "distributed systems",
            "title",
            "ANY",
            "ANY",
            2024,
            12
        );
        verify(eventPublisher, timeout(2000).atLeastOnce()).publishEvent((Object) argThat(event ->
            event instanceof SearchResultsUpdatedEvent updatedEvent
                && expectedQueryHash.equals(updatedEvent.getQueryHash())
        ));
    }
}
