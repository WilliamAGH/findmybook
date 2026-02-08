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
import net.findmybook.util.ApplicationConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @DisplayName("search() applies newest ordering ahead of cover quality when orderBy=newest")
    void searchNewestOrderingWhenNewest() {
        UUID newerLowQuality = UUID.randomUUID();
        UUID olderHighQuality = UUID.randomUUID();

        List<BookSearchService.SearchResult> searchResults = List.of(
            new BookSearchService.SearchResult(newerLowQuality, 0.80, "TSVECTOR"),
            new BookSearchService.SearchResult(olderHighQuality, 0.60, "TSVECTOR")
        );

        when(bookSearchService.searchBooks("cover-newest", 24)).thenReturn(searchResults);
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                newerLowQuality,
                "Newest Low Quality",
                1200,
                120,
                false,
                "https://example.test/low.jpg?w=1200&h=120",
                LocalDate.parse("2025-01-10")
            ),
            buildListItem(
                olderHighQuality,
                "Older High Quality",
                900,
                1400,
                true,
                "https://cdn.test/high.jpg",
                LocalDate.parse("2020-06-01")
            )
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
            .containsExactly(newerLowQuality.toString(), olderHighQuality.toString());
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
    @DisplayName("search() treats null-equivalent cover values as no-cover but sorts by relevance")
    void searchDemotesNullEquivalentCoverValues() {
        UUID nullEquivalent = UUID.randomUUID();
        UUID validCover = UUID.randomUUID();

        when(bookSearchService.searchBooks("cover-null-values", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(nullEquivalent, 0.99, "TSVECTOR"),
            new BookSearchService.SearchResult(validCover, 0.70, "TSVECTOR")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            new BookListItem(
                nullEquivalent.toString(),
                "slug-" + nullEquivalent,
                "Null Equivalent",
                "Null Equivalent description",
                List.of("Fixture Author"),
                List.of("Fixture Category"),
                null,
                "null",
                ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH,
                null,
                null,
                null,
                3.5,
                12,
                Map.of()
            ),
            buildListItem(validCover, "Valid Cover", 700, 1100, true, "https://cdn.test/valid.jpg")
        ));

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "cover-null-values",
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
            .containsExactly(nullEquivalent.toString(), validCover.toString());
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
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
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
    @DisplayName("search() should use Open Library as primary fallback when Postgres returns no matches")
    void should_UseOpenLibraryPrimaryFallback_When_PostgresReturnsNoMatches() {
        Book openLibraryOne = buildOpenLibraryCandidate("OL-PRIMARY-1", "Open Primary One");
        Book openLibraryTwo = buildOpenLibraryCandidate("OL-PRIMARY-2", "Open Primary Two");

        when(bookSearchService.searchBooks("fallback", 2)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("fallback"), anyString()))
            .thenReturn(Flux.just(openLibraryOne, openLibraryTwo));

        SearchPaginationService openLibraryPrimaryService = new SearchPaginationService(
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
            "fallback",
            0,
            1,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-PRIMARY-1");
        verify(openLibraryBookDataService).queryBooksByEverything("fallback", "newest");
        verifyNoInteractions(googleApiFetcher);
        verifyNoInteractions(googleBooksMapper);
    }

    @Test
    @DisplayName("search() should query Google as secondary fallback when Open Library underfills")
    void should_QueryGoogleSecondaryFallback_When_OpenLibraryUnderfills() {
        Book openLibraryOnly = buildOpenLibraryCandidate("OL-PRIMARY-1", "Open Primary One");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", "google-vol-2");
        node.putObject("volumeInfo").put("title", "Google Secondary");

        BookAggregate aggregate = BookAggregate.builder()
            .title("Google Secondary")
            .authors(List.of("Google Author"))
            .slugBase("google-secondary")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("GOOGLE-SECONDARY-1")
                .imageLinks(Map.of("thumbnail", "https://example.test/google-secondary.jpg"))
                .build())
            .build();

        when(bookSearchService.searchBooks("fallback", 4)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("fallback"), anyString()))
            .thenReturn(Flux.just(openLibraryOnly));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("fallback", 3, "newest", null, true)).thenReturn(Flux.just(node));
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);
        when(googleBooksMapper.map(node)).thenReturn(aggregate);

        SearchPaginationService openLibraryPrimaryService = new SearchPaginationService(
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
            "fallback",
            0,
            2,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(2);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactlyInAnyOrder("OL-PRIMARY-1", "GOOGLE-SECONDARY-1");
        verify(googleApiFetcher, times(1)).streamSearchItems("fallback", 3, "newest", null, true);
    }

    @Test
    @DisplayName("search() preserves Open Library description and page count metadata in fallback results")
    void should_PreserveOpenLibraryMetadata_When_FallbackResultsReturned() {
        Book openLibraryCandidate = buildOpenLibraryCandidate("OL-PRIMARY-1", "The Partner");
        openLibraryCandidate.setDescription("Full Open Library description text");
        openLibraryCandidate.setPageCount(416);
        openLibraryCandidate.setPublisher("Doubleday");
        openLibraryCandidate.setLanguage("eng");

        when(bookSearchService.searchBooks("john grisham", 2)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString()))
            .thenReturn(Flux.just(openLibraryCandidate));

        SearchPaginationService openLibraryPrimaryService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.empty(),
            Optional.empty(),
            Optional.of(openLibraryBookDataService),
            Optional.of(bookDataOrchestrator),
            Optional.of(eventPublisher),
            true
        );

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "john grisham",
            0,
            1,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).hasSize(1);
        Book first = page.pageItems().getFirst();
        assertThat(first.getDescription()).isEqualTo("Full Open Library description text");
        assertThat(first.getPageCount()).isEqualTo(416);
        assertThat(first.getPublisher()).isEqualTo("Doubleday");
        assertThat(first.getLanguage()).isEqualTo("eng");
    }

    @Test
    @DisplayName("search() should keep Open Library fallback results when Google secondary fails")
    void should_KeepOpenLibraryResults_When_GoogleSecondaryFails() {
        Book openLibraryOnly = buildOpenLibraryCandidate("OL-PRIMARY-1", "Open Primary One");

        when(bookSearchService.searchBooks("fallback", 4)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("fallback"), anyString()))
            .thenReturn(Flux.just(openLibraryOnly));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("fallback", 3, "newest", null, true))
            .thenReturn(Flux.error(new IllegalStateException("rate limited")));
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);

        SearchPaginationService openLibraryPrimaryService = new SearchPaginationService(
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
            "fallback",
            0,
            2,
            "newest",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = openLibraryPrimaryService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("OL-PRIMARY-1");
    }

    @Test
    @DisplayName("search() should merge Open Library candidates and sort by relevance score")
    void should_MergeOpenLibraryCandidates_When_PostgresHasCoverGaps() {
        UUID postgresCovered = UUID.randomUUID();
        UUID postgresSuppressed = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 6)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresCovered, 0.98, "FULLTEXT"),
            new BookSearchService.SearchResult(postgresSuppressed, 0.88, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(postgresCovered, "Postgres Covered", 600, 900, true, "https://example.test/postgres-covered.jpg"),
            buildListItem(postgresSuppressed, "Postgres Suppressed", 1200, 120, false, "https://example.test/postgres-wide.jpg?w=1200&h=120")
        ));

        Book openLibraryCandidate = buildOpenLibraryCandidate("OL-OPEN-1", "The Firm");
        openLibraryCandidate.setExternalImageUrl("https://covers.openlibrary.org/b/id/9330593-L.jpg");
        openLibraryCandidate.setCoverImageWidth(600);
        openLibraryCandidate.setCoverImageHeight(900);
        openLibraryCandidate.setIsCoverHighResolution(true);
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString()))
            .thenReturn(Flux.just(openLibraryCandidate));

        SearchPaginationService openLibraryAugmentingService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.empty(),
            Optional.empty(),
            Optional.of(openLibraryBookDataService),
            Optional.of(bookDataOrchestrator),
            Optional.of(eventPublisher),
            true
        );

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "john grisham",
            0,
            3,
            "relevance",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = openLibraryAugmentingService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId)
            .containsExactly(postgresSuppressed.toString(), postgresCovered.toString(), "OL-OPEN-1");
        verify(eventPublisher, timeout(300).times(0)).publishEvent(any());
        verifyNoInteractions(googleApiFetcher);
    }

    @Test
    @DisplayName("search() triggers Open Library metadata refresh when Postgres page metadata is incomplete")
    void should_TriggerMetadataRefresh_When_PostgresMissingDescriptionOrPageCount() {
        UUID postgresId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 4)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresId, 0.98, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                postgresId,
                "The Partner",
                List.of("John Grisham"),
                600,
                900,
                true,
                "https://covers.openlibrary.org/b/id/9323420-L.jpg"
            )
        ));

        Book openLibraryCandidate = buildOpenLibraryCandidate("OL77004W", "The Partner");
        openLibraryCandidate.setAuthors(List.of("John Grisham"));
        openLibraryCandidate.setDescription("A fuller Open Library description for The Partner.");
        openLibraryCandidate.setPageCount(416);
        openLibraryCandidate.setPublisher("Doubleday");
        openLibraryCandidate.setLanguage("eng");
        openLibraryCandidate.setExternalImageUrl("https://covers.openlibrary.org/b/id/9323420-L.jpg");
        when(openLibraryBookDataService.queryBooksByEverything(eq("john grisham"), anyString()))
            .thenReturn(Flux.just(openLibraryCandidate));

        SearchPaginationService metadataRefreshingService = new SearchPaginationService(
            bookSearchService,
            bookQueryRepository,
            Optional.empty(),
            Optional.empty(),
            Optional.of(openLibraryBookDataService),
            Optional.of(bookDataOrchestrator),
            Optional.of(eventPublisher),
            true
        );

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "john grisham",
            0,
            2,
            "relevance",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = metadataRefreshingService.search(request).block();

        assertThat(page).isNotNull();
        verify(openLibraryBookDataService).queryBooksByEverything("john grisham", "relevance");
        verify(bookDataOrchestrator).persistBooksAsync(
            argThat(books -> books != null
                && books.size() == 1
                && "OL77004W".equals(books.getFirst().getId())
                && Integer.valueOf(416).equals(books.getFirst().getPageCount())),
            eq("SEARCH_METADATA_REFRESH")
        );
        verifyNoInteractions(googleApiFetcher);
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
    @DisplayName("search() uses unauthenticated Google fallback when API key is unavailable")
    void should_UseUnauthenticatedGoogleFallback_When_ApiKeyMissing() {
        when(bookSearchService.searchBooks("distributed systems", 24)).thenReturn(List.of());
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString()))
            .thenReturn(Flux.empty());

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("id", "google-vol-unauth");
        ObjectNode volumeInfo = node.putObject("volumeInfo");
        volumeInfo.put("title", "Pragmatic Distributed Systems");

        BookAggregate aggregate = BookAggregate.builder()
            .title("Pragmatic Distributed Systems")
            .authors(List.of("Distributed Author"))
            .slugBase("pragmatic-distributed-systems")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("GOOGLE_BOOKS")
                .externalId("google-vol-unauth")
                .imageLinks(Map.of("thumbnail", "https://example.test/google-unauth.jpg"))
                .build())
            .build();

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(false);
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("distributed systems", 24, "relevance", null, false))
            .thenReturn(Flux.just(node));
        when(googleBooksMapper.map(node)).thenReturn(aggregate);

        SearchPaginationService fallbackService = new SearchPaginationService(
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
            "author",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = fallbackService.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.totalUnique()).isEqualTo(1);
        assertThat(page.pageItems()).extracting(Book::getId).containsExactly("google-vol-unauth");
        verify(googleApiFetcher, times(0))
            .streamSearchItems("distributed systems", 24, "relevance", null, true);
        verify(googleApiFetcher, times(1))
            .streamSearchItems("distributed systems", 24, "relevance", null, false);
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

        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems("distributed systems", 12, "relevance", null, true))
            .thenReturn(Flux.just(node));
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);
        when(googleBooksMapper.map(node)).thenReturn(aggregate);
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString()))
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
        verify(openLibraryBookDataService, timeout(2000).atLeastOnce())
            .queryBooksByEverything("distributed systems", "author");
        verify(eventPublisher, timeout(2000).atLeastOnce()).publishEvent((Object) argThat(event ->
            event instanceof SearchResultsUpdatedEvent
                && "GOOGLE_BOOKS".equals(((SearchResultsUpdatedEvent) event).getSource())
                && ((SearchResultsUpdatedEvent) event).getNewResults() != null
                && !((SearchResultsUpdatedEvent) event).getNewResults().isEmpty()
        ));
    }

    @Test
    @DisplayName("search() skips realtime updates when fallback already merged external results")
    void should_SkipRealtime_When_FallbackAlreadyProvidedExternalResults() {
        UUID postgresId = UUID.randomUUID();
        Book openRealtime = buildOpenLibraryCandidate("OL-REALTIME-1", "Open Realtime Result");

        when(bookSearchService.searchBooks("distributed systems", 24)).thenReturn(List.of(
            new BookSearchService.SearchResult(postgresId, 0.96, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(postgresId, "Designing Data-Intensive Applications")
        ));
        when(openLibraryBookDataService.queryBooksByEverything(eq("distributed systems"), anyString()))
            .thenReturn(Flux.just(openRealtime));

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
            "author",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = realtimeService.search(request).block();
        assertThat(page).isNotNull();
        verify(eventPublisher, timeout(300).times(0)).publishEvent(any());
    }

    @Test
    @DisplayName("search() demotes non-author exact-title rows for likely author queries")
    void should_DemoteExactTitleRows_When_QueryLooksLikeAuthorAndAuthorsDoNotMatch() {
        UUID noisyExactTitleId = UUID.randomUUID();
        UUID authoredWorkId = UUID.randomUUID();

        when(bookSearchService.searchBooks("john grisham", 4)).thenReturn(List.of(
            new BookSearchService.SearchResult(noisyExactTitleId, 1.0d, "EXACT_TITLE"),
            new BookSearchService.SearchResult(authoredWorkId, 0.99d, "FULLTEXT")
        ));
        when(bookQueryRepository.fetchBookListItems(anyList())).thenReturn(List.of(
            buildListItem(
                noisyExactTitleId,
                "John Grisham",
                List.of("Nancy Best"),
                600,
                900,
                true,
                "https://covers.openlibrary.org/b/id/1368891-L.jpg"
            ),
            buildListItem(
                authoredWorkId,
                "The Firm",
                List.of("John Grisham"),
                600,
                900,
                true,
                "https://covers.openlibrary.org/b/id/9330593-L.jpg"
            )
        ));

        SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
            "john grisham",
            0,
            2,
            "relevance",
            CoverImageSource.ANY,
            ImageResolutionPreference.ANY
        );

        SearchPaginationService.SearchPage page = service.search(request).block();

        assertThat(page).isNotNull();
        assertThat(page.pageItems()).extracting(Book::getId)
            .containsExactly(authoredWorkId.toString(), noisyExactTitleId.toString());
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

    private Book buildOpenLibraryCandidate(String id, String title) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        book.setAuthors(List.of("Open Library Author"));
        book.setRetrievedFrom("OPEN_LIBRARY");
        book.setDataSource("OPEN_LIBRARY");
        return book;
    }

    private BookListItem buildListItem(UUID id,
                                       String title,
                                       int width,
                                       int height,
                                       boolean highResolution,
                                       String coverUrl) {
        return buildListItem(id, title, List.of("Fixture Author"), width, height, highResolution, coverUrl, null);
    }

    private BookListItem buildListItem(UUID id,
                                       String title,
                                       int width,
                                       int height,
                                       boolean highResolution,
                                       String coverUrl,
                                       LocalDate publishedDate) {
        return buildListItem(id, title, List.of("Fixture Author"), width, height, highResolution, coverUrl, publishedDate);
    }

    private BookListItem buildListItem(UUID id,
                                       String title,
                                       List<String> authors,
                                       int width,
                                       int height,
                                       boolean highResolution,
                                       String coverUrl) {
        return buildListItem(id, title, authors, width, height, highResolution, coverUrl, null);
    }

    private BookListItem buildListItem(UUID id,
                                       String title,
                                       List<String> authors,
                                       int width,
                                       int height,
                                       boolean highResolution,
                                       String coverUrl,
                                       LocalDate publishedDate) {
        Map<String, Object> tags = Map.<String, Object>of();
        return new BookListItem(
            id.toString(),
            "slug-" + id,
            title,
            title + " description",
            authors,
            List.of("Fixture Category"),
            coverUrl,
            "s3://covers/" + id + ".jpg",
            "https://fallback.example.test/" + id + ".jpg",
            width,
            height,
            highResolution,
            4.0,
            25,
            tags,
            publishedDate
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

    @Nested
    class OpenLibraryBookDataServiceParsingTest {

        @Test
        @DisplayName("parseOpenLibrarySearchDoc maps page count and first sentence description")
        void parseOpenLibrarySearchDoc_mapsPageCountAndDescription() {
            OpenLibraryBookDataService service = new OpenLibraryBookDataService(
                WebClient.builder(),
                "https://openlibrary.org",
                true
            );

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode doc = mapper.createObjectNode();
            doc.put("key", "/works/OL77004W");
            doc.put("title", "The Partner");
            doc.putArray("author_name").add("John Grisham");
            doc.put("first_publish_year", 1997);
            doc.put("cover_i", 9323420);
            doc.put("number_of_pages_median", 416);
            doc.putArray("first_sentence")
                .add("They found him in Ponta Porã, a pleasant little town in Brazil.");
            doc.putArray("publisher").add("Doubleday");
            doc.putArray("language").add("eng");
            doc.putArray("subject").add("Legal thrillers");

            Book parsed = ReflectionTestUtils.invokeMethod(service, "parseOpenLibrarySearchDoc", doc);

            assertThat(parsed).isNotNull();
            assertThat(parsed.getId()).isEqualTo("OL77004W");
            assertThat(parsed.getDescription()).isEqualTo("They found him in Ponta Porã, a pleasant little town in Brazil.");
            assertThat(parsed.getPageCount()).isEqualTo(416);
            assertThat(parsed.getPublisher()).isEqualTo("Doubleday");
            assertThat(parsed.getLanguage()).isEqualTo("eng");
        }

        @Test
        @DisplayName("mergeWorkDetails replaces short description with full work description")
        void mergeWorkDetails_replacesWithFullDescription() {
            OpenLibraryBookDataService service = new OpenLibraryBookDataService(
                WebClient.builder(),
                "https://openlibrary.org",
                true
            );

            Book book = new Book();
            book.setId("OL77004W");
            book.setDescription("Short first sentence.");

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode workNode = mapper.createObjectNode();
            ObjectNode description = workNode.putObject("description");
            description.put("value", "This is the complete work description from Open Library with substantially more detail.");

            Book merged = ReflectionTestUtils.invokeMethod(service, "mergeWorkDetails", book, workNode);

            assertThat(merged).isNotNull();
            assertThat(merged.getDescription())
                .isEqualTo("This is the complete work description from Open Library with substantially more detail.");
        }
    }
}
