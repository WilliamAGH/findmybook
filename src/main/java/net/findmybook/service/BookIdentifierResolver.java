package net.findmybook.service;

import net.findmybook.dto.BookDetail;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.UuidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.util.StringUtils;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves user-facing identifiers (slug, ISBN, external ID) to canonical UUIDs.
 * Centralizing this logic prevents duplicate lookup heuristics across controllers.
 */
@Service
public class BookIdentifierResolver {

    private static final Logger log = LoggerFactory.getLogger(BookIdentifierResolver.class);

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

    public Optional<UUID> resolveToUuid(String identifier) {
        return resolveCanonicalId(identifier)
            .map(UuidUtils::parseUuidOrNull)
            .filter(uuid -> uuid != null);
    }

    public Optional<String> resolveCanonicalId(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Optional.empty();
        }

        String trimmed = identifier.trim();

        UUID uuid = UuidUtils.parseUuidOrNull(trimmed);
        if (uuid != null) {
            return resolveToPrimaryEdition(uuid.toString());
        }

        // Try slug resolution via Postgres projections
        Optional<BookDetail> bySlug = bookQueryRepository.fetchBookDetailBySlug(trimmed);
        if (bySlug.isPresent() && StringUtils.hasText(bySlug.get().id())) {
            return resolveToPrimaryEdition(bySlug.get().id());
        }

        if (bookLookupService == null) {
            return Optional.empty();
        }

        return bookLookupService.findBookIdByExternalIdentifier(trimmed)
            .or(() -> bookLookupService.findBookIdByIsbn(trimmed))
            .or(() -> bookLookupService.findBookById(trimmed))
            .flatMap(this::resolveToPrimaryEdition);
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

        String primaryId;
        try {
            primaryId = jdbcTemplate.query(
                """
                SELECT primary_wcm.book_id::text
                FROM work_cluster_members wcm
                JOIN work_cluster_members primary_wcm
                  ON primary_wcm.cluster_id = wcm.cluster_id
                 AND primary_wcm.is_primary = true
                WHERE wcm.book_id = ?::uuid
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                uuid
            );
        } catch (DataAccessException ex) {
            log.warn("Primary-edition cluster lookup failed for bookId={}; using original identifier. error={}",
                bookId, ex.getMessage());
            return Optional.of(bookId);
        }

        if (StringUtils.hasText(primaryId)) {
            return Optional.of(primaryId);
        }

        return Optional.of(bookId);
    }
}
