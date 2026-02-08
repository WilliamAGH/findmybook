package net.findmybook.adapters.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.domain.ai.BookAiContent;
import net.findmybook.domain.ai.BookAiContentSnapshot;
import net.findmybook.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Postgres adapter for versioned book AI content records.
 *
 * <p>Owns all SQL for the {@code book_ai_content} table so service-layer
 * orchestration remains persistence-agnostic.</p>
 */
@Repository
public class BookAiContentRepository {

    private static final Logger log = LoggerFactory.getLogger(BookAiContentRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BookAiContentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Loads the current AI snapshot for the given book ID.
     *
     * @param bookId canonical book UUID
     * @return current snapshot when present
     */
    @Transactional(readOnly = true)
    public Optional<BookAiContentSnapshot> fetchCurrent(UUID bookId) {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId is required");
        }

        String sql = """
            SELECT version_number, created_at, model, provider, content_json
            FROM book_ai_content
            WHERE book_id = ? AND is_current = true
            ORDER BY version_number DESC
            LIMIT 1
            """;

        return jdbcTemplate.query(
            sql,
            rs -> {
                if (!rs.next()) {
                    return Optional.<BookAiContentSnapshot>empty();
                }

                int version = rs.getInt("version_number");
                Timestamp createdAt = rs.getTimestamp("created_at");
                if (createdAt == null) {
                    throw new IllegalStateException("Persisted AI content missing created_at for book " + bookId);
                }
                Instant generatedAt = createdAt.toInstant();
                String model = rs.getString("model");
                String provider = rs.getString("provider");
                String aiContentJson = rs.getString("content_json");

                BookAiContent aiContent = deserializeAiContent(aiContentJson);
                return Optional.of(new BookAiContentSnapshot(bookId, version, generatedAt, model, provider, aiContent));
            },
            bookId
        );
    }

    /**
     * Stores a new version and marks it current for the supplied book.
     *
     * @param bookId canonical book UUID
     * @param aiContent normalized AI content payload
     * @param model resolved model identifier
     * @param provider provider label
     * @param promptHash prompt hash used for observability/dedup diagnostics
     * @return persisted current snapshot
     */
    @Transactional
    public BookAiContentSnapshot insertNewCurrentVersion(UUID bookId,
                                                         BookAiContent aiContent,
                                                         String model,
                                                         String provider,
                                                         String promptHash) {
        int nextVersion = resolveNextVersion(bookId);
        String rowId = IdGenerator.generateLong();
        String contentJson = serializeJson(aiContent);
        String keyThemesJson = serializeJson(aiContent.keyThemes());
        String takeawaysJson = serializeJson(aiContent.takeaways());

        jdbcTemplate.update(
            "UPDATE book_ai_content SET is_current = false WHERE book_id = ? AND is_current = true",
            bookId
        );

        String insertSql = """
            INSERT INTO book_ai_content
              (id, book_id, version_number, is_current, content_json, summary, reader_fit, key_themes,
               takeaways, context, model, provider, prompt_hash, created_at)
            VALUES (?, ?, ?, true, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb),
                    CAST(? AS jsonb), ?, ?, ?, ?, NOW())
            """;

        jdbcTemplate.update(
            insertSql,
            rowId,
            bookId,
            nextVersion,
            contentJson,
            aiContent.summary(),
            aiContent.readerFit(),
            keyThemesJson,
            takeawaysJson,
            aiContent.context(),
            model,
            provider,
            promptHash
        );

        return fetchCurrent(bookId)
            .orElseThrow(() -> new IllegalStateException("Inserted AI content could not be reloaded for book " + bookId));
    }

    private int resolveNextVersion(UUID bookId) {
        Integer version = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version_number), 0) + 1 FROM book_ai_content WHERE book_id = ?",
            Integer.class,
            bookId
        );
        if (version == null || version <= 0) {
            throw new IllegalStateException("Failed to resolve AI version number for book " + bookId);
        }
        return version;
    }

    private String serializeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize AI content value: " + value.getClass().getSimpleName(), ex);
        }
    }

    private BookAiContent deserializeAiContent(String aiContentJson) {
        try {
            return objectMapper.readValue(aiContentJson, BookAiContent.class);
        } catch (JacksonException ex) {
            log.error("Failed to deserialize persisted AI content: {}", aiContentJson, ex);
            throw new IllegalStateException("Persisted AI content payload is invalid", ex);
        }
    }
}
