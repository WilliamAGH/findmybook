package net.findmybook.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import net.findmybook.domain.ai.BookAiAnalysis;
import net.findmybook.domain.ai.BookAiSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Disabled("Requires Docker environment")
@Import({BookAiAnalysisRepository.class, ObjectMapper.class})
class BookAiAnalysisRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private BookAiAnalysisRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insertNewCurrentVersion_CreatesFirstVersion() {
        UUID bookId = createBook();
        BookAiAnalysis analysis = new BookAiAnalysis("Summary", "Fit", List.of("Theme1"));

        BookAiSnapshot snapshot = repository.insertNewCurrentVersion(
            bookId, analysis, "gpt-model", "openai", "hash123"
        );

        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.model()).isEqualTo("gpt-model");
        assertThat(snapshot.analysis().summary()).isEqualTo("Summary");
    }

    @Test
    void insertNewCurrentVersion_IncrementsVersion_AndUpdatesCurrentFlag() {
        UUID bookId = createBook();
        BookAiAnalysis a1 = new BookAiAnalysis("Old", "Fit", List.of("T1"));
        BookAiAnalysis a2 = new BookAiAnalysis("New", "Fit", List.of("T2"));

        repository.insertNewCurrentVersion(bookId, a1, "m1", "p1", "h1");
        BookAiSnapshot s2 = repository.insertNewCurrentVersion(bookId, a2, "m2", "p2", "h2");

        assertThat(s2.version()).isEqualTo(2);
        assertThat(s2.analysis().summary()).isEqualTo("New");

        // Verify version 1 is no longer current
        Boolean v1Current = jdbcTemplate.queryForObject(
            "SELECT is_current FROM book_ai_analysis_versions WHERE book_id = ? AND version_number = 1",
            Boolean.class, bookId
        );
        assertThat(v1Current).isFalse();
    }

    @Test
    void fetchCurrent_ReturnsLatestCurrent() {
        UUID bookId = createBook();
        BookAiAnalysis analysis = new BookAiAnalysis("Summary", "Fit", List.of("Theme1"));
        repository.insertNewCurrentVersion(bookId, analysis, "m1", "p1", "h1");

        assertThat(repository.fetchCurrent(bookId)).isPresent();
    }

    private UUID createBook() {
        UUID id = UUID.randomUUID();
        // Minimal book insert to satisfy FK
        jdbcTemplate.update(
            "INSERT INTO books (id, title, slug, created_at, updated_at) VALUES (?, 'Title', ?, NOW(), NOW())",
            id, "slug-" + id
        );
        return id;
    }
}
