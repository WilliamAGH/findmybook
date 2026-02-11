package net.findmybook.controller;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
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
            () -> adminController.triggerNytBestsellerProcessing()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("NYT processing is disabled", exception.getReason());
    }

    @Test
    void triggerNytBestsellerProcessing_shouldReturnInternalServerError_WhenSchedulerFailsUnexpectedly() {
        doThrow(new RuntimeException("NYT upstream failed"))
            .when(newYorkTimesBestsellerScheduler)
            .processNewYorkTimesBestsellers();

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerNytBestsellerProcessing()
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        assertEquals("Failed to trigger New York Times Bestseller processing job.", exception.getReason());
    }

    @Test
    void triggerS3CoverCleanupDryRun_shouldReturnInternalServerError_WhenUnexpectedFailureOccurs() {
        when(s3CoverCleanupService.performDryRun(anyString(), anyInt()))
            .thenThrow(new RuntimeException("S3 list failed"));

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
            .thenThrow(new RuntimeException("S3 move failed"));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> adminController.triggerS3CoverMoveAction(null, 10, TEST_QUARANTINE_PREFIX)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
    }
}
