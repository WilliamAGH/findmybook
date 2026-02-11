package net.findmybook.controller;

import net.findmybook.application.cover.BackfillMode;
import net.findmybook.application.cover.BackfillProgress;
import net.findmybook.application.cover.CoverBackfillService;
import net.findmybook.application.cover.SourceAttemptStatus;
import net.findmybook.scheduler.BookCacheWarmingScheduler;
import net.findmybook.scheduler.NewYorkTimesBestsellerScheduler;
import net.findmybook.service.ApiCircuitBreakerService;
import net.findmybook.service.BackfillCoordinator;
import net.findmybook.service.S3CoverCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    private static final String TEST_S3_PREFIX = "images/book-covers/";
    private static final int TEST_BATCH_LIMIT = 100;
    private static final String TEST_QUARANTINE_PREFIX = "images/non-covers-pages/";

    @Mock
    private S3CoverCleanupService s3CoverCleanupService;

    @Mock
    private ObjectProvider<S3CoverCleanupService> s3CoverCleanupServiceProvider;

    @Mock
    private NewYorkTimesBestsellerScheduler newYorkTimesBestsellerScheduler;

    @Mock
    private BackfillCoordinator backfillCoordinator;

    @Mock
    private ObjectProvider<BackfillCoordinator> backfillCoordinatorProvider;

    @Mock
    private BookCacheWarmingScheduler bookCacheWarmingScheduler;

    @Mock
    private ApiCircuitBreakerService apiCircuitBreakerService;

    @Mock
    private CoverBackfillService coverBackfillService;

    private AdminController adminController;

    @BeforeEach
    void setUp() {
        when(s3CoverCleanupServiceProvider.getIfAvailable()).thenReturn(s3CoverCleanupService);
        when(backfillCoordinatorProvider.getIfAvailable()).thenReturn(backfillCoordinator);
        adminController = new AdminController(
            s3CoverCleanupServiceProvider,
            newYorkTimesBestsellerScheduler,
            backfillCoordinatorProvider,
            bookCacheWarmingScheduler,
            apiCircuitBreakerService,
            coverBackfillService,
            TEST_S3_PREFIX,
            TEST_BATCH_LIMIT,
            TEST_QUARANTINE_PREFIX
        );
    }

    @Test
    void triggerNytBestsellerProcessing_shouldReturnBadRequest_WhenSchedulerRejectsRequest() {
        doThrow(new IllegalStateException("NYT processing is disabled"))
            .when(newYorkTimesBestsellerScheduler)
            .processNewYorkTimesBestsellers();

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerNytBestsellerProcessing(null, false)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("NYT processing is disabled", exception.getReason());
    }

    @Test
    void triggerNytBestsellerProcessing_shouldPropagateUnexpectedFailure_WhenSchedulerFailsUnexpectedly() {
        doThrow(new RuntimeException("NYT upstream failed"))
            .when(newYorkTimesBestsellerScheduler)
            .processNewYorkTimesBestsellers();

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> adminController.triggerNytBestsellerProcessing(null, false)
        );

        assertEquals("NYT upstream failed", exception.getMessage());
    }

    @Test
    void triggerNytBestsellerProcessing_shouldReturnHistoricalSummary_WhenRerunAllIsTrue() {
        when(newYorkTimesBestsellerScheduler.rerunHistoricalBestsellers())
            .thenReturn(new NewYorkTimesBestsellerScheduler.HistoricalRerunSummary(
                3,
                3,
                0,
                List.of()
            ));

        var response = adminController.triggerNytBestsellerProcessing(null, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("totalDates=3"));
        assertTrue(response.getBody().contains("succeeded=3"));
        assertTrue(response.getBody().contains("failed=0"));
    }

    @Test
    void triggerNytBestsellerProcessing_shouldReturnBadRequest_WhenHistoricalRerunRejected() {
        doThrow(new IllegalStateException("NYT historical rerun is disabled"))
            .when(newYorkTimesBestsellerScheduler)
            .rerunHistoricalBestsellers();

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerNytBestsellerProcessing(null, true)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("NYT historical rerun is disabled", exception.getReason());
    }

    @Test
    void triggerNytBestsellerProcessing_shouldReturnBadRequest_WhenRerunAllAndDateProvided() {
        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerNytBestsellerProcessing(LocalDate.parse("2026-02-09"), true)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Cannot combine rerunAll=true with a specific publishedDate.", exception.getReason());
    }

    @Test
    void triggerNytBestsellerProcessing_shouldForceSpecificPublishedDate_WhenProvided() {
        LocalDate requestedDate = LocalDate.parse("2026-02-09");

        var response = adminController.triggerNytBestsellerProcessing(requestedDate, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("2026-02-09"));
        verify(newYorkTimesBestsellerScheduler).forceProcessNewYorkTimesBestsellers(requestedDate);
    }

    @Test
    void triggerS3CoverCleanupDryRun_shouldReturnInternalServerError_WhenUnexpectedFailureOccurs() {
        when(s3CoverCleanupService.performDryRun(anyString(), anyInt()))
            .thenThrow(new IllegalStateException("S3 list failed"));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerS3CoverCleanupDryRun(null, 25)
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        assertNotNull(exception.getReason(), "Reason should not be null for error responses");
        assertTrue(exception.getReason().contains("Error during S3 Cover Cleanup Dry Run"));
    }

    @Test
    void triggerS3CoverMoveAction_shouldReturnInternalServerError_WhenUnexpectedFailureOccurs() {
        when(s3CoverCleanupService.performMoveAction(nullable(String.class), anyInt(), anyString()))
            .thenThrow(new IllegalStateException("S3 move failed"));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerS3CoverMoveAction(null, 10, TEST_QUARANTINE_PREFIX)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
    }

    @Test
    void triggerS3CoverMoveAction_shouldReturnBadRequest_WhenMoveActionRejectsArguments() {
        when(s3CoverCleanupService.performMoveAction(nullable(String.class), anyInt(), anyString()))
            .thenThrow(new IllegalArgumentException("invalid prefix"));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerS3CoverMoveAction(null, 10, TEST_QUARANTINE_PREFIX)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void getCoverBackfillStatus_shouldReturnCurrentAndLastAttemptDiagnostics_WhenAvailable() {
        SourceAttemptStatus openLibraryAttempt = new SourceAttemptStatus(
            "OPEN_LIBRARY",
            "NOT_FOUND",
            "no usable cover content from OPEN_LIBRARY (404 Not Found)",
            Instant.parse("2026-02-10T00:00:00Z")
        );
        BackfillProgress expectedProgress = new BackfillProgress(
            20,
            3,
            1,
            2,
            true,
            "book-current",
            "Current Book",
            "9780316769488",
            List.of(openLibraryAttempt),
            "book-last",
            "Last Book",
            "9780140177398",
            Boolean.FALSE,
            List.of(openLibraryAttempt)
        );
        when(coverBackfillService.getProgress()).thenReturn(expectedProgress);

        BackfillProgress responseBody = adminController.getCoverBackfillStatus().getBody();

        assertEquals(expectedProgress, responseBody);
        assertNotNull(responseBody);
        assertEquals("NOT_FOUND", responseBody.currentBookAttempts().get(0).outcome());
        assertEquals("book-current", responseBody.currentBookId());
    }

    @Test
    void startCoverBackfill_shouldReturnConflict_WhenAlreadyRunning() {
        when(coverBackfillService.isRunning()).thenReturn(true);
        when(coverBackfillService.getProgress()).thenReturn(new BackfillProgress(
            50, 10, 5, 5, true,
            null, null, null, List.of(),
            null, null, null, null, List.of()
        ));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.startCoverBackfill("missing", 100)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("already running"));
    }

    @Test
    void startCoverBackfill_shouldReturnBadRequest_WhenModeIsInvalid() {
        when(coverBackfillService.isRunning()).thenReturn(false);

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.startCoverBackfill("bogus", 100)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Invalid backfill mode"));
    }

    @Test
    void startCoverBackfill_shouldReturnAccepted_WhenStartedSuccessfully() {
        when(coverBackfillService.isRunning()).thenReturn(false);

        var response = adminController.startCoverBackfill("missing", 100);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertTrue(response.getBody().contains("mode=MISSING"));
        verify(coverBackfillService).runBackfill(BackfillMode.MISSING, 100);
    }

    @Test
    void cancelCoverBackfill_shouldReturnOk_WhenCancelled() {
        var response = adminController.cancelCoverBackfill();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("cancellation requested"));
        verify(coverBackfillService).cancel();
    }
}
