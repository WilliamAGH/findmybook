package net.findmybook.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import net.findmybook.dto.BookAggregate;
import net.findmybook.service.event.SearchProgressEvent;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import net.findmybook.util.SearchQueryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    void should_ClassifyLocalResilienceDenialsSeparately_When_ProviderErrorIsWrapped() {
        RequestNotPermitted rateLimiterDenial = RequestNotPermitted.createRequestNotPermitted(
            RateLimiter.ofDefaults("open-library-test")
        );
        CallNotPermittedException circuitDenial = CallNotPermittedException.createCallNotPermittedException(
            CircuitBreaker.ofDefaults("open-library-test")
        );

        SearchProgressEvent.SearchStatus rateLimiterStatus = ReflectionTestUtils.invokeMethod(
            SearchRealtimeCoordinator.class,
            "classifyProviderError",
            new IllegalStateException("wrapped", rateLimiterDenial)
        );
        SearchProgressEvent.SearchStatus circuitStatus = ReflectionTestUtils.invokeMethod(
            SearchRealtimeCoordinator.class,
            "classifyProviderError",
            new IllegalStateException("wrapped", circuitDenial)
        );

        assertThat(rateLimiterStatus).isEqualTo(SearchProgressEvent.SearchStatus.LOCAL_RATE_LIMITED);
        assertThat(circuitStatus).isEqualTo(SearchProgressEvent.SearchStatus.LOCAL_CIRCUIT_OPEN);
    }

    @Test
    void should_PublishDeniedLocalRateLimitStatus_When_OpenLibraryRejectsRealtimeRequest() {
        stubCompletePostgresPage("distributed systems", 24, 12, null);
        RequestNotPermitted denial = RequestNotPermitted.createRequestNotPermitted(
            RateLimiter.ofDefaults("open-library-realtime-test")
        );
        when(openLibraryBookDataService.queryBooksByEverything("distributed systems", "author"))
            .thenReturn(Flux.error(denial));

        SearchPaginationService.SearchPage page = fallbackEnabledService()
            .search(searchRequest("distributed systems", 0, 12, "author"))
            .block();

        assertThat(page).isNotNull();
        verify(eventPublisher, timeout(2000).atLeastOnce()).publishEvent((Object) argThat(event ->
            event instanceof SearchProgressEvent progressEvent
                && progressEvent.getStatus() == SearchProgressEvent.SearchStatus.LOCAL_RATE_LIMITED
                && "OPEN_LIBRARY".equals(progressEvent.getSource())
                && progressEvent.getMessage().contains("request denied by local rate limiting")
                && !progressEvent.getMessage().contains("deferred")
        ));
    }

    @Test
    @DisplayName("search() publishes realtime external candidates when Postgres has baseline results")
    void searchPublishesRealtimeExternalCandidates() {
        stubCompletePostgresPage("distributed systems", 24, 12, null);

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems("distributed systems", 12, "relevance", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-realtime", "Realtime Systems")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-realtime".equals(node.path("id").asString("")))))
            .thenReturn(googleAggregate("google-vol-realtime", "Realtime Systems", "https://example.test/realtime.jpg"));
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString(), eq(0), eq(24)))
            .thenReturn(Flux.empty());

        SearchPaginationService realtimeService = fallbackEnabledService();
        SearchPaginationService.SearchPage page = realtimeService.search(searchRequest("distributed systems", 0, 12, "author")).block();
        SearchPaginationService.SearchPage secondPage = realtimeService
            .search(searchRequest("distributed systems", 12, 12, "author"))
            .block();

        assertThat(page).isNotNull();
        assertThat(secondPage).isNotNull();
        verify(googleApiFetcher, times(1))
            .streamSearchItems("distributed systems", 12, "relevance", null, true);
        verify(openLibraryBookDataService, timeout(2000).times(1))
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
        stubCompletePostgresPage("distributed systems", 24, 12, LocalDate.of(2024, 1, 1));

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
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

    @Test
    @DisplayName("search() publishes realtime events on clamped page-size query hash")
    void should_PublishRealtimeEventsOnClampedTopic_When_MaxResultsExceedsLimit() {
        stubCompletePostgresPage("distributed systems", 200, 100, null);

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems("distributed systems", 20, "relevance", null, true))
            .thenReturn(Flux.just(googleVolumeNode("google-vol-clamped", "Realtime Systems")));
        when(googleBooksMapper.map(argThat(node -> "google-vol-clamped".equals(node.path("id").asString("")))))
            .thenReturn(googleAggregate("google-vol-clamped", "Realtime Systems", "https://example.test/realtime.jpg"));

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
        SearchPaginationService.SearchRequest request = searchRequest("distributed systems", 0, 500, "relevance");
        SearchPaginationService.SearchPage page = realtimeService.search(request).block();

        assertThat(page).isNotNull();
        String expectedQueryHash = SearchQueryUtils.topicKey(
            "distributed systems",
            "relevance",
            "ANY",
            "ANY",
            null,
            100
        );
        verify(eventPublisher, timeout(2000).atLeastOnce()).publishEvent((Object) argThat(event ->
            event instanceof SearchResultsUpdatedEvent updatedEvent
                && expectedQueryHash.equals(updatedEvent.getQueryHash())
        ));
    }

    private void stubCompletePostgresPage(String query,
                                          int searchWindow,
                                          int resultCount,
                                          LocalDate publishedDate) {
        List<UUID> bookIds = IntStream.range(0, resultCount)
            .mapToObj(ignored -> UUID.randomUUID())
            .toList();
        List<BookSearchService.SearchResult> searchResults = IntStream.range(0, resultCount)
            .mapToObj(index -> new BookSearchService.SearchResult(
                bookIds.get(index),
                1.0 - (index * 0.001),
                "FULLTEXT"
            ))
            .toList();
        when(bookSearchService.searchBooks(query, searchWindow)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(IntStream.range(0, resultCount)
            .mapToObj(index -> buildListItem(
                bookIds.get(index),
                "Postgres baseline " + index,
                600,
                900,
                true,
                "https://example.test/baseline-" + index + ".jpg",
                publishedDate
            ))
            .toList());
        if (publishedDate != null) {
            when(bookQueryRepository.fetchPublishedYears(anyList())).thenReturn(bookIds.stream()
                .collect(Collectors.toMap(bookId -> bookId, ignored -> publishedDate.getYear())));
        }
    }
}
