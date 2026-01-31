package net.findmybook.service;

import net.findmybook.dto.BookListItem;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.mapper.GoogleBooksMapper;
import net.findmybook.repository.BookQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

        when(bookSearchService.searchBooks(eq("java"), eq(24))).thenReturn(searchResults);
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

        when(bookSearchService.searchBooks(eq("java"), eq(36))).thenReturn(searchResults);
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

        when(bookSearchService.searchBooks(eq("miss"), eq(34))).thenReturn(results);
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

        when(bookSearchService.searchBooks(eq("cover-newest"), eq(24))).thenReturn(searchResults);
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

        when(bookSearchService.searchBooks(eq("suppressed"), eq(24))).thenReturn(List.of(
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

        when(bookSearchService.searchBooks(eq("fallback"), eq(24))).thenReturn(List.of());
        when(googleApiFetcher.streamSearchItems(eq("fallback"), eq(24), eq("newest"), isNull(), eq(true)))
            .thenAnswer(inv -> Flux.just(node));
        when(googleApiFetcher.streamSearchItems(eq("fallback"), eq(24), eq("newest"), isNull(), eq(false)))
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
        verify(googleApiFetcher, times(1)).streamSearchItems(eq("fallback"), eq(24), eq("newest"), isNull(), eq(true));
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
                Optional.empty()
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
