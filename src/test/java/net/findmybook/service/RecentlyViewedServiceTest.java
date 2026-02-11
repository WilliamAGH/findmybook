package net.findmybook.service;

import net.findmybook.model.Book;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class RecentlyViewedServiceTest {

    private JdbcTemplate jdbcTemplate;
    private RecentBookViewRepository recentBookViewRepository;
    private RecentlyViewedService recentlyViewedService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        recentBookViewRepository = mock(RecentBookViewRepository.class);

        when(recentBookViewRepository.isEnabled()).thenReturn(false);

        recentlyViewedService = createService();
    }

    private RecentlyViewedService createService() {
        return new RecentlyViewedService(jdbcTemplate, recentBookViewRepository);
    }

    @Test
    void getRecentlyViewedBookIds_returnsRepositoryIdsWhenAvailable() {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        when(recentBookViewRepository.isEnabled()).thenReturn(true);
        when(recentBookViewRepository.fetchMostRecentViews(3))
            .thenReturn(List.of(
                new RecentBookViewRepository.ViewStats("uuid-1", now, 5L, 9L, 12L),
                new RecentBookViewRepository.ViewStats("uuid-2", now.minusSeconds(60), 4L, 7L, 10L)
            ));

        List<String> ids = recentlyViewedService.getRecentlyViewedBookIds(3);

        assertEquals(List.of("uuid-1", "uuid-2"), ids);
        verify(recentBookViewRepository).fetchMostRecentViews(3);
    }

    @Test
    void getRecentlyViewedBookIds_fallsBackToCacheWhenRepositoryDisabled() {
        when(recentBookViewRepository.isEnabled()).thenReturn(false);

        Book cached = sampleBook("uuid-cache");
        cached.setSlug("uuid-cache");

        recentlyViewedService.addToRecentlyViewed(cached);

        List<String> ids = recentlyViewedService.getRecentlyViewedBookIds(5);

        assertEquals(List.of("uuid-cache"), ids);
    }

    @Test
    void getRecentlyViewedBookIds_throwsWhenRepositoryFails() {
        when(recentBookViewRepository.isEnabled()).thenReturn(true);
        when(recentBookViewRepository.fetchMostRecentViews(anyInt())).thenThrow(new RuntimeException("boom"));

        assertThrows(IllegalStateException.class, () -> recentlyViewedService.getRecentlyViewedBookIds(4));
        verify(recentBookViewRepository).fetchMostRecentViews(4);
    }

    @Test
    void addToRecentlyViewed_recordsViewWithoutSynchronousStatsRead() {
        when(recentBookViewRepository.isEnabled()).thenReturn(true);

        Book book = sampleBook("uuid-2");
        book.setSlug("slug-uuid-2");

        recentlyViewedService.addToRecentlyViewed(book);

        verify(recentBookViewRepository).recordView(eq("uuid-2"), any(Instant.class), eq("web"));
        verify(recentBookViewRepository, never()).fetchStatsForBook(any());
        assertTrue(
            book.getQualifiers() == null
                || book.getQualifiers().keySet().stream().noneMatch(key -> key.startsWith("recent.views."))
        );
    }

    @Test
    void addToRecentlyViewed_resolvesCanonicalIdViaWorkCluster() {
        String originalId = "11111111-1111-1111-1111-111111111111";
        String canonicalId = "22222222-2222-2222-2222-222222222222";
        when(jdbcTemplate.queryForObject(
                anyString(),
                eq(String.class),
                any(UUID.class)))
            .thenReturn(canonicalId);

        Book book = sampleBook(originalId);
        book.setSlug("slug-" + originalId);

        recentlyViewedService.addToRecentlyViewed(book);

        List<String> ids = recentlyViewedService.getRecentlyViewedBookIds(3);
        assertEquals(List.of(canonicalId), ids);
        verify(jdbcTemplate).queryForObject(
            anyString(),
            eq(String.class),
            any(UUID.class));
    }

    @Test
    void fetchViewCount_delegatesToRepositoryForRequestedWindow() {
        when(recentBookViewRepository.isEnabled()).thenReturn(true);
        when(recentBookViewRepository.fetchViewCountForBook(
            "uuid-30d",
            RecentBookViewRepository.ViewWindow.LAST_30_DAYS
        )).thenReturn(18L);

        long count = recentlyViewedService.fetchViewCount(
            "uuid-30d",
            RecentBookViewRepository.ViewWindow.LAST_30_DAYS
        );

        assertEquals(18L, count);
        verify(recentBookViewRepository).fetchViewCountForBook(
            "uuid-30d",
            RecentBookViewRepository.ViewWindow.LAST_30_DAYS
        );
    }

    private Book sampleBook(String id) {
        Book book = new Book();
        book.setId(id);
        book.setTitle("Test Book");
        book.setAuthors(List.of("Author"));
        book.setPublishedDate(Date.from(Instant.parse("2020-01-01T00:00:00Z")));
        book.setS3ImagePath("https://cdn.example/" + id + ".jpg");
        return book;
    }
}
