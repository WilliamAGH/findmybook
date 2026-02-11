package net.findmybook.adapters.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.domain.seo.BookSeoMetadataSnapshot;
import net.findmybook.domain.seo.BookSeoMetadataSnapshotReader;
import net.findmybook.util.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres adapter for versioned book SEO metadata records.
 *
 * <p>Owns SQL for {@code book_seo_metadata} so rendering and ingestion
 * workflows can consume typed snapshots without embedding SQL in services.</p>
 */
@Repository
public class BookSeoMetadataRepository implements BookSeoMetadataSnapshotReader {

    private final JdbcTemplate jdbcTemplate;

    public BookSeoMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads the current SEO metadata snapshot for the supplied book.
     *
     * @param bookId canonical book UUID
     * @return current snapshot when present
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<BookSeoMetadataSnapshot> fetchCurrent(UUID bookId) {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId is required");
        }

        String sql = """
            SELECT version_number, created_at, model, provider, seo_title, seo_description, prompt_hash
            FROM book_seo_metadata
            WHERE book_id = ? AND is_current = true
            ORDER BY version_number DESC
            LIMIT 1
            """;

        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return Optional.<BookSeoMetadataSnapshot>empty();
            }
            Timestamp createdAt = rs.getTimestamp("created_at");
            if (createdAt == null) {
                throw new IllegalStateException("Persisted SEO metadata missing created_at for book " + bookId);
            }
            return Optional.of(new BookSeoMetadataSnapshot(
                bookId,
                rs.getInt("version_number"),
                createdAt.toInstant(),
                rs.getString("model"),
                rs.getString("provider"),
                rs.getString("seo_title"),
                rs.getString("seo_description"),
                rs.getString("prompt_hash")
            ));
        }, bookId);
    }

    /**
     * Loads the prompt hash of the current SEO metadata snapshot when available.
     *
     * @param bookId canonical book UUID
     * @return current prompt hash when present and non-blank
     */
    @Transactional(readOnly = true)
    public Optional<String> fetchCurrentPromptHash(UUID bookId) {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId is required");
        }
        String promptHash = jdbcTemplate.query(
            """
            SELECT prompt_hash
            FROM book_seo_metadata
            WHERE book_id = ? AND is_current = true
            ORDER BY version_number DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("prompt_hash") : null,
            bookId
        );
        if (promptHash == null || promptHash.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(promptHash);
    }

    /**
     * Stores a new current SEO metadata snapshot and demotes prior current rows.
     *
     * @param bookId canonical book UUID
     * @param seoTitle generated SEO title
     * @param seoDescription generated SEO description
     * @param model model identifier
     * @param provider provider identifier
     * @param promptHash generation prompt hash
     * @return persisted current snapshot
     */
    @Transactional
    public BookSeoMetadataSnapshot insertNewCurrentVersion(UUID bookId,
                                                           String seoTitle,
                                                           String seoDescription,
                                                           String model,
                                                           String provider,
                                                           String promptHash) {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId is required");
        }
        if (seoTitle == null || seoTitle.isBlank()) {
            throw new IllegalArgumentException("seoTitle is required");
        }
        if (seoDescription == null || seoDescription.isBlank()) {
            throw new IllegalArgumentException("seoDescription is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }

        lockBook(bookId);
        int nextVersion = resolveNextVersion(bookId);

        jdbcTemplate.update(
            "UPDATE book_seo_metadata SET is_current = false WHERE book_id = ? AND is_current = true",
            bookId
        );
        jdbcTemplate.update(
            """
            INSERT INTO book_seo_metadata
              (id, book_id, version_number, is_current, seo_title, seo_description, model, provider, prompt_hash, created_at)
            VALUES (?, ?, ?, true, ?, ?, ?, ?, ?, NOW())
            """,
            IdGenerator.generateLong(),
            bookId,
            nextVersion,
            seoTitle,
            seoDescription,
            model,
            provider,
            promptHash
        );

        return fetchCurrent(bookId)
            .orElseThrow(() -> new IllegalStateException("Inserted SEO metadata could not be reloaded for book " + bookId));
    }

    private void lockBook(UUID bookId) {
        UUID lockedBookId = jdbcTemplate.query(
            "SELECT id FROM books WHERE id = ? FOR UPDATE",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            bookId
        );
        if (lockedBookId == null) {
            throw new IllegalStateException("Cannot lock missing book row for SEO metadata versioning: " + bookId);
        }
    }

    private int resolveNextVersion(UUID bookId) {
        Integer version = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version_number), 0) + 1 FROM book_seo_metadata WHERE book_id = ?",
            Integer.class,
            bookId
        );
        if (version == null || version <= 0) {
            throw new IllegalStateException("Failed to resolve SEO metadata version for book " + bookId);
        }
        return version;
    }
}
