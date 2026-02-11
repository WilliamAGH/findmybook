package net.findmybook.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.findmybook.domain.seo.BookSeoMetadataSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Disabled("Requires Docker environment")
@Import(BookSeoMetadataRepository.class)
class BookSeoMetadataRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private BookSeoMetadataRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insertNewCurrentVersion_IncrementsVersion_AndDemotesPreviousCurrentRow() {
        UUID bookId = createBook();
        repository.insertNewCurrentVersion(bookId, "Title v1 - Book Details | findmybook.net", "Description v1", "m1", "openai", "h1");
        BookSeoMetadataSnapshot snapshot = repository.insertNewCurrentVersion(
            bookId,
            "Title v2 - Book Details | findmybook.net",
            "Description v2",
            "m2",
            "openai",
            "h2"
        );

        assertThat(snapshot.version()).isEqualTo(2);
        assertThat(snapshot.seoTitle()).isEqualTo("Title v2 - Book Details | findmybook.net");
        Boolean previousCurrent = jdbcTemplate.queryForObject(
            "SELECT is_current FROM book_seo_metadata WHERE book_id = ? AND version_number = 1",
            Boolean.class,
            bookId
        );
        assertThat(previousCurrent).isFalse();
    }

    @Test
    void fetchCurrentPromptHash_ReturnsCurrentPromptHash() {
        UUID bookId = createBook();
        repository.insertNewCurrentVersion(
            bookId,
            "Title - Book Details | findmybook.net",
            "Description",
            "gpt-5-mini",
            "openai",
            "prompt-hash"
        );

        assertThat(repository.fetchCurrentPromptHash(bookId)).contains("prompt-hash");
    }

    private UUID createBook() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO books (id, title, slug, created_at, updated_at) VALUES (?, 'Title', ?, NOW(), NOW())",
            id,
            "slug-" + id
        );
        return id;
    }
}
