package net.findmybook.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BookSearchServiceBlankQueryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookSearchService bookSearchService;

    @BeforeEach
    void initService() {
        bookSearchService = new BookSearchService(
            jdbcTemplate,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
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
}
