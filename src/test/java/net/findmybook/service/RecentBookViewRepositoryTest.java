package net.findmybook.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for SQL-window selection in {@link RecentBookViewRepository}.
 */
class RecentBookViewRepositoryTest {

    @Test
    void should_ReturnZero_When_ViewCountRequestedWithoutRepository() {
        RecentBookViewRepository repository = new RecentBookViewRepository(null);

        long count = repository.fetchViewCountForBook(
            "book-1",
            RecentBookViewRepository.ViewWindow.LAST_30_DAYS
        );

        assertEquals(0L, count);
    }

    @Test
    void should_UseThirtyDaySql_When_ViewCountRequestedForLastThirtyDays() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        RecentBookViewRepository repository = new RecentBookViewRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("book-30"))).thenReturn(7L);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        long count = repository.fetchViewCountForBook(
            "book-30",
            RecentBookViewRepository.ViewWindow.LAST_30_DAYS
        );

        assertEquals(7L, count);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), eq("book-30"));
        assertTrue(sqlCaptor.getValue().contains("INTERVAL '30 days'"));
    }

    @Test
    void should_UseNinetyDaySql_When_ViewCountRequestedForLastNinetyDays() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        RecentBookViewRepository repository = new RecentBookViewRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("book-90"))).thenReturn(11L);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        long count = repository.fetchViewCountForBook(
            "book-90",
            RecentBookViewRepository.ViewWindow.LAST_90_DAYS
        );

        assertEquals(11L, count);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), eq("book-90"));
        assertTrue(sqlCaptor.getValue().contains("INTERVAL '90 days'"));
    }

    @Test
    void should_UseAllTimeSql_When_ViewCountRequestedForAllTime() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        RecentBookViewRepository repository = new RecentBookViewRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("book-all"))).thenReturn(21L);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        long count = repository.fetchViewCountForBook(
            "book-all",
            RecentBookViewRepository.ViewWindow.ALL_TIME
        );

        assertEquals(21L, count);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), eq("book-all"));
        assertTrue(sqlCaptor.getValue().contains("WHERE book_id = ?"));
        assertFalse(sqlCaptor.getValue().contains("INTERVAL '30 days'"));
        assertFalse(sqlCaptor.getValue().contains("INTERVAL '90 days'"));
    }

    @Test
    void should_UseNinetyDaySql_When_PopularBooksRequestedForLastNinetyDays() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        RecentBookViewRepository repository = new RecentBookViewRepository(jdbcTemplate);
        when(jdbcTemplate.query(
            anyString(),
            any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<RecentBookViewRepository.BookViewAggregate>>any()
        )).thenReturn(List.of());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        List<RecentBookViewRepository.BookViewAggregate> aggregates = repository.fetchMostViewedBooks(
            RecentBookViewRepository.ViewWindow.LAST_90_DAYS,
            8
        );

        assertTrue(aggregates.isEmpty());
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<RecentBookViewRepository.BookViewAggregate>>any()
        );
        assertTrue(sqlCaptor.getValue().contains("INTERVAL '90 days'"));
    }

    @Test
    void should_UseAllTimeSql_When_PopularBooksRequestedForAllTime() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        RecentBookViewRepository repository = new RecentBookViewRepository(jdbcTemplate);
        when(jdbcTemplate.query(
            anyString(),
            any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<RecentBookViewRepository.BookViewAggregate>>any()
        )).thenReturn(List.of());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        List<RecentBookViewRepository.BookViewAggregate> aggregates = repository.fetchMostViewedBooks(
            RecentBookViewRepository.ViewWindow.ALL_TIME,
            8
        );

        assertTrue(aggregates.isEmpty());
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<RecentBookViewRepository.BookViewAggregate>>any()
        );
        assertTrue(sqlCaptor.getValue().contains("FROM recent_book_views"));
        assertFalse(sqlCaptor.getValue().contains("INTERVAL '90 days'"));
    }
}
