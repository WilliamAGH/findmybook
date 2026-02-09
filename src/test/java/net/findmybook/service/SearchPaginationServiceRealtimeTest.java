package net.findmybook.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

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
        when(googleBooksMapper.map(argThat(node -> "google-vol-realtime".equals(node.path("id").asText()))))
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
}
