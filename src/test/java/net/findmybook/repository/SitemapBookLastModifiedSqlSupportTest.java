package net.findmybook.repository;

import net.findmybook.support.sitemap.SitemapBookLastModifiedSqlSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SitemapBookLastModifiedSqlSupportTest {

    @Test
    @DisplayName("globalBookLastModifiedCte injects alias and leaves no unresolved placeholders")
    void should_RenderGlobalBookLastModifiedCte_When_AliasProvided() {
        String sql = SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte("book_updated_at");

        assertTrue(sql.contains("MAX(change_events.changed_at) AS book_updated_at"));
        assertTrue(sql.contains("FROM book_ai_content bac"));
        assertTrue(sql.contains("FROM book_seo_metadata bsm"));
        assertFalse(sql.contains("%s"));
    }

    @Test
    @DisplayName("scopedAuthorBookLastModifiedQuery injects placeholders and alias without unresolved placeholders")
    void should_RenderScopedAuthorBookLastModifiedQuery_When_PlaceholdersAndAliasProvided() {
        String sql = SitemapBookLastModifiedSqlSupport.scopedAuthorBookLastModifiedQuery("?, ?, ?", "book_updated_at");

        assertTrue(sql.contains("WHERE baj.author_id IN (?, ?, ?)"));
        assertTrue(sql.contains("MAX(change_events.changed_at) AS book_updated_at"));
        assertTrue(sql.contains("SELECT baj.author_id, blm.id, blm.slug, blm.title, blm.book_updated_at"));
        assertFalse(sql.contains("%s"));
    }

    @Test
    void should_PageBooksBeforeAggregatingChangeEvents_When_RenderingXmlQuery() {
        String sql = SitemapBookLastModifiedSqlSupport.pagedBookLastModifiedQuery("book_updated_at");

        assertThat(sql)
            .contains("requested_books AS MATERIALIZED")
            .contains("LIMIT ? OFFSET ?")
            .contains("change_events AS NOT MATERIALIZED")
            .contains("LEFT JOIN change_events ON change_events.book_id = rb.id")
            .contains("MAX(change_events.changed_at) AS book_updated_at")
            .doesNotContain("%s");
        assertThat(sql.indexOf("LIMIT ? OFFSET ?")).isLessThan(sql.indexOf("change_events AS NOT MATERIALIZED"));
    }

    @Test
    void should_ThrowIllegalArgument_When_AliasContainsSqlInjection() {
        assertThatThrownBy(() ->
            SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte("x; DROP TABLE books"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_ThrowIllegalArgument_When_AliasIsBlank() {
        assertThatThrownBy(() ->
            SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_ThrowIllegalArgument_When_PlaceholdersContainSqlInjection() {
        assertThatThrownBy(() ->
            SitemapBookLastModifiedSqlSupport.scopedAuthorBookLastModifiedQuery(
                "1; DROP TABLE books --", "book_updated_at"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_AcceptValidAlias_When_AliasIsSimpleIdentifier() {
        assertDoesNotThrow(() ->
            SitemapBookLastModifiedSqlSupport.globalBookLastModifiedCte("book_updated_at"));
    }

    @Test
    void should_UseOneBulkQuery_When_AuthorPageMetadataIsRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        List<SitemapRepository.PageMetadata> expected = List.of(
                new SitemapRepository.PageMetadata(1, Instant.parse("2024-02-01T00:00:00Z"))
        );
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.PageMetadata>>any(),
                eq(100),
                eq(5000)
        )).thenReturn(expected);

        assertThat(sitemapRepository.fetchAuthorPageMetadata(100, 5000)).isEqualTo(expected);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.PageMetadata>>any(),
                eq(100),
                eq(5000)
        );
        assertThat(sqlCaptor.getValue())
                .contains("book_last_modified")
                .contains("ranked_authors")
                .contains("author_listing_pages")
                .contains("ROW_NUMBER() OVER")
                .contains("ORDER BY CASE bucket")
                .doesNotContain("%s");
    }

    @Test
    void should_UseBoundedPageQuery_When_BookXmlPageIsRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SitemapRepository sitemapRepository = new SitemapRepository(jdbcTemplate);
        when(jdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.BookRow>>any(),
            eq(5000),
            eq(10000)
        )).thenReturn(List.of());

        sitemapRepository.fetchBooksForXml(5000, 10000);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            org.mockito.ArgumentMatchers.<RowMapper<SitemapRepository.BookRow>>any(),
            eq(5000),
            eq(10000)
        );
        assertThat(sqlCaptor.getValue())
            .contains("requested_books AS MATERIALIZED")
            .contains("LIMIT ? OFFSET ?")
            .doesNotContain("FROM book_last_modified ORDER BY");
    }
}
