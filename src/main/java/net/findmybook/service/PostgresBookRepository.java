package net.findmybook.service;

import tools.jackson.databind.ObjectMapper;
import net.findmybook.model.Book;
import static net.findmybook.util.ApplicationConstants.Database.Queries.BOOK_BY_SLUG;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Repository responsible for hydrating full {@link Book} aggregates directly from Postgres.
 *
 * <p>This is extracted from {@link BookDataOrchestrator} so that the orchestrator can delegate
 * complex JDBC logic while keeping a slim facade. All methods remain optional-aware to preserve
 * existing behavior.</p>
 */
@Component
@ConditionalOnClass(JdbcTemplate.class)
public class PostgresBookRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresBookRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final BookLookupService bookLookupService;
    private final PostgresBookSectionHydrator sectionHydrator;
    private final PostgresBookDetailHydrator detailHydrator;

    @Autowired
    public PostgresBookRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, BookLookupService bookLookupService) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookLookupService = bookLookupService;
        this.sectionHydrator = new PostgresBookSectionHydrator(jdbcTemplate, objectMapper);
        this.detailHydrator = new PostgresBookDetailHydrator(jdbcTemplate);
    }

    /**
     * Backward-compatible constructor for tests and legacy callers.
     * Creates a BookLookupService using the provided JdbcTemplate.
     */
    public PostgresBookRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookLookupService = new BookLookupService(jdbcTemplate);
        this.sectionHydrator = new PostgresBookSectionHydrator(jdbcTemplate, objectMapper);
        this.detailHydrator = new PostgresBookDetailHydrator(jdbcTemplate);
    }

    Optional<Book> fetchByCanonicalId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        UUID canonicalId;
        try {
            canonicalId = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return loadAggregate(canonicalId);
    }

    Optional<Book> fetchBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return queryForUuid(BOOK_BY_SLUG, slug.trim())
                .flatMap(this::loadAggregate);
    }

    Optional<Book> fetchByIsbn13(String isbn13) {
        return lookupAndHydrate(bookLookupService.findBookIdByIsbn(isbn13));
    }

    Optional<Book> fetchByIsbn10(String isbn10) {
        return lookupAndHydrate(bookLookupService.findBookIdByIsbn(isbn10));
    }

    Optional<Book> fetchByExternalId(String externalId) {
        return lookupAndHydrate(bookLookupService.findBookIdByExternalIdentifier(externalId));
    }

    private Optional<Book> lookupAndHydrate(Optional<String> idOptional) {
        return idOptional.flatMap(this::loadAggregate);
    }








    private Optional<Book> loadAggregate(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) {
            return Optional.empty();
        }
        try {
            return loadAggregate(UUID.fromString(canonicalId));
        } catch (IllegalArgumentException ex) {
            LOG.debug("Value {} is not a valid UUID", canonicalId);
            return Optional.empty();
        }
    }

    private Optional<Book> loadAggregate(UUID canonicalId) {
        String sql = """
                SELECT id::text, slug, title, description, isbn10, isbn13, published_date, language, publisher, page_count
                FROM books
                WHERE id = ?
                """;
        try {
            return jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), rs -> {
                if (!rs.next()) {
                    return Optional.<Book>empty();
                }
                Book book = new Book();
                book.setId(rs.getString("id"));
                book.setSlug(rs.getString("slug"));
                book.setTitle(rs.getString("title"));
                book.setDescription(rs.getString("description"));
                book.setIsbn10(rs.getString("isbn10"));
                book.setIsbn13(rs.getString("isbn13"));
                java.sql.Date published = rs.getDate("published_date");
                if (published != null) {
                    book.setPublishedDate(new java.util.Date(published.getTime()));
                }
                book.setLanguage(rs.getString("language"));
                book.setPublisher(rs.getString("publisher"));
                Integer pageCount = (Integer) rs.getObject("page_count");
                book.setPageCount(pageCount);

                sectionHydrator.hydrateAuthors(book, canonicalId);
                sectionHydrator.hydrateCategories(book, canonicalId);
                sectionHydrator.hydrateCollections(book, canonicalId);
                sectionHydrator.hydrateDimensions(book, canonicalId);
                sectionHydrator.hydrateRawPayload(book, canonicalId);
                sectionHydrator.hydrateTags(book, canonicalId);
                detailHydrator.hydrateEditions(book, canonicalId);
                detailHydrator.hydrateCover(book, canonicalId);
                detailHydrator.hydrateRecommendations(book, canonicalId);
                detailHydrator.hydrateProviderMetadata(book, canonicalId);

                book.setRetrievedFrom("POSTGRES");
                book.setInPostgres(true);
                detailHydrator.hydrateDataSource(book, canonicalId);

                return Optional.of(book);
            });
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Postgres reader failed to load canonical book " + canonicalId, ex);
        }
    }

    private Optional<UUID> queryForUuid(String sql, Object param) {
        try {
            UUID result = jdbcTemplate.queryForObject(sql, UUID.class, param);
            return Optional.ofNullable(result);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Postgres lookup failed for value " + param, ex);
        }
    }

}
