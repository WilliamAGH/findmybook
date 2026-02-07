package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.event.SearchResultsUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchPaginationServiceTest {

    @Mock
    private BookSearchService bookSearchService;

    @Mock
    private BookQueryRepository bookQueryRepository;

    @Mock
    private GoogleApiFetcher googleApiFetcher;

    @Mock
    private GoogleBooksMapper googleBooksMapper;

    @Mock
    private OpenLibraryBookDataService openLibraryBookDataService;

    @Mock
    private BookDataOrchestrator bookDataOrchestrator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SearchPaginationService service;

    @BeforeEach
    void setUp() {
        service = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.empty(),
            Optional.empty()
        );
    }

    @Test
    @DisplayName("search() deduplicates results, preserves Postgres ordering, and returns paginated results")
    void searchDeduplicatesAndPersistsExternal() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        List<BookSearchService.SearchResult> searchResults = List.of(
            new BookSearchService.SearchResult(uuid1, 0.95, "TSVECTOR"),
            new BookSearchService.SearchResult(uuid2, 0.87, "TSVECTOR"),
            new BookSearchService.SearchResult(uuid1, 0.95, "TSVECTOR")  // duplicate
        );

        when(bookSearchService.searchBooks("java", 24)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(uuid1, "Postgres One"),
            buildListItem(uuid2, "Postgres Two")
        ));

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "java",
            0,
            12,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );
        SearchPaginationService.SearchPage page = service.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(uuid1.toString(), uuid2.toString());
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.prefetchedCount()).isZero();
        assertThat(page.nextStartIndex()).isZero();
    }

    @Test
    @DisplayName("search() slices with start offsets and computes prefetch metadata")
    void searchRespectsOffsetsAndPrefetch() {
        // Create 29 books - will test slicing from offset 12 with limit 12
        List<UUID> bookIds = new ArrayList<>();
        List<BookSearchService.SearchResult> searchResults = new ArrayList<>();
        List<BookListItem> listItems = new ArrayList<>();
        
        for (int i = 0; i < 29; i++) {
            UUID id = UUID.randomUUID();
            bookIds.add(id);
            searchResults.add(new BookSearchService.SearchResult(id, 0.9 - (i * 0.01), "TSVECTOR"));
            listItems.add(buildListItem(id, String.format("Book %02d", i)));
        }

        when(bookSearchService.searchBooks("java", 36)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(listItems);

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "java",
            12,
            12,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );
        SearchPaginationService.SearchPage page = service.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).hasSize(12);
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(
                bookIds.get(12).toString(),
                bookIds.get(13).toString(),
                bookIds.get(14).toString(),
                bookIds.get(15).toString(),
                bookIds.get(16).toString(),
                bookIds.get(17).toString(),
                bookIds.get(18).toString(),
                bookIds.get(19).toString(),
                bookIds.get(20).toString(),
                bookIds.get(21).toString(),
                bookIds.get(22).toString(),
                bookIds.get(23).toString()
            );
        assertThat(page.hasMore()).isTrue();
        assertThat(page.prefetchedCount()).isEqualTo(5);
        assertThat(page.nextStartIndex()).isEqualTo(24);
    }

    @Test
    @DisplayName("search() handles high start indexes correctly")
    void searchPostgresOnlyHonoursHighStartIndexes() {
        SearchPaginationService postgresOnlyService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.empty(),
            Optional.empty()
        );

        UUID idOne = UUID.randomUUID();
        UUID idTwo = UUID.randomUUID();
        UUID idThree = UUID.randomUUID();

        List<BookSearchService.SearchResult> results = List.of(
            new BookSearchService.SearchResult(idOne, 0.91, "TSVECTOR"),
            new BookSearchService.SearchResult(idTwo, 0.87, "TSVECTOR"),
            new BookSearchService.SearchResult(idThree, 0.72, "TSVECTOR")
        );

        when(bookSearchService.searchBooks("miss", 34)).thenReturn(results);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(idOne, "First"),
            buildListItem(idTwo, "Second"),
            buildListItem(idThree, "Third")
        ));

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "miss",
            10,
            12,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );
        SearchPaginationService.SearchPage page = postgresOnlyService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).isEmpty();
        assertThat(page.uniqueResults()).hasSize(3);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.prefetchedCount()).isZero();
        assertThat(page.nextStartIndex()).isEqualTo(10);
    }

    @Test
    @DisplayName("search() keeps cover-first ordering when orderBy=newest")
    void searchCoverFirstWhenNewest() {
        UUID newerLowQuality = UUID.randomUUID();
        UUID olderHighQuality = UUID.randomUUID();

        List<BookSearchService.SearchResult> searchResults = List.of(
            new BookSearchService.SearchResult(newerLowQuality, 0.80, "TSVECTOR"),
            new BookSearchService.SearchResult(olderHighQuality, 0.60, "TSVECTOR")
        );

        when(bookSearchService.searchBooks("cover-newest", 24)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(newerLowQuality, "Newest Low Quality", 160, 220, false, "https://example.test/low.jpg"),
            buildListItem(olderHighQuality, "Older High Quality", 900, 1400, true, "https://cdn.test/high.jpg")
        ));

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "cover-newest",
            0,
            12,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );
        SearchPaginationService.SearchPage page = service.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(olderHighQuality.toString(), newerLowQuality.toString());
    }

    @Test
    @DisplayName("search() retains suppressed covers but ranks them after valid covers")
    void searchKeepsSuppressedButRanksLast() {
        UUID withCover = UUID.randomUUID();
        UUID suppressed = UUID.randomUUID();

        when(bookSearchService.searchBooks("suppressed", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(withCover, 0.95, "TSVECTOR"),
            new BookSearchService.SearchResult(suppressed, 0.90, "TSVECTOR")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(withCover, "Valid Cover", 600, 900, true, "https://cdn.test/high.jpg"),
            buildListItem(suppressed, "Too Wide", 1200, 120, false, "https://example.test/wide.jpg?w=1200&h=120")
        ));

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "suppressed",
            0,
            12,
            "relevance",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = service.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems())
            .extracting(Book::getId)
            .containsExactly(withCover.toString(), suppressed.toString());
        assertThat(page.pageItems().get(1).getQualifiers())
            .containsEntry("cover.suppressed", true);
        assertThat(page.pageItems().get(1).getExternalImageUrl()).isNull();
    }

    @Test
    @DisplayName("search() triggers Google API fallback when Postgres returns no matches")
    void searchInvokesFallbackWhenPostgresEmpty() {
        UUID fallbackId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", "google-vol-1");
        ObjectNode volumeInfo = node.putObject("volumeInfo");
        volumeInfo.put("title", "Fallback Title");

        BookAggregate aggregate = BookAggregate.builder()
            .title("Fallback Title")
            .authors(List.of("Google Author"))
            .slugBase("fallback-title")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId(fallbackId.toString())
                .imageLinks(Map.of("thumbnail", "https://example.test/fallback.jpg"))
                .build())
            .build();

        when(bookSearchService.searchBooks("fallback", 24)).thenReturn(List.of());
        when(googleApiFetcher.streamSearchItems("fallback", 24, "newest", null, true))
            .thenAnswer(inv -> Flux.just(node));
        when(googleApiFetcher.streamSearchItems("fallback", 24, "newest", null, false))
            .thenReturn(Flux.empty());
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(true);
        when(googleBooksMapper.map(node)).thenReturn(aggregate);

        SearchPaginationService fallbackService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.of(googleApiFetcher),
            Optional.of(googleBooksMapper)
        );

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "fallback",
            0,
            12,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );
        SearchPaginationService.SearchPage page = fallbackService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly(fallbackId.toString());
        verify(googleApiFetcher, times(1)).streamSearchItems("fallback", 24, "newest", null, true);
    }

    @Test
    @DisplayName("search() applies published year filtering using repository-backed year metadata")
    void searchFiltersByPublishedYear() {
        UUID matchingYear = UUID.randomUUID();
        UUID differentYear = UUID.randomUUID();

        when(bookSearchService.searchBooks("history", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(matchingYear, 0.91, "FULLTEXT"),
            new BookSearchService.SearchResult(differentYear, 0.83, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchPublishedYears(anyList())).thenReturn(Map.of(
            matchingYear, 2024,
            differentYear, 1999
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(matchingYear, "Modern History")
        ));

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "history",
            0,
            12,
            "relevance",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY,
            2024
        );

        SearchPaginationService.SearchPage page = service.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly(matchingYear.toString());
    }

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
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", "google-vol-realtime");
        ObjectNode volumeInfo = node.putObject("volumeInfo");
        volumeInfo.put("title", "Realtime Systems");

        BookAggregate aggregate = BookAggregate.builder()
            .title("Realtime Systems")
            .authors(List.of("A. Author"))
            .slugBase("realtime-systems")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("google-vol-realtime")
                .imageLinks(Map.of("thumbnail", "https://example.test/realtime.jpg"))
                .build())
            .build();

        when(googleApiFetcher.streamSearchItems("distributed systems", 12, "relevance", null, true))
            .thenReturn(Flux.just(node));
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);
        when(googleBooksMapper.map(node)).thenReturn(aggregate);
        when(openLibraryBookDataService.queryBooksByTitle("distributed systems"))
            .thenReturn(Flux.empty());
        when(openLibraryBookDataService.queryBooksByAuthor("distributed systems"))
            .thenReturn(Flux.empty());

        SearchPaginationService realtimeService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.of(googleApiFetcher),
            Optional.of(googleBooksMapper),
            Optional.of(openLibraryBookDataService),
            Optional.of(bookDataOrchestrator),
            Optional.of(eventPublisher),
            true
        );

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "distributed systems",
            0,
            12,
            "author", // unsupported by Google; service should normalize to relevance
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = realtimeService.search(request).block();

        assertThat(page).isNotNull();
        verify(googleApiFetcher, times(1))
            .streamSearchItems("distributed systems", 12, "relevance", null, true);
        verify(eventPublisher, timeout(2000).atLeastOnce()).publishEvent((Object) argThat(event ->
            event instanceof SearchResultsUpdatedEvent
                && "GOOGLE_BOOKS".equals(((SearchResultsUpdatedEvent) event).getSource())
                && ((SearchResultsUpdatedEvent) event).getNewResults() != null
                && !((SearchResultsUpdatedEvent) event).getNewResults().isEmpty()
        ));
    }

    private BookListItem buildListItem(UUID id, String title) {
        Map<String, Object> tags = Map.<String, Object>of("nytBestseller", Map.<String, Object>of("rank", 1));
        return new BookListItem(
            id.toString(),
            "slug-" + id,
            title,
            title + " description",
            List.of("Fixture Author"),
            List.of("Fixture Category"),
            "https://example.test/" + id + ".jpg",
            "s3://covers/" + id + ".jpg",
            "https://fallback.example.test/" + id + ".jpg",
            600,
            900,
            true,
            4.5,
            100,
            tags
        );
    }

    private BookListItem buildListItem(UUID id,
                                       String title,
                                       int width,
                                       int height,
                                       boolean highResolution,
                                       String coverUrl) {
        Map<String, Object> tags = Map.<String, Object>of();
        return new BookListItem(
            id.toString(),
            "slug-" + id,
            title,
            title + " description",
            List.of("Fixture Author"),
            List.of("Fixture Category"),
            coverUrl,
            "s3://covers/" + id + ".jpg",
            "https://fallback.example.test/" + id + ".jpg",
            width,
            height,
            highResolution,
            4.0,
            25,
            tags
        );
    }

    @ExtendWith(MockitoExtension.class)
    static class BookSearchServiceBlankQueryTest {

        @Mock
        private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

        private BookSearchService bookSearchService;

        @BeforeEach
        void initService() {
            bookSearchService = new BookSearchService(
                jdbcTemplate,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false
            );
        }

        @Test
        @DisplayName("searchBooks() returns empty list for blank query without touching database")
        void searchBooksSkipsBlankQuery() {
            List<BookSearchService.SearchResult> results = bookSearchService.searchBooks("   ", 10);

            assertThat(results).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("searchAuthors() returns empty list for blank query without touching database")
        void searchAuthorsSkipsBlankQuery() {
            List<BookSearchService.AuthorResult> results = bookSearchService.searchAuthors("\t", 5);

            assertThat(results).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }
    }
}
