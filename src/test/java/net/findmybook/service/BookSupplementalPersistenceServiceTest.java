package net.findmybook.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the tag persistence guardrails noted in docs/task-retrofit-code-for-postgres-schema.md.
 */
@ExtendWith(MockitoExtension.class)
class BookSupplementalPersistenceServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BookCollectionPersistenceService collectionPersistenceService;

    private BookSupplementalPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new BookSupplementalPersistenceService(jdbcTemplate, new ObjectMapper(), collectionPersistenceService);
    }

    @Test
    void assignQualifierTags_persistsMetadataJson() {
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), any(Object[].class)))
                .thenReturn("tag-001");

        ArgumentCaptor<String> assignmentSqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        // Use a valid UUID for book ID
        String bookId = "11111111-1111-4111-8111-111111111111";

        service.assignQualifierTags(
                bookId,
                Map.of("nytBestseller", Map.of("list", "hardcover-fiction", "rank", 1))
        );

        verify(jdbcTemplate).update(
                assignmentSqlCaptor.capture(),
                any(),
                any(), // book_id is converted to UUID, not the original string
                eq("tag-001"),
                eq("QUALIFIER"),
                isNull(),
                metadataCaptor.capture()
        );

        assertThat(assignmentSqlCaptor.getValue()).contains("ON CONFLICT (book_id, tag_id)");
        assertThat(metadataCaptor.getValue()).contains("hardcover-fiction");
    }

    @Test
    void assignTag_normalizesKeyAndTrimsSource() {
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), any(Object[].class)))
                .thenReturn("tag-002");

        String bookId = "22222222-2222-4222-8222-222222222222";

        service.assignTag(
            bookId,
            "  NYT BESTSELLER  ",
            "NYT Bestseller",
            "  NYT  ",
            1.0d,
            Map.of("rank", 1)
        );

        verify(jdbcTemplate).queryForObject(
            startsWith("INSERT INTO book_tags"),
            org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
            any(),
            eq("nyt_bestseller"),
            eq("NYT Bestseller"),
            eq("QUALIFIER")
        );

        verify(jdbcTemplate).update(
            startsWith("INSERT INTO book_tag_assignments"),
            any(),
            any(),
            eq("tag-002"),
            eq("NYT"),
            eq(1.0d),
            any()
        );
    }

    @Test
    void assignTag_shouldUpsertTagDisplayName_WhenCanonicalTagAlreadyExists() {
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), any(Object[].class)))
            .thenReturn("tag-003");

        service.assignTag(
            "33333333-3333-4333-8333-333333333333",
            "nyt_list_audio_fiction",
            "NYT List: Audio Fiction",
            "NYT",
            1.0d,
            Map.of("list_code", "audio-fiction", "rank", 1)
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(
            sqlCaptor.capture(),
            org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
            any(),
            eq("nyt_list_audio_fiction"),
            eq("NYT List: Audio Fiction"),
            eq("QUALIFIER")
        );

        assertThat(sqlCaptor.getValue()).contains("display_name = CASE WHEN EXCLUDED.display_name IS NOT NULL");
        assertThat(sqlCaptor.getValue()).contains("btrim(EXCLUDED.display_name) <> ''");
    }
}
