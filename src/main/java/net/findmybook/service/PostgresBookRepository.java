package net.findmybook.service;

import tools.jackson.databind.ObjectMapper;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.CoverImages;
import net.findmybook.util.cover.CoverSourceMapper;
import net.findmybook.util.ApplicationConstants;
import static net.findmybook.util.ApplicationConstants.Database.Queries.BOOK_BY_SLUG;
import java.util.List;
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
    private static final String PROVIDER_GOOGLE_BOOKS = ApplicationConstants.Provider.GOOGLE_BOOKS;

    private final JdbcTemplate jdbcTemplate;
    private final BookLookupService bookLookupService;
    private final PostgresBookSectionHydrator sectionHydrator;

    @Autowired
    public PostgresBookRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, BookLookupService bookLookupService) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookLookupService = bookLookupService;
        this.sectionHydrator = new PostgresBookSectionHydrator(jdbcTemplate, objectMapper);
    }

    /**
     * Backward-compatible constructor for tests and legacy callers.
     * Creates a BookLookupService using the provided JdbcTemplate.
     * Note: This constructor will not publish migration events.
     */
    public PostgresBookRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookLookupService = new BookLookupService(jdbcTemplate);
        this.sectionHydrator = new PostgresBookSectionHydrator(jdbcTemplate, objectMapper);
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
                hydrateEditions(book, canonicalId);
                hydrateCover(book, canonicalId);
                hydrateRecommendations(book, canonicalId);
                hydrateProviderMetadata(book, canonicalId);
                
                // Set retrieval metadata for development mode tracking
                book.setRetrievedFrom("POSTGRES");
                book.setInPostgres(true);
                // Data source will be determined from collections/provider metadata
                hydrateDataSource(book, canonicalId);

                return Optional.of(book);
            });
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Postgres reader failed to load canonical book " + canonicalId, ex);
        }
    }

    private void hydrateEditions(Book book, UUID canonicalId) {
        String sql = """
                SELECT b.id::text as edition_id,
                       b.slug,
                       b.title,
                       b.isbn13,
                       b.isbn10,
                       b.publisher,
                       b.published_date,
                       wc.cluster_method,
                       wcm.confidence,
                       bei.external_id as google_books_id,
                       bil.s3_image_path,
                       -- Task #14: Quality facets for deterministic sorting
                       COALESCE(bil.is_high_resolution, false) AS has_high_res,
                       COALESCE((bil.width::bigint * bil.height::bigint), 0) AS cover_area
                FROM work_cluster_members wcm1
                JOIN work_cluster_members wcm ON wcm.cluster_id = wcm1.cluster_id
                JOIN books b ON b.id = wcm.book_id
                JOIN work_clusters wc ON wc.id = wcm.cluster_id
                LEFT JOIN book_external_ids bei
                       ON bei.book_id = b.id AND bei.source = '%s'
                LEFT JOIN LATERAL (
                       SELECT s3_image_path, url, source, width, height, is_high_resolution
                       FROM book_image_links
                       WHERE book_id = b.id
                       ORDER BY COALESCE(is_high_resolution, false) DESC,
                                COALESCE((width::bigint * height::bigint), 0) DESC,
                                created_at DESC
                       LIMIT 1
                ) bil ON TRUE
                WHERE wcm1.book_id = ?
                  AND wcm.book_id <> ?
                ORDER BY wcm.is_primary DESC,
                         has_high_res DESC,  -- Task #14: Sort by cover quality
                         cover_area DESC,
                         wcm.confidence DESC NULLS LAST,
                         b.published_date DESC NULLS LAST,
                         lower(b.title)
                """.formatted(PROVIDER_GOOGLE_BOOKS);
        try {
            List<Book.EditionInfo> editions = jdbcTemplate.query(sql,
                    ps -> {
                        ps.setObject(1, canonicalId);
                        ps.setObject(2, canonicalId);
                    },
                    (rs, rowNum) -> {
                        Book.EditionInfo info = new Book.EditionInfo();
                        info.setGoogleBooksId(rs.getString("google_books_id"));
                        info.setType(rs.getString("cluster_method"));
                        String slug = rs.getString("slug");
                        info.setIdentifier(slug != null && !slug.isBlank() ? slug : rs.getString("edition_id"));
                        info.setEditionIsbn13(rs.getString("isbn13"));
                        info.setEditionIsbn10(rs.getString("isbn10"));
                        java.sql.Date published = rs.getDate("published_date");
                        if (published != null) {
                            info.setPublishedDate(new java.util.Date(published.getTime()));
                        }
                        info.setCoverImageUrl(rs.getString("s3_image_path"));
                        return info;
                    });
            book.setOtherEditions(editions.isEmpty() ? List.of() : editions);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to hydrate editions for canonical book " + canonicalId, ex);
        }
    }

    private void hydrateCover(Book book, UUID canonicalId) {
        String sql = """
                SELECT image_type, url, source, s3_image_path, width, height, is_high_resolution
                FROM book_image_links
                WHERE book_id = ?
                ORDER BY
                    -- Prioritize actual quality metrics (Task #5)
                    COALESCE(is_high_resolution, false) DESC,
                    COALESCE((width::bigint * height::bigint), 0) DESC,
                    -- Fall back to image type as tiebreaker
                    CASE image_type
                        WHEN 'extraLarge' THEN 1
                        WHEN 'large' THEN 2
                        WHEN 'medium' THEN 3
                        WHEN 'small' THEN 4
                        WHEN 'thumbnail' THEN 5
                        WHEN 'smallThumbnail' THEN 6
                        ELSE 7
                    END,
                    created_at DESC
                LIMIT 2
                """;
        try {
            List<CoverCandidate> candidates = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> new CoverCandidate(
                    rs.getString("url"),
                    rs.getString("s3_image_path"),
                    rs.getString("source"),
                    rs.getObject("width", Integer.class),
                    rs.getObject("height", Integer.class),
                    rs.getObject("is_high_resolution", Boolean.class)
            ));
            if (candidates.isEmpty()) {
                return;
            }
            CoverCandidate primary = candidates.get(0);
            book.setExternalImageUrl(primary.url());
            book.setS3ImagePath(primary.s3Path());
            book.setCoverImageWidth(primary.width());
            book.setCoverImageHeight(primary.height());
            book.setIsCoverHighResolution(primary.highRes());
            CoverImages coverImages = new CoverImages(primary.url(),
                    candidates.size() > 1 ? candidates.get(1).url() : primary.url(),
                    toCoverSource(primary.source()));
            book.setCoverImages(coverImages);
            
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to hydrate cover for canonical book " + canonicalId, ex);
        }
    }

    private void hydrateRecommendations(Book book, UUID canonicalId) {
        String sql = """
                SELECT recommended_book_id::text
                FROM book_recommendations
                WHERE source_book_id = ?
                ORDER BY score DESC NULLS LAST, created_at DESC
                LIMIT 20
                """;
        try {
            List<String> recommendations = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString("recommended_book_id"));
            book.setCachedRecommendationIds(recommendations == null || recommendations.isEmpty() ? List.of() : recommendations);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to hydrate recommendations for canonical book " + canonicalId, ex);
        }
    }

    private void hydrateProviderMetadata(Book book, UUID canonicalId) {
        String sql = """
                SELECT info_link, preview_link, web_reader_link, purchase_link,
                       average_rating, ratings_count, list_price, currency_code, provider_asin
                FROM book_external_ids
                WHERE book_id = ?
                ORDER BY CASE WHEN source = '%s' THEN 0 ELSE 1 END, created_at DESC
                LIMIT 1
                """.formatted(PROVIDER_GOOGLE_BOOKS);
        try {
            jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), rs -> {
                if (!rs.next()) {
                    return null;
                }
                book.setInfoLink(rs.getString("info_link"));
                book.setPreviewLink(rs.getString("preview_link"));
                book.setWebReaderLink(rs.getString("web_reader_link"));
                book.setPurchaseLink(rs.getString("purchase_link"));
                java.math.BigDecimal avgDecimal = rs.getBigDecimal("average_rating");
                if (avgDecimal != null) {
                    book.setAverageRating(avgDecimal.doubleValue());
                    book.setHasRatings(Boolean.TRUE);
                }
                Integer ratingsCount = rs.getObject("ratings_count", Integer.class);
                if (ratingsCount != null) {
                    book.setRatingsCount(ratingsCount);
                    book.setHasRatings(ratingsCount > 0);
                }
                java.math.BigDecimal listPrice = rs.getBigDecimal("list_price");
                if (listPrice != null) {
                    book.setListPrice(listPrice.doubleValue());
                }
                String currency = rs.getString("currency_code");
                if (currency != null && !currency.isBlank()) {
                    book.setCurrencyCode(currency);
                }
                String asin = rs.getString("provider_asin");
                if (asin != null && !asin.isBlank()) {
                    book.setAsin(asin);
                }
                return null;
            });
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to hydrate provider metadata for canonical book " + canonicalId, ex);
        }
    }

    private void hydrateDataSource(Book book, UUID canonicalId) {
        // Check if book is part of NYT bestseller list
        String nytCheck = """
                SELECT 1
                FROM book_collections_join bcj
                JOIN book_collections bc ON bc.id = bcj.collection_id
                WHERE bcj.book_id = ?
                  AND bc.source = 'NYT'
                LIMIT 1
                """;
        try {
            List<Integer> nytResults = jdbcTemplate.query(nytCheck, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> 1);
            if (!nytResults.isEmpty()) {
                book.setDataSource("NYT");
                return;
            }
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to check NYT source for canonical book " + canonicalId, ex);
        }
        
        // Check external provider IDs to determine primary data source
        String providerCheck = """
                SELECT source
                FROM book_external_ids
                WHERE book_id = ?
                ORDER BY created_at ASC
                LIMIT 1
                """;
        try {
            List<String> sources = jdbcTemplate.query(providerCheck, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString("source"));
            if (!sources.isEmpty()) {
                book.setDataSource(sources.get(0));
            }
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to check provider source for canonical book " + canonicalId, ex);
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

    /**
     * Checks if book needs S3 migration and publishes event if needed.
     * Bug #4 implementation: Migrate external cover URLs to S3 storage.
     */
    /**
     * @deprecated Deprecated 2025-10-01. Use {@link CoverSourceMapper#toCoverImageSource(String)} instead.
     */
    @Deprecated(since = "2025-10-01", forRemoval = true)
    private CoverImageSource toCoverSource(String raw) {
        return CoverSourceMapper.toCoverImageSource(raw);
    }

    private record CoverCandidate(String url,
                                  String s3Path,
                                  String source,
                                  Integer width,
                                  Integer height,
                                  Boolean highRes) {
    }
}
