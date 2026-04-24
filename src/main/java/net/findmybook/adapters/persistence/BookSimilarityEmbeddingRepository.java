package net.findmybook.adapters.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.domain.similarity.BookSimilarityBookSource;
import net.findmybook.domain.similarity.BookSimilaritySectionInput;
import net.findmybook.domain.similarity.BookSimilaritySectionKey;
import net.findmybook.domain.similarity.BookSimilaritySourceDocument;
import net.findmybook.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Postgres adapter for book similarity embedding sources and persisted vectors.
 */
@Repository
public class BookSimilarityEmbeddingRepository {

    private static final Logger log = LoggerFactory.getLogger(BookSimilarityEmbeddingRepository.class);
    private static final String INPUT_FORMAT = "key_value";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BookSimilarityEmbeddingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Reports whether the persisted vector row is current versus canonical book state.
     *
     * <p>Returns true only when the vector exists with populated \`source_hash\` and
     * \`qwen_4b_fp16\`, the canonical \`books.updated_at\` is not newer than the vector's
     * \`computed_at\`, and no \`book_ai_content\` row is newer than the vector. A positive
     * result lets demand callers skip the AI queue round-trip without losing correctness
     * because the same freshness rules drive scheduled refresh candidate selection.</p>
     *
     * @param bookId canonical book UUID
     * @param modelVersion active model/profile/regime version
     * @param profileHash active fusion profile hash
     * @return true when the vector row is fresh for the active contract
     */
    @Transactional(readOnly = true)
    public boolean isVectorFresh(UUID bookId, String modelVersion, String profileHash) {
        Integer exists = jdbcTemplate.query(
            """
            SELECT 1
            FROM book_similarity_vectors v
            JOIN books b ON b.id = v.book_id
            WHERE v.source_type = 'book'
              AND v.book_id = ?
              AND v.model_version = ?
              AND v.profile_hash = ?
              AND v.source_hash IS NOT NULL
              AND v.qwen_4b_fp16 IS NOT NULL
              AND v.computed_at IS NOT NULL
              AND b.updated_at <= v.computed_at
              AND NOT EXISTS (
                SELECT 1
                FROM book_ai_content bac
                WHERE bac.book_id = b.id
                  AND bac.is_current = true
                  AND bac.created_at > v.computed_at
              )
            LIMIT 1
            """,
            rs -> rs.next() ? Integer.valueOf(1) : null,
            bookId,
            modelVersion,
            profileHash
        );
        return exists != null;
    }

    /**
     * Finds books whose persisted vector is missing or older than source tables.
     *
     * @param modelVersion active model/profile/regime version
     * @param profileHash active profile hash
     * @param limit maximum candidate rows
     * @return canonical book IDs to inspect
     */
    @Transactional(readOnly = true)
    public List<UUID> findRefreshCandidates(String modelVersion, String profileHash, int limit) {
        return jdbcTemplate.query(
            """
            SELECT b.id
            FROM books b
            LEFT JOIN book_similarity_vectors v
              ON v.book_id = b.id
             AND v.source_type = 'book'
             AND v.model_version = ?
             AND v.profile_hash = ?
            WHERE b.title IS NOT NULL
              AND (
                v.book_id IS NULL
                OR v.source_hash IS NULL
                OR v.qwen_4b_fp16 IS NULL
                OR v.computed_at IS NULL
                OR b.updated_at > v.computed_at
                OR EXISTS (
                  SELECT 1
                  FROM book_ai_content bac
                  WHERE bac.book_id = b.id
                    AND bac.is_current = true
                    AND bac.created_at > v.computed_at
                )
              )
            ORDER BY COALESCE(v.computed_at, TIMESTAMPTZ 'epoch') ASC, b.updated_at DESC
            LIMIT ?
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            modelVersion,
            profileHash,
            Math.max(1, limit)
        );
    }

    /**
     * Loads the current source data for a book.
     *
     * @param bookId canonical book UUID
     * @return source data when the book exists
     */
    @Transactional(readOnly = true)
    public Optional<BookSimilarityBookSource> fetchBookSource(UUID bookId) {
        return jdbcTemplate.query(
            """
            SELECT b.id, b.title, b.subtitle, b.description, b.publisher,
                   EXTRACT(YEAR FROM b.published_date)::text published_year,
                   b.page_count::text page_count, b.language,
                   authors.names authors,
                   tags.names classification_tags,
                   categories.names collection_categories,
                   bac.summary ai_summary,
                   bac.reader_fit ai_reader_fit,
                   bac.key_themes::text ai_key_themes,
                   bac.takeaways::text ai_takeaways,
                   bac.context ai_context,
                   ratings.average_rating::text average_rating,
                   ratings.ratings_count::text ratings_count
            FROM books b
            LEFT JOIN book_ai_content bac ON bac.book_id = b.id AND bac.is_current
            LEFT JOIN LATERAL (
              SELECT string_agg(a.name, ' | ' ORDER BY baj.position, a.name) names
              FROM book_authors_join baj
              JOIN authors a ON a.id = baj.author_id
              WHERE baj.book_id = b.id
            ) authors ON true
            LEFT JOIN LATERAL (
              SELECT string_agg(DISTINCT bt.display_name, ' | ' ORDER BY bt.display_name) names
              FROM book_tag_assignments bta
              JOIN book_tags bt ON bt.id = bta.tag_id
              WHERE bta.book_id = b.id
            ) tags ON true
            LEFT JOIN LATERAL (
              SELECT string_agg(DISTINCT bc.display_name, ' | ' ORDER BY bc.display_name) names
              FROM book_collections_join bcj
              JOIN book_collections bc ON bc.id = bcj.collection_id
              WHERE bcj.book_id = b.id AND bc.collection_type = 'CATEGORY'
            ) categories ON true
            LEFT JOIN LATERAL (
              SELECT max(average_rating) average_rating, max(ratings_count) ratings_count
              FROM book_external_ids
              WHERE book_id = b.id
            ) ratings ON true
            WHERE b.id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return Optional.<BookSimilarityBookSource>empty();
                }
                return Optional.of(new BookSimilarityBookSource(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("subtitle"),
                    rs.getString("authors"),
                    rs.getString("classification_tags"),
                    rs.getString("collection_categories"),
                    rs.getString("description"),
                    rs.getString("ai_summary"),
                    rs.getString("ai_reader_fit"),
                    rs.getString("ai_key_themes"),
                    rs.getString("ai_takeaways"),
                    rs.getString("ai_context"),
                    rs.getString("publisher"),
                    rs.getString("published_year"),
                    rs.getString("page_count"),
                    rs.getString("language"),
                    rs.getString("average_rating"),
                    rs.getString("ratings_count")
                ));
            },
            bookId
        );
    }

    /**
     * Loads the stored source hash for the active vector row.
     */
    @Transactional(readOnly = true)
    public Optional<String> fetchCurrentSourceHash(UUID bookId, String modelVersion, String profileHash) {
        String sourceHash = jdbcTemplate.query(
            """
            SELECT source_hash
            FROM book_similarity_vectors
            WHERE source_type = 'book'
              AND book_id = ?
              AND model_version = ?
              AND profile_hash = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("source_hash") : null,
            bookId,
            modelVersion,
            profileHash
        );
        return sourceHash == null || sourceHash.isBlank() ? Optional.empty() : Optional.of(sourceHash);
    }

    /**
     * Finds nearest persisted book-level vectors for the active similarity contract.
     *
     * @param sourceBookId canonical source book UUID
     * @param modelVersion active model version
     * @param profileHash active fusion profile hash
     * @param limit maximum neighbor count
     * @return ranked book IDs and cosine similarity scores
     */
    @Transactional(readOnly = true)
    public List<NearestBookRow> findNearestBooks(UUID sourceBookId, String modelVersion, String profileHash, int limit) {
        if (sourceBookId == null || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            WITH anchor AS (
              SELECT qwen_4b_fp16
              FROM book_similarity_vectors
              WHERE source_type = 'book'
                AND book_id = ?
                AND model_version = ?
                AND profile_hash = ?
                AND qwen_4b_fp16 IS NOT NULL
              LIMIT 1
            )
            SELECT candidate.book_id,
                   1 - (candidate.qwen_4b_fp16 <=> anchor.qwen_4b_fp16) AS similarity
            FROM anchor
            JOIN book_similarity_vectors candidate
              ON candidate.source_type = 'book'
             AND candidate.book_id <> ?
             AND candidate.model_version = ?
             AND candidate.profile_hash = ?
             AND candidate.qwen_4b_fp16 IS NOT NULL
            WHERE NOT EXISTS (
              SELECT 1
              FROM work_cluster_members source_member
              JOIN work_cluster_members candidate_member
                ON candidate_member.cluster_id = source_member.cluster_id
              WHERE source_member.book_id = ?
                AND candidate_member.book_id = candidate.book_id
            )
            ORDER BY candidate.qwen_4b_fp16 <=> anchor.qwen_4b_fp16
            LIMIT ?
            """,
            (rs, rowNum) -> new NearestBookRow(
                rs.getObject("book_id", UUID.class),
                rs.getDouble("similarity")
            ),
            sourceBookId,
            modelVersion,
            profileHash,
            sourceBookId,
            modelVersion,
            profileHash,
            sourceBookId,
            Math.max(1, limit)
        );
    }

    /**
     * Loads a cached section embedding for an exact model/input hash.
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

    /**
     * Upserts the searchable fused vector row for a book/profile/model contract.
     */
    @Transactional
    public void upsertFusedEmbedding(FusedEmbeddingRow row) {
        String sourceJson = toJson(row.sourceDocument().sourceJson());
        String sectionHashesJson = toJson(new SectionHashPayload(row.sourceDocument().sectionInputs().stream()
            .map(sectionInput -> new SectionHashEntry(sectionInput.sectionKey().key(), sectionInput.inputHash()))
            .toList()));
        String fusedHalfvecLiteral = BookSimilarityVectorLiteral.toHalfvecLiteral(row.fusedEmbedding());
        jdbcTemplate.update(
            """
            INSERT INTO book_similarity_vectors
              (id, book_id, profile_id, profile_hash, model, model_version, input_format, section_hash,
               section_input_hashes, embedding, source_text, source_json, source_hash, qwen_4b_fp16, computed_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS halfvec), ?, CAST(? AS jsonb), ?,
                    CAST(? AS halfvec), NOW(), NOW())
            ON CONFLICT (book_id, model_version, profile_hash)
            DO UPDATE SET model = EXCLUDED.model,
                          input_format = EXCLUDED.input_format,
                          section_hash = EXCLUDED.section_hash,
                          section_input_hashes = EXCLUDED.section_input_hashes,
                          embedding = EXCLUDED.embedding,
                          source_text = EXCLUDED.source_text,
                          source_json = EXCLUDED.source_json,
                          source_hash = EXCLUDED.source_hash,
                          qwen_4b_fp16 = EXCLUDED.qwen_4b_fp16,
                          computed_at = NOW(),
                          updated_at = NOW()
            """,
            IdGenerator.generateLong(),
            row.sourceDocument().bookId(),
            row.profileId(),
            row.profileHash(),
            row.model(),
            row.modelVersion(),
            INPUT_FORMAT,
            row.sourceDocument().sectionHash(),
            sectionHashesJson,
            fusedHalfvecLiteral,
            row.sourceDocument().sourceText(),
            sourceJson,
            row.sourceDocument().sourceHash(),
            fusedHalfvecLiteral
        );
    }

    private String toJson(BookSimilaritySourceDocument.SourceMetadata payload) {
        return serialize(payload, "source metadata");
    }

    private String toJson(SectionHashPayload payload) {
        return serialize(payload, "section hash payload");
    }

    private String serialize(BookSimilaritySourceDocument.SourceMetadata payload, String payloadName) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException jacksonException) {
            log.error("Failed to serialize book similarity {}", payloadName, jacksonException);
            throw new IllegalStateException("Failed to serialize book similarity " + payloadName, jacksonException);
        }
    }

    private String serialize(SectionHashPayload payload, String payloadName) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException jacksonException) {
            log.error("Failed to serialize book similarity {}", payloadName, jacksonException);
            throw new IllegalStateException("Failed to serialize book similarity " + payloadName, jacksonException);
        }
    }

    private static String preview(String text) {
        int previewLength = Math.min(512, text.length());
        return text.substring(0, previewLength);
    }

    /**
     * Row payload persisted as one fused embedding contract.
     */
    public record FusedEmbeddingRow(
        BookSimilaritySourceDocument sourceDocument,
        String profileId,
        String profileHash,
        String model,
        String modelVersion,
        List<Float> fusedEmbedding
    ) {
    }

    /**
     * Ranked nearest-neighbor row produced by the vector index.
     */
    public record NearestBookRow(UUID bookId, double similarity) {
    }

    private record SectionHashPayload(List<SectionHashEntry> sections) {
    }

    private record SectionHashEntry(String key, String inputHash) {
    }
}
