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
import org.springframework.jdbc.core.RowMapper;
import reactor.core.publisher.Mono;

import java.sql.ResultSet;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

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

    private NytBestsellerPersistenceCollaborator persistenceCollaborator;
    private NewYorkTimesBestsellerScheduler scheduler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        NytBestsellerPayloadMapper payloadMapper = new NytBestsellerPayloadMapper(objectMapper);
        persistenceCollaborator = new NytBestsellerPersistenceCollaborator(
            jdbcTemplate,
            supplementalPersistenceService,
            payloadMapper,
            bookLookupService,
            bookUpsertService
        );
        NewYorkTimesBestsellerScheduler.NytIngestServices services = new NewYorkTimesBestsellerScheduler.NytIngestServices(
            newYorkTimesService,
            jdbcTemplate,
            collectionPersistenceService,
            payloadMapper,
            persistenceCollaborator
        );
        NewYorkTimesBestsellerScheduler.SchedulerConfig config = new NewYorkTimesBestsellerScheduler.SchedulerConfig(true, true, true);
        scheduler = new NewYorkTimesBestsellerScheduler(services, config);
        lenient().when(jdbcTemplate.update(
            anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);
    }

    @Test
    void should_UseAtomicUpsertStatement_When_UpsertingNytExternalIdentifiers() throws Exception {
        JsonNode bookNode = objectMapper.readTree(
            """
            {
              "book_review_link": "https://example.com/review",
              "first_chapter_link": "https://example.com/preview",
              "amazon_product_url": "https://example.com/buy",
              "book_uri": "https://example.com/book"
            }
            """
        );

        persistenceCollaborator.upsertNytExternalIdentifiers(
            UUID.randomUUID().toString(),
            bookNode,
            "https://example.com/book",
            "9780316769488",
            "0316769487"
        );

        verify(jdbcTemplate, times(1)).update(
            org.mockito.ArgumentMatchers.contains("ON CONFLICT (source, external_id) DO UPDATE"),
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
    void should_FailIngest_When_OverviewListsContainNoBooks() throws Exception {
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.of("collection-1"));

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            scheduler::processNewYorkTimesBestsellers
        );

        assertThat(thrown.getMessage()).contains("no usable lists");
        verify(collectionPersistenceService).upsertBestsellerCollection(
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        );
        verifyNoInteractions(bookLookupService, supplementalPersistenceService, bookUpsertService);
    }

    @Test
    void should_FailIngest_When_NytOverviewIsNullOrEmpty() {
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class)))
            .thenReturn(Mono.empty())
            .thenReturn(Mono.just(objectMapper.createObjectNode()));

        IllegalStateException nullOverviewFailure = assertThrows(
            IllegalStateException.class,
            () -> scheduler.forceProcessNewYorkTimesBestsellers()
        );
        IllegalStateException emptyOverviewFailure = assertThrows(
            IllegalStateException.class,
            () -> scheduler.forceProcessNewYorkTimesBestsellers()
        );

        assertThat(nullOverviewFailure.getMessage()).contains("returned no data");
        assertThat(emptyOverviewFailure.getMessage()).contains("returned no data");
        verifyNoInteractions(collectionPersistenceService);
    }

    @Test
    void should_FailIngest_When_JdbcTemplateIsUnavailable() {
        NytBestsellerPayloadMapper payloadMapper = new NytBestsellerPayloadMapper(objectMapper);
        NewYorkTimesBestsellerScheduler schedulerWithoutJdbc = new NewYorkTimesBestsellerScheduler(
            new NewYorkTimesBestsellerScheduler.NytIngestServices(
                newYorkTimesService,
                null,
                collectionPersistenceService,
                payloadMapper,
                persistenceCollaborator
            ),
            new NewYorkTimesBestsellerScheduler.SchedulerConfig(true, true, true)
        );

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> schedulerWithoutJdbc.forceProcessNewYorkTimesBestsellers()
        );

        assertThat(thrown.getMessage()).contains("JdbcTemplate unavailable");
        verifyNoInteractions(newYorkTimesService, collectionPersistenceService);
    }

    @Test
    void should_FailIngest_When_NoBookCanLandABestsellerMembership() throws Exception {
        JsonNode overview = objectMapper.readTree(
            """
            {
              "results": {
                "lists": [{
                  "list_name_encoded": "hardcover-fiction",
                  "books": [{"title":"Unstable", "primary_isbn13":"invalid"}]
                }]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class)))
            .thenReturn(Mono.just(overview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.of("collection-1"));

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> scheduler.forceProcessNewYorkTimesBestsellers()
        );

        assertThat(thrown.getMessage()).contains("persisted zero bestseller memberships from 1 processed entries");
        verify(collectionPersistenceService, never()).upsertBestsellerMembership(
            any(BookCollectionPersistenceService.BestsellerMembershipDto.class)
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldFailList_When_CollectionUpsertReturnsEmpty() throws Exception {
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.empty());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> scheduler.processNewYorkTimesBestsellers());

        assertThat(thrown.getMessage()).contains("1 of 1 list(s) failed");
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenThrow(new IllegalStateException("collection upsert failure"))
            .thenReturn(Optional.of("collection-2"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> scheduler.processNewYorkTimesBestsellers());
        assertThat(thrown.getMessage()).contains("1 of 2 list(s) failed");

        verify(collectionPersistenceService, times(2)).upsertBestsellerCollection(
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780316769488", null)).thenReturn(null);
        when(bookLookupService.resolveCanonicalBookId("9780140177398", null)).thenReturn(existingBookId.toString());
        when(bookUpsertService.upsert(any(BookAggregate.class)))
            .thenThrow(new IllegalStateException("book upsert failure"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> scheduler.processNewYorkTimesBestsellers());
        assertThat(thrown.getMessage()).contains("1 of 1 list(s) failed");

        verify(collectionPersistenceService, times(1)).upsertBestsellerMembership(
            any(BookCollectionPersistenceService.BestsellerMembershipDto.class)
        );
    }

    @Test
    void processNewYorkTimesBestsellers_shouldSkipUnstableIdentityAndProcessUriOrIsbnEntries() throws Exception {
        UUID existingBookId = UUID.randomUUID();
        UUID uriOnlyBookId = UUID.randomUUID();
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
                      { "title": "URI only", "book_uri": "nyt://book/uri-only", "rank": 1 },
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId(null, null)).thenReturn(null);
        when(bookLookupService.resolveCanonicalBookId(null, "0140177396")).thenReturn(existingBookId.toString());
        when(bookUpsertService.upsert(any(BookAggregate.class))).thenReturn(
            BookUpsertService.UpsertResult.builder()
                .bookId(uriOnlyBookId)
                .slug("uri-only")
                .isNew(true)
                .build()
        );

        NewYorkTimesBestsellerScheduler.NytIngestSummary summary =
            scheduler.processNewYorkTimesBestsellers((LocalDate) null);

        assertThat(summary.executed()).isTrue();
        assertThat(summary.totalLists()).isEqualTo(1);
        assertThat(summary.usableLists()).isEqualTo(1);
        assertThat(summary.processedEntries()).isEqualTo(3);
        assertThat(summary.persistedMemberships()).isEqualTo(2);
        assertThat(summary.hasValidatedIngestion()).isTrue();
        org.mockito.ArgumentCaptor<BookAggregate> aggregateCaptor = org.mockito.ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService, times(1)).upsert(aggregateCaptor.capture());
        assertThat(aggregateCaptor.getValue().getIdentifiers().getExternalId()).isEqualTo("nyt://book/uri-only");
        verify(bookLookupService, times(1)).resolveCanonicalBookId(null, null);
        verify(bookLookupService, times(1)).resolveCanonicalBookId(null, "0140177396");
        verify(collectionPersistenceService, times(2)).upsertBestsellerMembership(
            any(BookCollectionPersistenceService.BestsellerMembershipDto.class)
        );
    }

    @Test
    void should_PreserveProviderMappedBook_When_RerunUsesIsbnFromDifferentBook() throws Exception {
        UUID providerMappedBookId = UUID.randomUUID();
        UUID changedIsbnBookId = UUID.randomUUID();
        String bookUri = "nyt://book/stable-rerun-identity";
        String initialIsbn13 = "9780316769488";
        String changedIsbn13 = "9780140177398";
        JsonNode initialOverview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [{
                  "list_name_encoded": "hardcover-fiction",
                  "display_name": "Hardcover Fiction",
                  "books": [{
                    "title": "Stable NYT Identity",
                    "primary_isbn13": "9780316769488",
                    "book_uri": "nyt://book/stable-rerun-identity",
                    "rank": 1
                  }]
                }]
              }
            }
            """
        );
        JsonNode rerunOverview = objectMapper.readTree(
            """
            {
              "results": {
                "published_date": "2026-02-08",
                "lists": [{
                  "list_name_encoded": "hardcover-fiction",
                  "display_name": "Hardcover Fiction",
                  "books": [{
                    "title": "Stable NYT Identity",
                    "primary_isbn13": "9780140177398",
                    "book_uri": "nyt://book/stable-rerun-identity",
                    "rank": 1
                  }]
                }]
              }
            }
            """
        );
        when(newYorkTimesService.fetchBestsellerListOverview(nullable(LocalDate.class)))
            .thenReturn(Mono.just(initialOverview))
            .thenReturn(Mono.just(rerunOverview));
        when(collectionPersistenceService.upsertBestsellerCollection(
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.of("collection-1"));
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Object>>any(),
            anyString()
        )).thenReturn(null);
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("FROM book_external_ids"),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            eq(bookUri), eq(bookUri), eq(bookUri),
            nullable(String.class), nullable(String.class), nullable(String.class),
            nullable(String.class), nullable(String.class), nullable(String.class),
            eq(bookUri), eq(bookUri)
        )).thenReturn(List.of()).thenAnswer(invocation -> {
            RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getString("id")).thenReturn("nyt-stable-rerun-identity");
            when(resultSet.getObject("book_id", UUID.class)).thenReturn(providerMappedBookId);
            when(resultSet.getString("external_id")).thenReturn(bookUri);
            when(resultSet.getString("canonical_volume_link")).thenReturn(bookUri);
            return List.of(rowMapper.mapRow(resultSet, 0));
        });
        when(bookLookupService.resolveCanonicalBookId(any(), nullable(String.class)))
            .thenAnswer(invocation -> changedIsbn13.equals(invocation.getArgument(0, String.class))
                ? changedIsbnBookId.toString()
                : providerMappedBookId.toString());

        scheduler.processNewYorkTimesBestsellers();
        scheduler.processNewYorkTimesBestsellers();

        org.mockito.ArgumentCaptor<BookCollectionPersistenceService.BestsellerMembershipDto> membershipCaptor =
            org.mockito.ArgumentCaptor.forClass(BookCollectionPersistenceService.BestsellerMembershipDto.class);
        verify(collectionPersistenceService, times(2)).upsertBestsellerMembership(membershipCaptor.capture());
        assertThat(membershipCaptor.getAllValues())
            .extracting(BookCollectionPersistenceService.BestsellerMembershipDto::bookId)
            .containsExactly(providerMappedBookId.toString(), providerMappedBookId.toString())
            .doesNotContain(changedIsbnBookId.toString());
        assertThat(membershipCaptor.getAllValues())
            .extracting(BookCollectionPersistenceService.BestsellerMembershipDto::providerIsbn13)
            .containsExactly(initialIsbn13, changedIsbn13);
        verify(bookLookupService, times(1)).resolveCanonicalBookId(initialIsbn13, null);
        verify(bookLookupService, never()).resolveCanonicalBookId(changedIsbn13, null);
        verifyNoInteractions(bookUpsertService);
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
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
            eq("nyt://book/mapped"),
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
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
            any(BookCollectionPersistenceService.BestsellerMembershipDto.class)
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780316769488", null)).thenReturn(existingBookId.toString());

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        verify(jdbcTemplate, times(1)).update(
            org.mockito.ArgumentMatchers.contains("ON CONFLICT (source, external_id) DO UPDATE"),
            any(),
            eq(existingBookId),
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
            any(BookCollectionPersistenceService.BestsellerCollectionDto.class)
        )).thenReturn(Optional.of("collection-1"));
        when(bookLookupService.resolveCanonicalBookId("9780316769488", "0316769487")).thenReturn(existingBookId.toString());

        assertDoesNotThrow(() -> scheduler.processNewYorkTimesBestsellers());

        verify(bookLookupService, times(1)).resolveCanonicalBookId("9780316769488", "0316769487");
        verify(collectionPersistenceService, times(1)).upsertBestsellerMembership(
            any(BookCollectionPersistenceService.BestsellerMembershipDto.class)
        );
    }
}
