package net.findmybook.service;

import net.findmybook.dto.BookDetail;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.UuidUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.util.StringUtils;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves user-facing identifiers (slug, ISBN, external ID) to canonical UUIDs.
 * Centralizing this logic prevents duplicate lookup heuristics across controllers.
 *
 * <p><strong>Exception strategy:</strong> {@link DataAccessException} from cluster
 * lookups propagates uncaught. Spring Boot's default error handling converts it to
 * an HTTP 500 response. Callers that need graceful degradation must catch explicitly.
 */
@Service
public class BookIdentifierResolver {

    private final BookLookupService bookLookupService;
    private final BookQueryRepository bookQueryRepository;
    private final JdbcTemplate jdbcTemplate;

    public BookIdentifierResolver(BookLookupService bookLookupService,
                                  BookQueryRepository bookQueryRepository,
                                  JdbcTemplate jdbcTemplate) {
        this.bookLookupService = bookLookupService;
        this.bookQueryRepository = bookQueryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Resolves a user-facing identifier to a canonical UUID.
     *
     * @param identifier slug, ISBN, external ID, or UUID string
     * @return resolved UUID, or empty if the identifier cannot be matched
     * @throws DataAccessException if the work-cluster database lookup fails
     */
    public Optional<UUID> resolveToUuid(String identifier) throws DataAccessException {
        return resolveCanonicalId(identifier)
            .map(UuidUtils::parseUuidOrNull)
            .filter(Objects::nonNull);
    }

    /**
     * Resolves a user-facing identifier to the exact matching book UUID without changing editions.
     * Detail-scoped mutations use this contract so data is written to the same book rendered by
     * the detail API rather than to a work-cluster primary edition.
     *
     * @param identifier slug, ISBN, external ID, or UUID string
     * @return exact resolved UUID, or empty if the identifier cannot be matched
     */
    public Optional<UUID> resolveExactBookUuid(String identifier) {
        return resolveExactBookId(identifier)
            .map(UuidUtils::parseUuidOrNull)
            .filter(Objects::nonNull);
    }

    /**
     * Resolves a user-facing identifier to a canonical book ID string.
     *
     * @param identifier slug, ISBN, external ID, or UUID string
     * @return canonical book ID, or empty if the identifier cannot be matched
     * @throws DataAccessException if the work-cluster database lookup fails
     */
    public Optional<String> resolveCanonicalId(String identifier) throws DataAccessException {
        return resolveExactBookId(identifier).flatMap(this::resolveToPrimaryEdition);
    }

    private Optional<String> resolveExactBookId(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Optional.empty();
        }

        String trimmed = identifier.trim();

        UUID uuid = UuidUtils.parseUuidOrNull(trimmed);
        if (uuid != null) {
            return Optional.of(uuid.toString());
        }

        // Try slug resolution via Postgres projections
        Optional<BookDetail> bySlug = bookQueryRepository.fetchBookDetailBySlug(trimmed);
        if (bySlug.isPresent() && StringUtils.hasText(bySlug.get().id())) {
            return Optional.of(bySlug.get().id());
        }

        if (bookLookupService == null) {
            return Optional.empty();
        }

        return bookLookupService.findBookIdByExternalIdentifier(trimmed)
            .or(() -> bookLookupService.findBookIdByIsbn(trimmed));
    }

    /**
     * Resolves a database book identifier to the primary edition within its work cluster.
     *
     * @param bookId raw book identifier resolved from user-facing inputs
     * @return canonical primary edition identifier, or the original when no cluster exists
     */
    private Optional<String> resolveToPrimaryEdition(String bookId) {
        if (!StringUtils.hasText(bookId) || jdbcTemplate == null) {
            return Optional.ofNullable(bookId);
        }

        UUID uuid = UuidUtils.parseUuidOrNull(bookId);
        if (uuid == null) {
            return Optional.of(bookId);
        }

        String primaryId = jdbcTemplate.query(
            """
            SELECT primary_wcm.book_id::text
            FROM work_cluster_members wcm
            JOIN work_cluster_members primary_wcm
              ON primary_wcm.cluster_id = wcm.cluster_id
             AND primary_wcm.is_primary = true
            WHERE wcm.book_id = ?::uuid
            ORDER BY (primary_wcm.book_id = wcm.book_id) DESC,
                     wcm.cluster_id ASC,
                     primary_wcm.book_id ASC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            uuid
        );

        if (StringUtils.hasText(primaryId)) {
            return Optional.of(primaryId);
        }

        return Optional.of(bookId);
    }
}
