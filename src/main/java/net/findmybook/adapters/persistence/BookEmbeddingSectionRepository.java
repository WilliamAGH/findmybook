package net.findmybook.adapters.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.domain.similarity.BookSimilaritySectionInput;
import net.findmybook.domain.similarity.BookSimilaritySectionKey;
import net.findmybook.util.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres adapter for cached per-section book embeddings.
 *
 * <p>Section embeddings are cached by exact model/input hash so profile weight
 * changes can refuse the embeddings API without re-embedding. Fused vector
 * writes (book-level similarity contracts) live in
 * {@link BookSimilarityEmbeddingRepository} so each repository stays focused on
 * a single storage concern.</p>
 */
@Repository
public class BookEmbeddingSectionRepository {

    private static final String INPUT_FORMAT = "key_value";
    private static final int INPUT_PREVIEW_MAX_CHARS = 512;

    private final JdbcTemplate jdbcTemplate;

    public BookEmbeddingSectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads a cached section embedding for an exact model/input hash.
     *
     * @param bookId canonical book UUID
     * @param sectionKey canonical section identifier
     * @param model active embeddings model
     * @param inputHash SHA-256 hash over section input text
     * @return decoded vector when a cache hit exists
     */
    @Transactional(readOnly = true)
    public Optional<List<Float>> fetchSectionEmbedding(UUID bookId,
                                                       BookSimilaritySectionKey sectionKey,
                                                       String model,
                                                       String inputHash) {
        String vectorText = jdbcTemplate.query(
            """
            SELECT embedding::text
            FROM book_embedding_sections
            WHERE book_id = ?
              AND section_key = ?
              AND model = ?
              AND input_format = ?
              AND input_hash = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            bookId,
            sectionKey.key(),
            model,
            INPUT_FORMAT,
            inputHash
        );
        return vectorText == null ? Optional.empty() : Optional.of(BookSimilarityVectorLiteral.parseHalfvec(vectorText));
    }

    /**
     * Caches a section embedding under its exact source hash.
     *
     * @param bookId canonical book UUID
     * @param sectionInput rendered section input used for the embedding call
     * @param model active embeddings model
     * @param embedding vector produced by the embeddings provider
     */
    @Transactional
    public void upsertSectionEmbedding(UUID bookId,
                                       BookSimilaritySectionInput sectionInput,
                                       String model,
                                       List<Float> embedding) {
        jdbcTemplate.update(
            """
            INSERT INTO book_embedding_sections
              (id, book_id, section_key, input_format, input_hash, model, embedding, input_preview, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS halfvec), ?, NOW())
            ON CONFLICT (book_id, section_key, model, input_format, input_hash)
            DO UPDATE SET embedding = EXCLUDED.embedding,
                          input_preview = EXCLUDED.input_preview,
                          updated_at = NOW()
            """,
            IdGenerator.generateLong(),
            bookId,
            sectionInput.sectionKey().key(),
            INPUT_FORMAT,
            sectionInput.inputHash(),
            model,
            BookSimilarityVectorLiteral.toHalfvecLiteral(embedding),
            preview(sectionInput.text())
        );
    }

    private static String preview(String text) {
        int previewLength = Math.min(INPUT_PREVIEW_MAX_CHARS, text.length());
        return text.substring(0, previewLength);
    }
}
