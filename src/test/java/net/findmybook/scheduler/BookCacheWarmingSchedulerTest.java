package net.findmybook.scheduler;

import net.findmybook.dto.BookDetail;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.ApiRequestMonitor;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.RecentlyViewedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookCacheWarmingSchedulerTest {

    private static final int TEST_RATE_LIMIT = 60_000;
    private static final int TEST_MAX_BOOKS_PER_RUN = 5;

    private RecentlyViewedService recentlyViewedService;
    private ObjectProvider<ApiRequestMonitor> apiRequestMonitorProvider;
    private BookQueryRepository bookQueryRepository;
    private BookIdentifierResolver bookIdentifierResolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        recentlyViewedService = mock(RecentlyViewedService.class);
        apiRequestMonitorProvider = (ObjectProvider<ApiRequestMonitor>) org.mockito.Mockito.mock(ObjectProvider.class);
        bookQueryRepository = mock(BookQueryRepository.class);
        bookIdentifierResolver = mock(BookIdentifierResolver.class);

        ApiRequestMonitor apiRequestMonitor = mock(ApiRequestMonitor.class);
        when(apiRequestMonitor.getCurrentHourlyRequests()).thenReturn(0);
        when(apiRequestMonitorProvider.getIfAvailable()).thenReturn(apiRequestMonitor);
    }

    @Test
    void warmPopularBookCaches_skipsWhenDisabled() {
        BookCacheWarmingScheduler disabledScheduler = new BookCacheWarmingScheduler(
            recentlyViewedService,
            apiRequestMonitorProvider,
            bookQueryRepository,
            bookIdentifierResolver,
            false,
            TEST_RATE_LIMIT,
            TEST_MAX_BOOKS_PER_RUN
        );

        disabledScheduler.warmPopularBookCaches();

        verifyNoInteractions(recentlyViewedService);
    }

    @Test
    void warmPopularBookCaches_resolvesBooksFromRepository() {
        BookCacheWarmingScheduler singleBookScheduler = new BookCacheWarmingScheduler(
            recentlyViewedService,
            apiRequestMonitorProvider,
            bookQueryRepository,
            bookIdentifierResolver,
            true,
            TEST_RATE_LIMIT,
            1
        );

        BookDetail detail = new BookDetail(
            "uuid-123",
            "slug-123",
            "Test Title",
            "Test Description",
            "Test Publisher",
            LocalDate.now(),
            "en",
            320,
            List.of("Author"),
            List.of("Fiction"),
            "https://example.test/cover.jpg",
            "s3://covers/test-title.jpg",
            "https://example.test/cover-fallback.jpg",
            "https://example.test/thumb.jpg",
            600,
            900,
            Boolean.TRUE,
            "POSTGRES",
            4.5,
            120,
            "1234567890",
            "1234567890123",
            "https://example.test/preview",
            "https://example.test/info",
            Map.<String, Object>of(),
            List.<net.findmybook.dto.EditionSummary>of()
        );

        when(recentlyViewedService.getRecentlyViewedBookIds(anyInt())).thenReturn(List.of("slug-123"));
        when(bookQueryRepository.fetchBookDetailBySlug("slug-123")).thenReturn(Optional.of(detail));

        ApiRequestMonitor apiRequestMonitor = mock(ApiRequestMonitor.class);
        when(apiRequestMonitor.getCurrentHourlyRequests()).thenReturn(0);
        when(apiRequestMonitorProvider.getIfAvailable()).thenReturn(apiRequestMonitor);

        singleBookScheduler.warmPopularBookCaches();

        verify(bookQueryRepository).fetchBookDetailBySlug("slug-123");
    }
}

class WorkClusterSchedulerTest {

    private JdbcTemplate jdbcTemplate;
    private WorkClusterScheduler scheduler;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        scheduler = new WorkClusterScheduler(jdbcTemplate);
    }

    @Test
    void clusterBooks_should_SkipGoogleClustering_When_KnownConstraintFailureOccurs() {
        when(jdbcTemplate.queryForObject(
            eq("SELECT * FROM cluster_books_by_isbn()"),
            org.mockito.ArgumentMatchers.<RowMapper<Integer[]>>any()
        )).thenReturn(new Integer[]{2, 8});

        when(jdbcTemplate.queryForObject(
            eq("SELECT * FROM cluster_books_by_google_canonical()"),
            org.mockito.ArgumentMatchers.<RowMapper<Integer[]>>any()
        )).thenThrow(new DataIntegrityViolationException(
            "ERROR: check_reasonable_member_count violated in cluster_books_by_google_canonical"
        ));

        assertDoesNotThrow(() -> scheduler.clusterBooks());

        verify(jdbcTemplate).queryForObject(
            eq("SELECT * FROM cluster_books_by_isbn()"),
            org.mockito.ArgumentMatchers.<RowMapper<Integer[]>>any()
        );
        verify(jdbcTemplate).queryForObject(
            eq("SELECT * FROM cluster_books_by_google_canonical()"),
            org.mockito.ArgumentMatchers.<RowMapper<Integer[]>>any()
        );
    }

    @Test
    void clusterBooks_should_ThrowIllegalStateException_When_IsbnClusteringFails() {
        when(jdbcTemplate.queryForObject(
            eq("SELECT * FROM cluster_books_by_isbn()"),
            org.mockito.ArgumentMatchers.<RowMapper<Integer[]>>any()
        )).thenThrow(new DataIntegrityViolationException("isbn clustering failure"));

        assertThrows(IllegalStateException.class, () -> scheduler.clusterBooks());
    }

    @Test
    void clusterBooks_should_ThrowIllegalStateException_When_GoogleClusteringFailsForUnknownReason() {
        when(jdbcTemplate.queryForObject(
            eq("SELECT * FROM cluster_books_by_isbn()"),
            org.mockito.ArgumentMatchers.<RowMapper<Integer[]>>any()
        )).thenReturn(new Integer[]{1, 3});

        when(jdbcTemplate.queryForObject(
            eq("SELECT * FROM cluster_books_by_google_canonical()"),
            org.mockito.ArgumentMatchers.<RowMapper<Integer[]>>any()
        )).thenThrow(new DataIntegrityViolationException("google clustering failure"));

        assertThrows(IllegalStateException.class, () -> scheduler.clusterBooks());
    }
}
