package net.findmybook.service;

import tools.jackson.databind.ObjectMapper;
import net.findmybook.dto.BookAggregate;
import net.findmybook.dto.BookDetail;
import net.findmybook.model.Book;
import net.findmybook.util.ApplicationConstants;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers retrofit checklist scenarios for dedupe and edition-chaining behavior.
 * See docs/task-retrofit-code-for-postgres-schema.md.
 */
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

    private BookDataOrchestrator orchestrator;
    private BookExternalBatchPersistenceService batchPersistenceService;

    // Concrete dependencies instantiated in setUp()
    private PostgresBookRepository postgresBookRepository;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        postgresBookRepository = new PostgresBookRepository(jdbcTemplate, om);
        batchPersistenceService =
            new BookExternalBatchPersistenceService(om, googleBooksMapper, bookUpsertService);

        orchestrator = new BookDataOrchestrator(
                bookSearchService,
                postgresBookRepository,
                batchPersistenceService,
                Optional.empty(),
                Optional.empty(),
                Optional.of(googleBooksMapper),
                bookUpsertService
        );
        lenient().when(bookSearchService.searchBooks(anyString(), any())).thenReturn(List.of());
        lenient().when(bookSearchService.searchByIsbn(anyString())).thenReturn(java.util.Optional.empty());
        lenient().when(bookSearchService.searchAuthors(anyString(), any())).thenReturn(List.of());
        lenient().doNothing().when(bookSearchService).refreshMaterializedView();

        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any()))
                .thenThrow(new EmptyResultDataAccessException(1));
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
                .thenThrow(new EmptyResultDataAccessException(1));
    }

    @Test
    @Disabled("Method resolveCanonicalBookId was moved from BookDataOrchestrator to CanonicalBookPersistenceService (deprecated)")
    void resolveCanonicalBookId_prefersExistingExternalMapping() {
        whenExternalIdLookupReturns("existing-book-id");

        Book incoming = new Book();
        incoming.setId("temporary-id");
        incoming.setIsbn13("9781234567890");
        incoming.setIsbn10("1234567890");

        String resolved = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "resolveCanonicalBookId",
                incoming,
                "google-abc",
                "9781234567890",
                "1234567890"
        );

        assertThat(resolved).isEqualTo("existing-book-id");
    }

    @Test
    void persistBook_usesFallbackAggregateWhenMapperReturnsNull() {
        when(googleBooksMapper.map(any())).thenReturn(null);
        when(bookUpsertService.upsert(any())).thenReturn(BookUpsertService.UpsertResult.builder()
            .bookId(UUID.randomUUID())
            .slug("fallback-book")
            .isNew(true)
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
        fallback.setPublishedDate(Date.from(Instant.parse("2020-01-01T00:00:00Z")));

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
        assertThat(aggregate.getPublishedDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(aggregate.getCategories()).containsExactly("Fiction");
        assertThat(aggregate.getIdentifiers().getSource()).isEqualTo("OPEN_LIBRARY");
        assertThat(aggregate.getIdentifiers().getImageLinks()).containsEntry("thumbnail", "https://example.com/cover.jpg");
    }

    @Test
    void persistBook_mapsUtcPublishedDateWithoutLocalTimezoneDrift() {
        when(googleBooksMapper.map(any())).thenReturn(null);
        when(bookUpsertService.upsert(any())).thenReturn(BookUpsertService.UpsertResult.builder()
            .bookId(UUID.randomUUID())
            .slug("utc-book")
            .isNew(true)
            .build());

        Book fallback = new Book();
        fallback.setId("OLUTC1");
        fallback.setTitle("UTC Boundary Book");
        fallback.setAuthors(List.of("Author"));
        fallback.setExternalImageUrl("https://example.com/utc-cover.jpg");
        fallback.setRetrievedFrom("OPEN_LIBRARY");
        fallback.setPublishedDate(Date.from(Instant.parse("1997-01-01T00:00:00Z")));

        boolean persisted = batchPersistenceService.persistBook(fallback, null, null);

        assertThat(persisted).isTrue();
        ArgumentCaptor<BookAggregate> aggregateCaptor = ArgumentCaptor.forClass(BookAggregate.class);
        verify(bookUpsertService).upsert(aggregateCaptor.capture());
        BookAggregate aggregate = aggregateCaptor.getValue();
        assertThat(aggregate.getPublishedDate()).isEqualTo(LocalDate.of(1997, 1, 1));
    }

    @Test
    void should_ReturnCurrentDescription_When_BothProvidersReturnNoCandidates() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        AtomicInteger openLibrarySubscriptions = new AtomicInteger();
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.<Book>defer(() -> {
                openLibrarySubscriptions.incrementAndGet();
                return Flux.empty();
            }));
        configureGoogleFallback(googleApiFetcher);
        when(googleApiFetcher.streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(false)))
            .thenReturn(Flux.empty());

        String description = enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50);

        assertThat(description).isEqualTo(SHORT_DESCRIPTION);
        assertThat(openLibrarySubscriptions.get()).isEqualTo(1);
        verify(googleApiFetcher).streamSearchItems(
            anyString(), anyInt(), anyString(), isNull(), eq(false));
    }

    @Test
    void should_PropagateGoogleFailure_When_OpenLibraryReturnsNoCandidates() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        IllegalStateException googleFailure = new IllegalStateException("Google Books unavailable");
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.empty());
        configureGoogleFallback(googleApiFetcher);
        when(googleApiFetcher.streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(false)))
            .thenReturn(Flux.error(googleFailure));

        assertThatThrownBy(() -> enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50))
            .isSameAs(googleFailure);
        verify(googleApiFetcher).streamSearchItems(
            anyString(), anyInt(), anyString(), isNull(), eq(false));
    }

    @Test
    void should_PreserveSecondaryFailure_When_AllProvidersFail() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        IllegalStateException openLibraryFailure = new IllegalStateException("Open Library unavailable");
        IllegalStateException authenticatedFailure = new IllegalStateException("Authenticated Google Books unavailable");
        IllegalStateException fallbackFailure = new IllegalStateException("Unauthenticated Google Books unavailable");
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.error(openLibraryFailure));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(true);
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(true);
        when(googleApiFetcher.streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(true)))
            .thenReturn(Flux.error(authenticatedFailure));
        when(googleApiFetcher.streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(false)))
            .thenReturn(Flux.error(fallbackFailure));

        assertThatThrownBy(() -> enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50))
            .isSameAs(openLibraryFailure)
            .satisfies(failure -> {
                assertThat(failure.getSuppressed()).contains(authenticatedFailure);
                assertThat(authenticatedFailure.getSuppressed()).contains(fallbackFailure);
            });
        verify(googleApiFetcher).streamSearchItems(
            anyString(), anyInt(), anyString(), isNull(), eq(false));
    }

    @Test
    void should_ReturnCurrentDescription_When_GoogleFallbackIsDisabled() {
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(false);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);

        String description = enrichmentOrchestrator(Optional.empty(), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50);

        assertThat(description).isEqualTo(SHORT_DESCRIPTION);
    }

    @Test
    void should_PropagateOpenLibraryFailure_When_GoogleFallbackIsDisabled() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        IllegalStateException openLibraryFailure = new IllegalStateException("Open Library unavailable");
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.error(openLibraryFailure));
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(false);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(false);

        assertThatThrownBy(() -> enrichmentOrchestrator(Optional.of(openLibrary), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50))
            .isSameAs(openLibraryFailure);
    }

    @Test
    void should_PropagateAuthenticatedGoogleFailure_When_FallbackCircuitIsOpen() {
        GoogleApiFetcher googleApiFetcher = mock(GoogleApiFetcher.class);
        IllegalStateException authenticatedFailure = new IllegalStateException("Google Books unavailable");
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(true);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(true);
        when(googleApiFetcher.isFallbackAllowed()).thenReturn(false);
        when(googleApiFetcher.streamSearchItems(
                anyString(), anyInt(), anyString(), isNull(), eq(true)))
            .thenReturn(Flux.error(authenticatedFailure));

        assertThatThrownBy(() -> enrichmentOrchestrator(Optional.empty(), Optional.of(googleApiFetcher))
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fallback is unavailable")
            .hasCause(authenticatedFailure);
        verify(googleApiFetcher, never()).streamSearchItems(
            anyString(), anyInt(), anyString(), isNull(), eq(false));
    }

    @Test
    void should_RetryOpenLibraryOnce_When_TransientFailureThenEmptyResult() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        WebClientResponseException transientFailure = providerFailure(HttpStatus.SERVICE_UNAVAILABLE);
        AtomicInteger openLibrarySubscriptions = new AtomicInteger();
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.<Book>defer(() -> openLibrarySubscriptions.incrementAndGet() == 1
                ? Flux.error(transientFailure)
                : Flux.empty()));

        String description = enrichmentOrchestrator(Optional.of(openLibrary), Optional.empty())
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50);

        assertThat(description).isEqualTo(SHORT_DESCRIPTION);
        assertThat(openLibrarySubscriptions.get()).isEqualTo(2);
    }

    @Test
    void should_PreserveTransientFailure_When_OpenLibraryOutagePersists() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        WebClientResponseException transientFailure = providerFailure(HttpStatus.SERVICE_UNAVAILABLE);
        AtomicInteger openLibrarySubscriptions = new AtomicInteger();
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.<Book>defer(() -> {
                openLibrarySubscriptions.incrementAndGet();
                return Flux.error(transientFailure);
            }));

        assertThatThrownBy(() -> enrichmentOrchestrator(Optional.of(openLibrary), Optional.empty())
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50))
            .isSameAs(transientFailure);
        assertThat(openLibrarySubscriptions.get()).isEqualTo(2);
    }

    @Test
    void should_MapOpenLibraryTimeout_When_RetryBudgetIsExhausted() {
        OpenLibraryBookDataService openLibrary = mock(OpenLibraryBookDataService.class);
        TimeoutException timeoutFailure = new TimeoutException("Open Library timed out");
        AtomicInteger openLibrarySubscriptions = new AtomicInteger();
        when(openLibrary.queryBooksByEverything(anyString(), anyString(), eq(0), anyInt()))
            .thenReturn(Flux.<Book>defer(() -> {
                openLibrarySubscriptions.incrementAndGet();
                return Flux.error(timeoutFailure);
            }));

        assertThatThrownBy(() -> enrichmentOrchestrator(Optional.of(openLibrary), Optional.empty())
            .enrichDescriptionForAiIfNeeded(ENRICHMENT_BOOK_ID, enrichmentDetail(), SHORT_DESCRIPTION, 50))
            .isInstanceOf(IllegalStateException.class)
            .hasCause(timeoutFailure);
        assertThat(openLibrarySubscriptions.get()).isEqualTo(2);
    }

    @Test
    void isSystemicDatabaseError_detectsWrappedConnectionFailure() {
        java.net.ConnectException rootCause = new java.net.ConnectException("Connection refused");
        RuntimeException wrapped = new RuntimeException("Systemic database error during upsert", rootCause);

        boolean systemic = batchPersistenceService.isSystemicDatabaseError(wrapped);

        assertThat(systemic).isTrue();
    }

    @Test
    void persistBook_returnsFalseDuringShutdown_When_SystemicDatabaseErrorOccurs() {
        when(googleBooksMapper.map(any())).thenReturn(BookAggregate.builder()
            .title("Shutdown Fixture")
            .slugBase("shutdown-fixture")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-SHUTDOWN-1")
                .build())
            .build());
        when(bookUpsertService.upsert(any()))
            .thenThrow(new DataAccessResourceFailureException("Connection closed"));
        ReflectionTestUtils.invokeMethod(batchPersistenceService, "markShutdownInProgress");

        Book incoming = new Book();
        incoming.setId("OL-SHUTDOWN-1");
        incoming.setTitle("Shutdown Fixture");

        boolean persisted = batchPersistenceService.persistBook(incoming, null, null);

        assertThat(persisted).isFalse();
    }

    @Test
    void persistBook_returnsFalse_When_BookUpsertFailsWithNonSystemicException() {
        when(googleBooksMapper.map(any())).thenReturn(BookAggregate.builder()
            .title("Non Systemic Fixture")
            .slugBase("non-systemic-fixture")
            .identifiers(BookAggregate.ExternalIdentifiers.builder()
                .source("OPEN_LIBRARY")
                .externalId("OL-NON-SYSTEMIC-1")
                .build())
            .build());
        when(bookUpsertService.upsert(any()))
            .thenThrow(new IllegalStateException("Cover persistence returned unsuccessful result for test-book"));

        Book incoming = new Book();
        incoming.setId("OL-NON-SYSTEMIC-1");
        incoming.setTitle("Non Systemic Fixture");

        boolean persisted = batchPersistenceService.persistBook(incoming, null, null);

        assertThat(persisted).isFalse();
    }

    @Test
    void classifyBookUpsertFailureCode_returnsSystemicCode_When_DatabaseConnectivityFailureOccurs() {
        String code = batchPersistenceService.classifyBookUpsertFailureCode(
            new DataAccessResourceFailureException("Connection refused")
        );

        assertThat(code).isEqualTo("BOOK_UPSERT_SYSTEMIC_DB");
    }

    @Test
    void classifyBookUpsertFailureCode_returnsNonSystemicCode_When_DomainValidationFailureOccurs() {
        String code = batchPersistenceService.classifyBookUpsertFailureCode(
            new IllegalStateException("Cover persistence returned unsuccessful result")
        );

        assertThat(code).isEqualTo("BOOK_UPSERT_NON_SYSTEMIC");
    }

    private void whenExternalIdLookupReturns(String bookId) {
        lenient().when(jdbcTemplate.queryForObject(
                eq("SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1"),
                eq(String.class),
                eq(ApplicationConstants.Provider.GOOGLE_BOOKS),
                any()
        )).thenReturn(bookId);
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

    private void configureGoogleFallback(GoogleApiFetcher googleApiFetcher) {
        when(googleApiFetcher.isApiKeyAvailable()).thenReturn(false);
        when(googleApiFetcher.isGoogleFallbackEnabled()).thenReturn(true);
    }

    private BookDetail enrichmentDetail() {
        return new BookDetail(
            ENRICHMENT_BOOK_ID.toString(), "canonical-title", "Canonical title", "", "Canonical publisher",
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
