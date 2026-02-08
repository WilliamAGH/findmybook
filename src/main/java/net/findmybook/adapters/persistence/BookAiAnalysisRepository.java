package net.findmybook.adapters.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.domain.ai.BookAiAnalysis;
import net.findmybook.domain.ai.BookAiSnapshot;
import net.findmybook.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Postgres adapter for versioned book AI analysis records.
 *
 * <p>Owns all SQL for the {@code book_ai_content} table so service-layer
 * orchestration remains persistence-agnostic.</p>
 */
@Repository
public class BookAiAnalysisRepository {

    private static final Logger log = LoggerFactory.getLogger(BookAiAnalysisRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BookAiAnalysisRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
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
    public Optional<BookAiSnapshot> fetchCurrent(UUID bookId) {
        if (bookId == null) {
            return Optional.empty();
        }

        String sql = """
            SELECT version_number, created_at, model, provider, analysis_json
            FROM book_ai_content
            WHERE book_id = ? AND is_current = true
            ORDER BY version_number DESC
            LIMIT 1
            """;

        return jdbcTemplate.query(
            sql,
            rs -> {
                if (!rs.next()) {
                    return Optional.<BookAiSnapshot>empty();
                }

                int version = rs.getInt("version_number");
                Timestamp createdAt = rs.getTimestamp("created_at");
                Instant generatedAt = createdAt != null ? createdAt.toInstant() : Instant.now();
                String model = rs.getString("model");
                String provider = rs.getString("provider");
                String analysisJson = rs.getString("analysis_json");

                BookAiAnalysis analysis = deserializeAnalysis(analysisJson);
                return Optional.of(new BookAiSnapshot(bookId, version, generatedAt, model, provider, analysis));
            },
            bookId
        );
    }

    /**
     * Stores a new version and marks it current for the supplied book.
     *
     * @param bookId canonical book UUID
     * @param analysis normalized analysis payload
     * @param model resolved model identifier
    * @param provider provider label
    * @param promptHash prompt hash used for observability/dedup diagnostics
    * @return persisted current snapshot
     */
    @Transactional
    public BookAiSnapshot insertNewCurrentVersion(UUID bookId,
                                                  BookAiAnalysis analysis,
                                                  String model,
                                                  String provider,
                                                  String promptHash) {
        int nextVersion = resolveNextVersion(bookId);
        String rowId = IdGenerator.generateLong();
        String analysisJson = serializeJson(analysis);
        String keyThemesJson = serializeJson(analysis.keyThemes());
        String takeawaysJson = serializeJson(analysis.takeaways());

        jdbcTemplate.update(
            "UPDATE book_ai_content SET is_current = false WHERE book_id = ? AND is_current = true",
            bookId
        );

        String insertSql = """
            INSERT INTO book_ai_content
              (id, book_id, version_number, is_current, analysis_json, summary, reader_fit, key_themes,
               takeaways, context, model, provider, prompt_hash, created_at)
            VALUES (?, ?, ?, true, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb),
                    CAST(? AS jsonb), ?, ?, ?, ?, NOW())
            """;

        jdbcTemplate.update(
            insertSql,
            rowId,
            bookId,
            nextVersion,
            analysisJson,
            analysis.summary(),
            analysis.readerFit(),
            keyThemesJson,
            takeawaysJson,
            analysis.context(),
            model,
            provider,
            promptHash
        );

        return fetchCurrent(bookId)
            .orElseThrow(() -> new IllegalStateException("Inserted AI analysis could not be reloaded for book " + bookId));
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
            throw new IllegalStateException("Failed to serialize AI analysis value: " + value.getClass().getSimpleName(), ex);
        }
    }

    private BookAiAnalysis deserializeAnalysis(String analysisJson) {
        try {
            return objectMapper.readValue(analysisJson, BookAiAnalysis.class);
        } catch (JacksonException ex) {
            log.error("Failed to deserialize persisted analysis: {}", analysisJson, ex);
            throw new IllegalStateException("Persisted AI analysis payload is invalid", ex);
        }
    }
}
