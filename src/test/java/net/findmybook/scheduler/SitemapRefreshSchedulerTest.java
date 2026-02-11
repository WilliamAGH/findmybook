package net.findmybook.scheduler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.findmybook.config.SitemapProperties;
import net.findmybook.dto.BookAggregate;
import net.findmybook.service.BookCollectionPersistenceService;
import net.findmybook.service.BookLookupService;
import net.findmybook.service.BookSitemapService;
import net.findmybook.service.BookSitemapService.SitemapSnapshot;
import net.findmybook.service.BookSitemapService.SnapshotSyncResult;
import net.findmybook.service.BookSupplementalPersistenceService;
import net.findmybook.service.BookUpsertService;
import net.findmybook.service.NewYorkTimesService;
import net.findmybook.service.SitemapService;
import net.findmybook.service.SitemapService.BookSitemapItem;
import net.findmybook.service.image.S3BookCoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SitemapRefreshSchedulerTest {

    @Mock
    private BookSitemapService bookSitemapService;

    @Mock
    private SitemapService sitemapService;

    @Mock
    private ObjectProvider<S3BookCoverService> coverServiceProvider;

    @Mock
    private S3BookCoverService coverService;

    private SitemapProperties sitemapProperties;

    private SitemapRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        sitemapProperties = new SitemapProperties();
        sitemapProperties.setSchedulerEnabled(true);
        sitemapProperties.setSchedulerCoverSampleSize(5);
        sitemapProperties.setSchedulerExternalHydrationSize(3);
        when(coverServiceProvider.getIfAvailable()).thenReturn(coverService);
        when(coverService.fetchCover(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        scheduler = new SitemapRefreshScheduler(sitemapProperties, bookSitemapService, sitemapService, coverServiceProvider);
    }

    @Test
    void refreshSitemapArtifacts_runsSnapshotUploadAndWarmups() {
        List<BookSitemapItem> items = List.of(new BookSitemapItem("book-1", "slug-1", "Title", Instant.now()));
        SitemapSnapshot snapshot = new SitemapSnapshot(Instant.now(), items);
        when(bookSitemapService.synchronizeSnapshot()).thenReturn(new SnapshotSyncResult(snapshot, true, "sitemaps/books.json"));

        scheduler.refreshSitemapArtifacts();

        verify(bookSitemapService).synchronizeSnapshot();
        verify(coverService).fetchCover(org.mockito.ArgumentMatchers.any());
    }
}

@ExtendWith(MockitoExtension.class)
class NewYorkTimesBestsellerSchedulerTest {

    @Mock
    private NewYorkTimesService newYorkTimesService;

    @Mock
    private BookLookupService bookLookupService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BookCollectionPersistenceService collectionPersistenceService;

    @Mock
    private BookSupplementalPersistenceService supplementalPersistenceService;

    @Mock
    private BookUpsertService bookUpsertService;

    private NewYorkTimesBestsellerScheduler scheduler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scheduler = new NewYorkTimesBestsellerScheduler(
            newYorkTimesService,
            bookLookupService,
            objectMapper,
            jdbcTemplate,
            collectionPersistenceService,
            supplementalPersistenceService,
            bookUpsertService,
            true,
            true
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldIgnoreMissingOptionalListFields_WhenOverviewContainsPartialData() throws Exception {
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [
                  {
                    "list_name_encoded": "hardcover-fiction",
                    "updated": "WEEKLY",
                    "books": []
                  }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("hardcover fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            eq("WEEKLY"),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-1"));

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        verify(collectionPersistenceService).upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("hardcover fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            eq("WEEKLY"),
            any(JsonNode.class)
        );
        verifyNoInteractions(bookLookupService, supplementalPersistenceService, bookUpsertService);
    }

    @Test
    void processNewYorkTimesBestsellers_shouldProcessAllListsThenThrow_WhenOneListFails() throws Exception {
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [
                  { "list_name_encoded": "hardcover-fiction", "display_name": "Hardcover Fiction", "updated": "WEEKLY", "books": [] },
                  { "list_name_encoded": "hardcover-nonfiction", "display_name": "Hardcover Nonfiction", "updated": "WEEKLY", "books": [] }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("Hardcover Fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            eq("WEEKLY"),
            any(JsonNode.class)
        )).thenThrow(new IllegalStateException("collection upsert failure"));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-nonfiction"),
            eq("Hardcover Nonfiction"),
            eq("hardcover-nonfiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            eq("WEEKLY"),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-2"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> scheduler.processNewYorkTimesBestsellers());
        assertThat(thrown.getMessage()).contains("1 of 2 list(s) failed");

        verify(collectionPersistenceService).upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("Hardcover Fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            eq("WEEKLY"),
            any(JsonNode.class)
        );
        verify(collectionPersistenceService).upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-nonfiction"),
            eq("Hardcover Nonfiction"),
            eq("hardcover-nonfiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            eq("WEEKLY"),
            any(JsonNode.class)
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldProcessAllBooksThenThrow_WhenSingleBookProcessingFails() throws Exception {
        UUID existingBookId = UUID.randomUUID();
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [
                  {
                    "list_name_encoded": "hardcover-fiction",
                    "display_name": "Hardcover Fiction",
                    "books": [
                      {
                        "title": "Broken Book",
                        "primary_isbn13": "9780316769488",
                        "description": "broken",
                        "publisher": "Pub",
                        "rank": 1
                      },
                      {
                        "title": "Healthy Book",
                        "primary_isbn13": "9780140177398",
                        "description": "healthy",
                        "publisher": "Pub",
                        "rank": 2,
                        "weeks_on_list": 3,
                        "rank_last_week": 5,
                        "book_image": "https://example.com/healthy.jpg",
                        "buy_links": [{"name":"Amazon","url":"https://amazon.example/item"}]
                      }
                    ]
                  }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("Hardcover Fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            nullable(String.class),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780316769488", null)).thenReturn(null);
        when(bookLookupService.resolveCanonicalBookId("9780140177398", null)).thenReturn(existingBookId.toString());
        when(bookUpsertService.upsert(any(BookAggregate.class)))
            .thenThrow(new IllegalStateException("book upsert failure"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> scheduler.processNewYorkTimesBestsellers());
        assertThat(thrown.getMessage()).contains("1 of 1 list(s) failed");

        verify(collectionPersistenceService, times(1)).upsertBestsellerMembership(
            eq("collection-1"),
            eq(existingBookId.toString()),
            eq(2),
            eq(3),
            eq(5),
            eq(2),
            eq("9780140177398"),
            eq((String) null),
            eq((String) null),
            any(String.class)
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldSkipInvalidIsbnsAndProcessValidEntries() throws Exception {
        UUID existingBookId = UUID.randomUUID();
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [
                  {
                    "list_name_encoded": "hardcover-fiction",
                    "display_name": "Hardcover Fiction",
                    "books": [
                      { "title": "Invalid", "primary_isbn13": "X0234484", "primary_isbn10": "ABC" },
                      { "title": "Valid", "primary_isbn10": "0140177396", "rank": 1 }
                    ]
                  }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("Hardcover Fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            nullable(String.class),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId(null, "0140177396")).thenReturn(existingBookId.toString());

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        verify(bookLookupService, times(1)).resolveCanonicalBookId(null, "0140177396");
        verify(collectionPersistenceService, times(1)).upsertBestsellerMembership(
            eq("collection-1"),
            eq(existingBookId.toString()),
            eq(1),
            eq((Integer) null),
            eq((Integer) null),
            eq(1),
            eq((String) null),
            eq("0140177396"),
            eq((String) null),
            any(String.class)
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldMapNaturalLanguageMetadataAndEnrichExistingBookFields() throws Exception {
        UUID existingBookId = UUID.randomUUID();
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "bestsellers_date": "2026-02-01",
                "lists": [
                  {
                    "list_name_encoded": "young-adult-hardcover",
                    "display_name": "Young Adult Hardcover",
                    "list_name": "Young Adult Hardcover",
                    "updated": "WEEKLY",
                    "books": [
                      {
                        "title": "Mapped Book",
                        "description": "Mapped description",
                        "author": "Mapped Author",
                        "contributor": "by Mapped Author",
                        "contributor_note": "Illustrated by Someone",
                        "asterisk": "*",
                        "dagger": "\\u2020",
                        "created_date": "2026-01-28 04:00:00",
                        "updated_date": "2026-01-31 04:00:00",
                        "publisher": "Mapped Publisher",
                        "primary_isbn13": "9780316769488",
                        "isbns": [{"isbn10":"0316769487","isbn13":"9780316769488"}],
                        "rank": 2,
                        "weeks_on_list": 9,
                        "rank_last_week": 3,
                        "age_group": "14 and up",
                        "price": "0.00",
                        "book_uri": "nyt://book/mapped",
                        "book_review_link": "https://nytimes.example/review",
                        "first_chapter_link": "https://nytimes.example/first-chapter",
                        "amazon_product_url": "https://amazon.example/mapped-direct",
                        "book_image": "https://nytimes.example/image.jpg",
                        "book_image_width": 128,
                        "book_image_height": 193,
                        "buy_links": [
                          {"name":"Amazon","url":"https://amazon.example/mapped"},
                          {"name":"Apple Books","url":"https://apple.example/mapped"}
                        ]
                      }
                    ]
                  }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("young-adult-hardcover"),
            eq("Young Adult Hardcover"),
            eq("young-adult-hardcover"),
            eq("Young Adult Hardcover"),
            eq(LocalDate.parse("2026-02-01")),
            eq(LocalDate.parse("2026-02-08")),
            eq("WEEKLY"),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780316769488", "0316769487")).thenReturn(existingBookId.toString());

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        verify(jdbcTemplate, times(1)).update(
            org.mockito.ArgumentMatchers.contains("UPDATE books"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq(existingBookId)
        );
        verify(jdbcTemplate, times(1)).update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO book_external_ids"),
            any(),
            eq(existingBookId),
            eq("9780316769488"),
            eq("9780316769488"),
            eq("0316769487"),
            eq("https://nytimes.example/review"),
            eq("https://nytimes.example/first-chapter"),
            eq((String) null),
            eq("https://amazon.example/mapped-direct"),
            eq("nyt://book/mapped")
        );
        verify(supplementalPersistenceService, times(1)).assignTag(
            eq(existingBookId.toString()),
            eq("nyt_bestseller"),
            eq("NYT Bestseller"),
            eq("NYT"),
            eq(1.0),
            anyMap()
        );
        verify(supplementalPersistenceService, times(1)).assignTag(
            eq(existingBookId.toString()),
            eq("nyt_list_young_adult_hardcover"),
            eq("NYT List: Young Adult Hardcover"),
            eq("NYT"),
            eq(1.0),
            org.mockito.ArgumentMatchers.argThat(metadata ->
                metadata != null
                    && "young-adult-hardcover".equals(metadata.get("list_code"))
                    && "Young Adult Hardcover".equals(metadata.get("list_display_name"))
                    && "WEEKLY".equals(metadata.get("updated_frequency"))
                    && "Mapped description".equals(metadata.get("description"))
                    && "Mapped Author".equals(metadata.get("author"))
                    && "nyt://book/mapped".equals(metadata.get("book_uri"))
                    && "https://amazon.example/mapped-direct".equals(metadata.get("amazon_product_url"))
                    && "*".equals(metadata.get("asterisk"))
                    && "\u2020".equals(metadata.get("dagger"))
                    && "2026-01-28 04:00:00".equals(metadata.get("created_date"))
                    && "2026-01-31 04:00:00".equals(metadata.get("updated_date"))
                    && metadata.get("isbns") instanceof List<?> isbnEntries
                    && !isbnEntries.isEmpty()
                    && isbnEntries.get(0) instanceof Map<?, ?> firstIsbn
                    && "9780316769488".equals(firstIsbn.get("isbn13"))
                    && "0316769487".equals(firstIsbn.get("isbn10"))
            )
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldMapExtendedExternalIdentifierLinks_WhenCreatingCanonicalBook() throws Exception {
        UUID createdBookId = UUID.randomUUID();
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [
                  {
                    "list_name_encoded": "hardcover-fiction",
                    "display_name": "Hardcover Fiction",
                    "books": [
                      {
                        "title": "Newly Created",
                        "description": "Fresh NYT description",
                        "publisher": "NYT Publisher",
                        "author": "NYT Author",
                        "primary_isbn13": "9780140177398",
                        "book_review_link": "https://nytimes.example/review/new",
                        "first_chapter_link": "https://nytimes.example/chapter/new",
                        "article_chapter_link": "https://nytimes.example/article/new",
                        "book_uri": "nyt://book/new",
                        "amazon_product_url": "https://amazon.example/new",
                        "book_image": "https://nytimes.example/new.jpg",
                        "rank": 1
                      }
                    ]
                  }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("Hardcover Fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            nullable(String.class),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780140177398", null)).thenReturn(null);
        when(bookUpsertService.upsert(any(BookAggregate.class))).thenReturn(
            BookUpsertService.UpsertResult.builder()
                .bookId(createdBookId)
                .slug("newly-created")
                .isNew(true)
                .build()
        );

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        org.mockito.ArgumentCaptor<BookAggregate> aggregateCaptor = org.mockito.ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService, times(1)).upsert(aggregateCaptor.capture());
        BookAggregate capturedAggregate = aggregateCaptor.getValue();

        assertThat(capturedAggregate.getDescription()).isEqualTo("Fresh NYT description");
        assertThat(capturedAggregate.getCategories()).containsExactly("NYT Hardcover Fiction");
        assertThat(capturedAggregate.getIdentifiers().getSource()).isEqualTo("NEW_YORK_TIMES");
        assertThat(capturedAggregate.getIdentifiers().getPurchaseLink()).isEqualTo("https://amazon.example/new");
        assertThat(capturedAggregate.getIdentifiers().getInfoLink()).isEqualTo("https://nytimes.example/review/new");
        assertThat(capturedAggregate.getIdentifiers().getPreviewLink()).isEqualTo("https://nytimes.example/chapter/new");
        assertThat(capturedAggregate.getIdentifiers().getWebReaderLink()).isEqualTo("https://nytimes.example/article/new");
        assertThat(capturedAggregate.getIdentifiers().getCanonicalVolumeLink()).isEqualTo("nyt://book/new");
        assertThat(capturedAggregate.getIdentifiers().getImageLinks())
            .containsEntry("thumbnail", "https://nytimes.example/new.jpg");
        verify(collectionPersistenceService, times(1)).upsertBestsellerMembership(
            eq("collection-1"),
            eq(createdBookId.toString()),
            eq(1),
            eq((Integer) null),
            eq((Integer) null),
            eq(1),
            eq("9780140177398"),
            eq((String) null),
            eq("https://amazon.example/new"),
            any(String.class)
        );
        verify(supplementalPersistenceService, times(2)).assignTag(
            eq(createdBookId.toString()),
            any(String.class),
            any(String.class),
            eq("NYT"),
            eq(1.0),
            anyMap()
        );
        verify(jdbcTemplate, never()).update(
            org.mockito.ArgumentMatchers.contains("UPDATE books"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(UUID.class)
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldSkipExternalIdentifierInsert_WhenExistingNytExternalRowWasUpdated() throws Exception {
        UUID existingBookId = UUID.randomUUID();
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [
                  {
                    "list_name_encoded": "hardcover-fiction",
                    "display_name": "Hardcover Fiction",
                    "books": [
                      {
                        "title": "Existing External Id",
                        "description": "Existing row should update without insert",
                        "publisher": "Pub",
                        "primary_isbn13": "9780316769488",
                        "book_uri": "nyt://book/existing",
                        "book_review_link": "https://nytimes.example/review/existing",
                        "rank": 1
                      }
                    ]
                  }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("Hardcover Fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            nullable(String.class),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780316769488", null)).thenReturn(existingBookId.toString());
        lenient().when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("UPDATE book_external_ids"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq(existingBookId)
        )).thenReturn(1);

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        verify(jdbcTemplate, times(1)).update(
            org.mockito.ArgumentMatchers.contains("UPDATE book_external_ids"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq(existingBookId)
        );
        verify(jdbcTemplate, never()).update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO book_external_ids"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldResolveIsbnFromIsbnArray_WhenPrimaryIsbnFieldsMissing() throws Exception {
        UUID existingBookId = UUID.randomUUID();
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [
                  {
                    "list_name_encoded": "hardcover-fiction",
                    "display_name": "Hardcover Fiction",
                    "books": [
                      {
                        "title": "Array ISBN Book",
                        "description": "Book with only isbns[] payload",
                        "publisher": "Array Pub",
                        "isbns": [{"isbn10":"0316769487","isbn13":"9780316769488"}],
                        "rank": 1
                      }
                    ]
                  }
                ]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class))).thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            nullable(String.class),
            eq("hardcover-fiction"),
            eq("Hardcover Fiction"),
            eq("hardcover-fiction"),
            nullable(String.class),
            nullable(LocalDate.class),
            eq(LocalDate.parse("2026-02-08")),
            nullable(String.class),
            any(JsonNode.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780316769488", "0316769487")).thenReturn(existingBookId.toString());

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        verify(bookLookupService, times(1)).resolveCanonicalBookId("9780316769488", "0316769487");
        verify(collectionPersistenceService, times(1)).upsertBestsellerMembership(
            eq("collection-1"),
            eq(existingBookId.toString()),
            eq(1),
            eq((Integer) null),
            eq((Integer) null),
            eq(1),
            eq("9780316769488"),
            eq("0316769487"),
            eq((String) null),
            any(String.class)
        );
    }
}
