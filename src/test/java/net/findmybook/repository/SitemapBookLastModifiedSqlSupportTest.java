package net.findmybook.repository;

import net.findmybook.support.sitemap.SitemapBookLastModifiedSqlSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
