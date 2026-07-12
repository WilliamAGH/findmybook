package net.findmybook.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookSearchServiceBlankQueryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookSearchService bookSearchService;

    @BeforeEach
    void initService() {
        BookSearchService.SearchDependencies deps = new BookSearchService.SearchDependencies(
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
        bookSearchService = new BookSearchService(
            jdbcTemplate,
            deps,
            false
        );
    }

    @Test
    @DisplayName("searchBooks() returns empty list for blank query without touching database")
    void searchBooksSkipsBlankQuery() {
        List<BookSearchService.SearchResult> results = bookSearchService.searchBooks("   ", 10);

        assertThat(results).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("searchAuthors() returns empty list for blank query without touching database")
    void searchAuthorsSkipsBlankQuery() {
        List<BookSearchService.AuthorResult> results = bookSearchService.searchAuthors("\t", 5);

        assertThat(results).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void should_GroupCanonicalNamesAndCountDistinctBooks_When_CategoryFacetsRequested() {
        List<BookSearchService.CategoryFacet> expectedFacets = List.of(
            new BookSearchService.CategoryFacet("Fantasy", 240)
        );
        when(jdbcTemplate.query(
            anyString(),
            ArgumentMatchers.<PreparedStatementSetter>any(),
            ArgumentMatchers.<RowMapper<BookSearchService.CategoryFacet>>any()
        )).thenReturn(expectedFacets);

        List<BookSearchService.CategoryFacet> facets = bookSearchService.fetchCategoryFacets(10, 3);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            ArgumentMatchers.<PreparedStatementSetter>any(),
            ArgumentMatchers.<RowMapper<BookSearchService.CategoryFacet>>any()
        );
        assertThat(facets).containsExactlyElementsOf(expectedFacets);
        assertThat(sqlCaptor.getValue())
            .contains("MIN(bc.display_name) AS category_name")
            .contains("COUNT(DISTINCT bcj.book_id)::int AS book_count")
            .contains("GROUP BY bc.normalized_name")
            .contains("HAVING COUNT(DISTINCT bcj.book_id) >= ?")
            .doesNotContain(" OVER ");
    }
}
