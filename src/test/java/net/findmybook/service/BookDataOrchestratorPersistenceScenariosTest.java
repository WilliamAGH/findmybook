package net.findmybook.service;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.findmybook.domain.book.BookDescriptionEnrichmentException;
import net.findmybook.dto.BookAggregate;
import net.findmybook.dto.BookDetail;
import net.findmybook.model.Book;
import net.findmybook.support.search.GoogleExternalSearchFlow;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookDataOrchestratorPersistenceScenariosTest {

    private static final UUID ENRICHMENT_BOOK_ID = UUID.fromString("019cb5e1-d100-719e-8194-f6f5af0af7a8");
    private static final String ENRICHMENT_ISBN_13 = "9781234567890";
    private static final String SHORT_DESCRIPTION = "short description";

    @Mock
    private BookSearchService bookSearchService;

    @Mock
    private net.findmybook.mapper.GoogleBooksMapper googleBooksMapper;

    @Mock
    private BookUpsertService bookUpsertService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookExternalBatchPersistenceService batchPersistenceService;

    private PostgresBookRepository postgresBookRepository;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        postgresBookRepository = new PostgresBookRepository(jdbcTemplate, om);
        batchPersistenceService =
            new BookExternalBatchPersistenceService(om, googleBooksMapper, bookUpsertService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2020-01-01", "1997-01-01"})
    void should_PreserveFallbackFieldsAndUtcDate_When_MapperReturnsNull(String publishedDate) {
        when(bookUpsertService.upsert(any())).thenReturn(BookUpsertService.UpsertResult.builder()
            .bookId(UUID.randomUUID())
            .build());

        Book fallback = new Book();
        fallback.setId("OL123M");
        fallback.setTitle("Fallback Title");
        fallback.setAuthors(List.of("Author Example"));
        fallback.setCategories(List.of("Fiction"));
        fallback.setDescription("Fallback summary");
        fallback.setExternalImageUrl("https://example.com/cover.jpg");
        fallback.setRetrievedFrom("OPEN_LIBRARY");
        fallback.setLanguage("en");
        fallback.setPublisher("Fallback Publishing");
        fallback.setPageCount(321);
        fallback.setPublishedDate(Date.from(Instant.parse(publishedDate + "T00:00:00Z")));

        boolean persisted = batchPersistenceService.persistBook(fallback, null, null);

        assertThat(persisted).isTrue();
        ArgumentCaptor<BookAggregate> aggregateCaptor = ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService).upsert(aggregateCaptor.capture());

        BookAggregate aggregate = aggregateCaptor.getValue();
        assertThat(aggregate.getTitle()).isEqualTo("Fallback Title");
        assertThat(aggregate.getAuthors()).containsExactly("Author Example");
        assertThat(aggregate.getDescription()).isEqualTo("Fallback summary");
        assertThat(aggregate.getLanguage()).isEqualTo("en");
        assertThat(aggregate.getPublisher()).isEqualTo("Fallback Publishing");
        assertThat(aggregate.getPageCount()).isEqualTo(321);
        assertThat(aggregate.getPublishedDate()).isEqualTo(LocalDate.parse(publishedDate));
        assertThat(aggregate.getCategories()).containsExactly("Fiction");
        assertThat(aggregate.getIdentifiers().getSource()).isEqualTo("OPEN_LIBRARY");
        assertThat(aggregate.getIdentifiers().getImageLinks()).containsEntry("thumbnail", "https://example.com/cover.jpg");
    }

    @Test
    void should_PreserveDistinctProviderRecords_When_TitleAndAuthorsMatchWithoutAnIsbn() {
        Book googleBook = providerBook("shared-title", "GOOGLE_BOOKS");
        Book openLibraryBook = providerBook("shared-title", "OPEN_LIBRARY");
        Book duplicateGoogleBook = providerBook("shared-title", "GOOGLE_BOOKS");

        List<Book> deduplicatedBooks = batchPersistenceService.deduplicateByIdentifiers(
            List.of(googleBook, openLibraryBook, duplicateGoogleBook)
        );

        assertThat(deduplicatedBooks).containsExactly(googleBook, openLibraryBook);
    }

    @Test
    void should_DeduplicateEquivalentIsbnForms_When_ProvidersUseIsbn10AndIsbn13() {
        Book isbn13Book = providerBook("google-isbn13", "GOOGLE_BOOKS");
        isbn13Book.setIsbn13("9780306406157");
        Book isbn10Book = providerBook("open-library-isbn10", "OPEN_LIBRARY");
        isbn10Book.setIsbn10("0306406152");

        List<Book> deduplicatedBooks = batchPersistenceService.deduplicateByIdentifiers(
            List.of(isbn13Book, isbn10Book)
        );

        assertThat(deduplicatedBooks).containsExactly(isbn13Book);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void should_ReuseSuccessfulCandidates_When_UnchangedDescriptionIsEnrichedTwice(boolean hasCandidate) {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        when(openLibrary.isAvailable()).thenReturn(true);
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        AtomicInteger openLibrarySubscriptions = new AtomicInteger();
        Book candidate = providerBook("short-description", "OPEN_LIBRARY");
        candidate.setIsbn13(ENRICHMENT_ISBN_13);
        candidate.setDescription(SHORT_DESCRIPTION);
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.<Book>defer(() -> {
                openLibrarySubscriptions.incrementAndGet();
                return hasCandidate ? Flux.just(candidate) : Flux.empty();
            }));
        configureGoogleFallback(googleApiFetcher);
        when(googleApiFetcher.streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(false)))
            .thenReturn(Flux.empty());

        BookDataOrchestrator enrichment = enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(googleApiFetcher));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(enrichment.enrichDescriptionForAiIfNeeded(
                ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50)).isEqualTo(SHORT_DESCRIPTION);
        }
        assertThat(openLibrarySubscriptions.get()).isEqualTo(1);
        verify(googleApiFetcher).streamSearchItems(
            anyString(), anyInt(), anyString(), isNull(), eq(false));
        verify(bookUpsertService, never()).upsert(any());
    }

    @Test
    void should_ReturnCurrentDescription_When_OpenLibraryFailsAndGoogleReturnsNoCandidates() {
        BookDataOrchestrator enrichment = enrichmentOrchestrator(
            Flux.error(new IllegalStateException("Open Library unavailable")), Flux.empty());

        assertThat(enrichment.enrichDescriptionForAiIfNeeded(
            ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50)).isEqualTo(SHORT_DESCRIPTION);
    }

    @Test
    void should_PreserveSecondaryFailure_When_AllProvidersFail() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        when(openLibrary.isAvailable()).thenReturn(true);
        IllegalStateException openLibraryFailure = new IllegalStateException("Open Library unavailable");
        IllegalStateException authenticatedFailure = new IllegalStateException("Authenticated Google Books unavailable");
        IllegalStateException fallbackFailure = new IllegalStateException("Unauthenticated Google Books unavailable");
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.<Book>error(openLibraryFailure).delaySubscription(Duration.ofMillis(25)));
        GoogleApiFetcher googleApiFetcher = failingAuthenticatedGoogle(authenticatedFailure, true);
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(false)))
            .thenReturn(Flux.error(fallbackFailure));

        assertThatThrownBy(() -> enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50))
            .isSameAs(openLibraryFailure)
            .satisfies(failure -> {
                assertThat(failure.getSuppressed()).contains(authenticatedFailure);
                assertThat(authenticatedFailure.getSuppressed()).contains(fallbackFailure);
            });
    }

    @Test
    void should_ReturnCurrentDescription_When_GoogleFallbackIsDisabled() {
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(false);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);

        String description = enrichmentOrchestrator(Optional.empty(), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50);

        assertThat(description).isEqualTo(SHORT_DESCRIPTION);
    }

    @Test
    void should_NotCacheOpenLibraryFailure_When_GoogleFallbackIsDisabled() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        when(openLibrary.isAvailable()).thenReturn(true);
        IllegalStateException openLibraryFailure = new IllegalStateException("Open Library unavailable");
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.error(openLibraryFailure));

        BookDataOrchestrator enrichment = enrichmentOrchestrator(Optional.of(openLibrary), Optional.empty());
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> enrichment.enrichDescriptionForAiIfNeeded(
                ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50)).isSameAs(openLibraryFailure);
        }
        verify(openLibrary, times(2)).queryBooksByEverything(anyString(), anyString(), eq(0), anyInt());
    }

    @Test
    void should_NotCacheAuthenticatedGoogleFailure_When_OpenLibraryIsDisabled() {
        RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
        registry.rateLimiter(OpenLibraryBookDataService.RATE_LIMITER_NAME);
        OpenLibraryBookDataService openLibrary = spy(new OpenLibraryBookDataService(
            WebClient.builder(), "https://openlibrary.org", false, registry));
        IllegalStateException authenticatedFailure = new IllegalStateException("Google Books unavailable");
        GoogleApiFetcher googleApiFetcher = failingAuthenticatedGoogle(authenticatedFailure, false);
        BookDataOrchestrator enrichment = enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(googleApiFetcher));
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> enrichment.enrichDescriptionForAiIfNeeded(
                    ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50))
                    .isSameAs(authenticatedFailure);
            }
            verify(googleApiFetcher, times(2)).streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(true));
            verify(googleApiFetcher, never()).streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(false));
            verify(openLibrary, never()).queryBooksByEverything(anyString(), anyString(), eq(0), anyInt());
        } finally {
            openLibrary.stopAdmission();
        }
    }

    @Test
    void should_PreserveAuthenticatedGoogleFailure_When_FallbackCircuitIsOpen() {
        IllegalStateException failure = new IllegalStateException("Google Books unavailable");
        GoogleApiFetcher google = failingAuthenticatedGoogle(failure, true);
        when(google.isFallbackAllowed()).thenReturn(false);
        GoogleExternalSearchFlow flow = new GoogleExternalSearchFlow(Optional.of(google), Optional.of(googleBooksMapper));
        assertThatThrownBy(() -> flow.streamCandidates("isbn:" + ENRICHMENT_ISBN_13, "relevance", null, 6)
            .collectList().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fallback is unavailable")
            .hasCause(failure);
        verify(google, never()).streamSearchItems(anyString(), anyInt(), anyString(), isNull(), eq(false));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void should_RetryOpenLibraryOnce_When_TransientFailureOccurs(boolean outagePersists) {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        when(openLibrary.isAvailable()).thenReturn(true);
        WebClientResponseException transientFailure = providerFailure(HttpStatus.SERVICE_UNAVAILABLE);
        AtomicInteger openLibrarySubscriptions = new AtomicInteger();
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.<Book>defer(() -> openLibrarySubscriptions.incrementAndGet() == 1 || outagePersists
                ? Flux.error(transientFailure)
                : Flux.empty()));
        BookDataOrchestrator enrichment = enrichmentOrchestrator(Optional.of(openLibrary), Optional.empty());

        if (outagePersists) {
            assertThatThrownBy(() -> enrichment.enrichDescriptionForAiIfNeeded(
                ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50)).isSameAs(transientFailure);
        } else {
            assertThat(enrichment.enrichDescriptionForAiIfNeeded(
                ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50)).isEqualTo(SHORT_DESCRIPTION);
        }
        assertThat(openLibrarySubscriptions.get()).isEqualTo(2);
    }

    @Test
    void should_EnforceOneRequestWideDeadline_When_BothProvidersStall() {
        AtomicLong googleSubscribedAt = new AtomicLong();
        AtomicInteger cancellations = new AtomicInteger();
        BookDataOrchestrator enrichment = enrichmentOrchestrator(
            Flux.<Book>never().doOnCancel(cancellations::incrementAndGet),
            Flux.defer(() -> {
                googleSubscribedAt.set(System.nanoTime());
                return Flux.<JsonNode>never().doOnCancel(cancellations::incrementAndGet);
            }));

        long startedAtNanos = System.nanoTime();
        assertThatThrownBy(() -> enrichment.enrichDescriptionForAiIfNeeded(
            ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50))
            .isInstanceOf(BookDescriptionEnrichmentException.class)
            .hasMessageContaining("Open Library")
            .hasCauseInstanceOf(TimeoutException.class)
            .satisfies(failure -> assertThat(failure.getSuppressed())
                .anySatisfy(googleFailure -> assertThat(googleFailure)
                    .isInstanceOf(BookDescriptionEnrichmentException.class)
                    .hasMessageContaining("Google Books")
                    .hasCauseInstanceOf(TimeoutException.class)));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAtNanos)).isLessThan(Duration.ofSeconds(12));
        assertThat(googleSubscribedAt.get() - startedAtNanos).isBetween(0L, Duration.ofSeconds(2).toNanos());
        assertThat(cancellations.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void should_RetainCompletedDescription_When_OtherProviderStalls(boolean openLibraryStalls) {
        String description = "A complete matching description from the provider that responds before the deadline.";
        Book candidate = providerBook("open-library-description", "OPEN_LIBRARY");
        candidate.setIsbn13(ENRICHMENT_ISBN_13);
        candidate.setDescription(description);
        if (openLibraryStalls) {
            when(googleBooksMapper.map(any())).thenReturn(BookAggregate.builder()
                .title(candidate.getTitle())
                .isbn13(ENRICHMENT_ISBN_13)
                .description(description)
                .identifiers(BookAggregate.ExternalIdentifiers.builder().externalId("google-description").build())
                .build());
        }
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicLong lastSubscribedAt = new AtomicLong();
        BookDataOrchestrator enrichment = enrichmentOrchestrator(
            Flux.defer(() -> {
                subscriptions.incrementAndGet();
                lastSubscribedAt.set(System.nanoTime());
                return openLibraryStalls ? Flux.never() : Flux.just(candidate);
            }),
            Flux.defer(() -> {
                subscriptions.incrementAndGet();
                lastSubscribedAt.set(System.nanoTime());
                return openLibraryStalls
                    ? Flux.<JsonNode>just(new ObjectMapper().createObjectNode()).delayElements(Duration.ofMillis(25))
                    : Flux.never();
            }));
        when(bookUpsertService.upsert(any())).thenReturn(BookUpsertService.UpsertResult.builder()
            .bookId(ENRICHMENT_BOOK_ID)
            .build());
        when(bookSearchService.fetchBookDetail(ENRICHMENT_BOOK_ID)).thenReturn(Optional.of(enrichmentDetail(description)));

        long startedAtNanos = System.nanoTime();
        assertThat(enrichment.enrichDescriptionForAiIfNeeded(
            ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50)).isEqualTo(description);
        assertThat(subscriptions.get()).isEqualTo(2);
        assertThat(lastSubscribedAt.get() - startedAtNanos).isBetween(0L, Duration.ofSeconds(2).toNanos());
        ArgumentCaptor<BookAggregate> aggregate = ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService).upsert(aggregate.capture());
        assertThat(aggregate.getValue().getDescription()).isEqualTo(description);
    }

    @Test
    void should_CancelBothProviders_When_CallerIsInterrupted() throws InterruptedException {
        CountDownLatch subscribed = new CountDownLatch(2);
        CountDownLatch cancelled = new CountDownLatch(2);
        BookDataOrchestrator enrichment = enrichmentOrchestrator(
            Flux.<Book>never().doOnSubscribe(ignored -> subscribed.countDown()).doOnCancel(cancelled::countDown),
            Flux.<JsonNode>never().doOnSubscribe(ignored -> subscribed.countDown()).doOnCancel(cancelled::countDown));
        FutureTask<String> result = new FutureTask<>(() -> enrichment.enrichDescriptionForAiIfNeeded(
            ENRICHMENT_BOOK_ID, enrichmentDetail(""), SHORT_DESCRIPTION, 50));
        Thread caller = Thread.ofVirtual().start(result);
        try {
            assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(InterruptedException.class);
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            verify(bookUpsertService, never()).upsert(any());
        } finally {
            caller.interrupt();
            assertThat(caller.join(Duration.ofSeconds(2))).isTrue();
        }
    }

    @Test
    void isSystemicDatabaseError_detectsWrappedConnectionFailure() {
        java.net.ConnectException rootCause = new java.net.ConnectException("Connection refused");
        RuntimeException wrapped = new RuntimeException("Systemic database error during upsert", rootCause);

        boolean systemic = batchPersistenceService.isSystemicDatabaseError(wrapped);

        assertThat(systemic).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void should_ClassifyAndRejectFailedPersistence_When_UpsertFails(boolean duringShutdown) {
        when(googleBooksMapper.map(any())).thenReturn(BookAggregate.builder()
            .title("Failing Fixture")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-FAILED-1")
                .build())
            .build());
        RuntimeException failure = duringShutdown
            ? new DataAccessResourceFailureException("Connection closed")
            : new IllegalStateException("Cover persistence returned unsuccessful result for test-book");
        when(bookUpsertService.upsert(any())).thenThrow(failure);
        if (duringShutdown) {
            ReflectionTestUtils.invokeMethod(batchPersistenceService, "markShutdownInProgress");
        }
        Book incoming = providerBook("OL-FAILED-1", "OPEN_LIBRARY");

        assertThat(batchPersistenceService.persistBook(incoming, null, null)).isFalse();
        assertThat(batchPersistenceService.classifyBookUpsertFailureCode(failure))
            .isEqualTo(duringShutdown ? "BOOK_UPSERT_SYSTEMIC_DB" : "BOOK_UPSERT_NON_SYSTEMIC");
    }

    private GoogleApiFetcher failingAuthenticatedGoogle(RuntimeException failure, boolean fallbackEnabled) {
        GoogleApiFetcher google = mock(GoogleApiFetcher.class);
        when(google.isApiKeyAvailable()).thenReturn(true);
        when(google.isGoogleFallbackEnabled()).thenReturn(fallbackEnabled);
        when(google.streamSearchItems(anyString(), anyInt(), anyString(), isNull(), eq(true)))
            .thenReturn(Flux.error(failure));
        return google;
    }

    private BookDataOrchestrator enrichmentOrchestrator(Flux<Book> openLibraryCandidates, Flux<JsonNode> googleCandidates) {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        when(openLibrary.isAvailable()).thenReturn(true);
        GoogleApiFetcher google = mock(GoogleApiFetcher.class);
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt())).thenReturn(openLibraryCandidates);
        configureGoogleFallback(google);
        when(google.streamSearchItems(anyString(), anyInt(), anyString(), isNull(), eq(false))).thenReturn(googleCandidates);
        return enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(google));
    }

    private BookDataOrchestrator enrichmentOrchestrator(Optional<OpenLibraryBookDataService> openLibrary,
                                                         Optional<GoogleApiFetcher> googleApiFetcher) {
        return new BookDataOrchestrator(
            bookSearchService,
            postgresBookRepository,
            batchPersistenceService,
            openLibrary,
            googleApiFetcher,
            Optional.of(googleBooksMapper),
            bookUpsertService
        );
    }

    private Book providerBook(String providerId, String providerSource) {
        Book book = new Book();
        book.setId(providerId);
        book.setDataSource(providerSource);
        book.setTitle("Shared Title");
        book.setAuthors(List.of("Shared Author"));
        return book;
    }

    private void configureGoogleFallback(GoogleApiFetcher googleApiFetcher) {
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(true);
    }

    private BookDetail enrichmentDetail(String description) {
        return new BookDetail(
            ENRICHMENT_BOOK_ID.toString(), "canonical-title", "Canonical title", description, "Canonical publisher",
            LocalDate.of(2020, 1, 1), "en", 250, List.of("Canonical author"), List.of("Fiction"),
            "https://example.com/cover.jpg", "covers/canonical.jpg", "https://example.com/fallback.jpg",
            "https://example.com/thumbnail.jpg", 600, 900, true, "GOOGLE_BOOKS", 4.0, 10,
            "1234567890", ENRICHMENT_ISBN_13, "https://example.com/preview", "https://example.com/info",
            java.util.Map.of("source", "test"), List.of()
        );
    }

    private WebClientResponseException providerFailure(HttpStatus status) {
        return WebClientResponseException.create(
            status.value(), "provider failure", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8
        );
    }
}
