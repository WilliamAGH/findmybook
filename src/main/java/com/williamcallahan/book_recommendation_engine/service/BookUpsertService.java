package com.williamcallahan.book_recommendation_engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.book_recommendation_engine.dto.BookAggregate;
import com.williamcallahan.book_recommendation_engine.service.event.BookUpsertEvent;
import com.williamcallahan.book_recommendation_engine.service.image.CoverPersistenceService;
import com.williamcallahan.book_recommendation_engine.util.CategoryNormalizer;
import com.williamcallahan.book_recommendation_engine.util.DimensionParser;
import com.williamcallahan.book_recommendation_engine.util.IdGenerator;
import com.williamcallahan.book_recommendation_engine.util.IsbnUtils;
import com.williamcallahan.book_recommendation_engine.util.UrlUtils;
import com.williamcallahan.book_recommendation_engine.util.cover.CoverQuality;
import com.williamcallahan.book_recommendation_engine.util.cover.ImageDimensionUtils;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single Source of Truth (SSOT) for ALL book database writes.
 * <p>
 * This service is THE ONLY place that writes to:
 * - books table
 * - authors table
 * - book_authors_join table
 * - book_external_ids table
 * - book_image_links table
 * - book_dimensions table (if dimensions provided)
 * - events_outbox table (transactional event publishing)
 * <p>
 * Key principles:
 * - UPSERT everywhere (INSERT ... ON CONFLICT DO UPDATE)
 * - Update guards: only update if new data is fresher
 * - Transactional: all writes in single transaction
 * - Emits events via outbox pattern for WebSocket delivery
 * - Generates stable, unique slugs
 * <p>
 * Replaces logic from:
 * - CanonicalBookPersistenceService (entire class)
 * - BookSupplementalPersistenceService (persistAuthors, persistCategories)
 * <p>
 * Usage:
 * <pre>
 * BookAggregate aggregate = googleBooksMapper.map(json);
 * UpsertResult result = bookUpsertService.upsert(aggregate);
 * UUID bookId = result.getBookId();
 * String slug = result.getSlug();
 * </pre>
 */
@Service
@Slf4j
public class BookUpsertService {
    
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final BookCollectionPersistenceService collectionPersistenceService;
    private final CoverPersistenceService coverPersistenceService;

    public BookUpsertService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ApplicationEventPublisher eventPublisher,
        BookCollectionPersistenceService collectionPersistenceService,
        CoverPersistenceService coverPersistenceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.collectionPersistenceService = collectionPersistenceService;
        this.coverPersistenceService = coverPersistenceService;
    }
    
    /**
     * Upserts a book from normalized external data.
     * <p>
     * This is the primary entry point for all book persistence.
     * ALL writes happen in a single transaction.
     * <p>
     * Process:
     * 1. Determine book_id (existing or new UUID v7)
     * 2. Generate unique slug
     * 3. UPSERT books table
     * 4. UPSERT authors and book_authors_join
     * 5. UPSERT book_external_ids
     * 6. UPSERT book_image_links
     * 7. UPSERT book_dimensions (if provided)
     * 8. Emit outbox event (same transaction)
     * <p>
     * Thread-safe and can be called concurrently.
     *
     * @param aggregate Normalized book data from external source
     * @return UpsertResult with bookId, slug, and isNew flag
     */
    @Transactional
    public UpsertResult upsert(BookAggregate aggregate) {
        if (aggregate == null) {
            throw new IllegalArgumentException("BookAggregate cannot be null");
        }
        
        if (aggregate.getTitle() == null || aggregate.getTitle().isBlank()) {
            throw new IllegalArgumentException("Book title is required");
        }
        
        log.debug("Upserting book: title='{}', isbn13='{}', source='{}'", 
            aggregate.getTitle(), 
            aggregate.getIsbn13(),
            aggregate.getIdentifiers() != null ? aggregate.getIdentifiers().getSource() : "UNKNOWN"
        );
        
        // 1. Determine book_id (check if book already exists)
        UUID bookId = findOrCreateBookId(aggregate);
        boolean isNew = bookId == null;
        
        if (isNew) {
            bookId = UUID.fromString(IdGenerator.uuidV7());
            log.info("Creating new book: id={}, title='{}'", bookId, aggregate.getTitle());
        } else {
            log.info("Updating existing book: id={}, title='{}'", bookId, aggregate.getTitle());
        }
        
        // 2. Generate unique slug
        String slug = ensureUniqueSlug(aggregate.getSlugBase(), bookId, isNew);
        
        // 3. UPSERT books table
        upsertBookRecord(bookId, aggregate, slug);
        
        // 4. UPSERT authors
        if (aggregate.getAuthors() != null && !aggregate.getAuthors().isEmpty()) {
            upsertAuthors(bookId, aggregate.getAuthors());
        }
        
        // 5. UPSERT external IDs
        if (aggregate.getIdentifiers() != null) {
            upsertExternalIds(bookId, aggregate.getIdentifiers());
        }
        
        // 6. UPSERT image links with enhanced metadata (dimensions, high-res detection)
        if (aggregate.getIdentifiers() != null && aggregate.getIdentifiers().getImageLinks() != null) {
            upsertImageLinksEnhanced(bookId, aggregate.getIdentifiers());
        }
        
        // 7. UPSERT categories
        if (aggregate.getCategories() != null && !aggregate.getCategories().isEmpty()) {
            upsertCategories(bookId, aggregate.getCategories());
        }
        
        // 8. UPSERT dimensions (if provided)
        if (aggregate.getDimensions() != null) {
            upsertDimensions(bookId, aggregate.getDimensions());
        }
        
        Map<String, String> normalizedImageLinks = collectNormalizedImageLinks(aggregate);
        String source = aggregate.getIdentifiers() != null ? aggregate.getIdentifiers().getSource() : null;
        String context = source != null ? source : "POSTGRES_UPSERT";
        String canonicalImageUrl = selectPreferredImageUrl(normalizedImageLinks);

        if (isNew) {
            clusterNewBook(bookId, aggregate);
        }

        // 9. Emit outbox event (transactional) and publish in-process event
        emitOutboxEvent(bookId, slug, aggregate.getTitle(), isNew, context, canonicalImageUrl, normalizedImageLinks, source);
        publishBookUpsertEvent(bookId, slug, aggregate.getTitle(), isNew, context, canonicalImageUrl, normalizedImageLinks, source);
        
        log.info("Successfully upserted book: id={}, slug='{}', isNew={}", bookId, slug, isNew);
        
        return UpsertResult.builder()
            .bookId(bookId)
            .slug(slug)
            .isNew(isNew)
            .build();
    }
    
    /**
     * Find existing book ID or return null for new book.
     * Uses PostgreSQL advisory locks to prevent race conditions during concurrent upserts.
     *
     * Lookup strategy (in order):
     * Task #9: Prioritize universal identifiers (ISBN) over source-specific ones
     * 1. ISBN-13 (universal identifier)
     * 2. ISBN-10 (universal identifier)
     * 3. External ID (source-specific identifier)
     *
     * For books WITH identifiers: Acquires advisory lock based on stable hash of identifiers
     * to prevent duplicate inserts from concurrent requests.
     *
     * For books WITHOUT identifiers: Falls back to unsafe path (no lock protection).
     */
    private UUID findOrCreateBookId(BookAggregate aggregate) {
        // Compute lock key from book identifiers
        Long lockKey = computeBookLockKey(aggregate);

        if (lockKey == null) {
            // No identifiers available - fall back to unsafe path
            log.warn("Book has no identifiers (ISBN/externalId) - cannot use advisory lock. Race condition possible.");
            return findOrCreateBookIdUnsafe(aggregate);
        }

        // Acquire PostgreSQL advisory lock for this book
        // Lock is automatically released when transaction commits/rolls back
        try {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
            log.debug("Acquired advisory lock {} for book lookup", lockKey);
        } catch (Exception e) {
            log.warn("Failed to acquire advisory lock {}, proceeding without lock: {}", lockKey, e.getMessage());
            return findOrCreateBookIdUnsafe(aggregate);
        }

        // Now safely query within locked section
        return findOrCreateBookIdUnsafe(aggregate);
    }

    /**
     * Computes a stable numeric lock key from book identifiers.
     * Priority order: ISBN-13 > ISBN-10 > External ID
     *
     * <p>Task #9: ISBNs are normalized using IsbnUtils.sanitize() to remove hyphens, spaces,
     * and other formatting characters. This ensures that "978-0-545-01022-1" and "9780545010221"
     * generate the SAME lock key, preventing race conditions and duplicate book creation when
     * different sources format ISBNs differently.</p>
     *
     * Returns null if book has no identifiers (title-only books).
     *
     * Uses Java's String.hashCode() which is stable across JVM instances
     * and deterministic for the same input string.
     */
    private Long computeBookLockKey(BookAggregate aggregate) {
        String lockString = null;

        // Priority 1: ISBN-13 (most stable, globally unique)
        // Sanitize to ensure "978-0-545-01022-1" and "9780545010221" produce same lock
        String sanitizedIsbn13 = IsbnUtils.sanitize(aggregate.getIsbn13());
        if (sanitizedIsbn13 != null) {
            lockString = "ISBN13:" + sanitizedIsbn13;
        }
        // Priority 2: ISBN-10 (stable, globally unique)
        // Sanitize to ensure "0-545-01022-6" and "0545010226" produce same lock
        else {
            String sanitizedIsbn10 = IsbnUtils.sanitize(aggregate.getIsbn10());
            if (sanitizedIsbn10 != null) {
                lockString = "ISBN10:" + sanitizedIsbn10;
            }
        }

        // Priority 3: External ID (source-specific unique identifier)
        if (lockString == null && aggregate.getIdentifiers() != null) {
            String source = aggregate.getIdentifiers().getSource();
            String externalId = aggregate.getIdentifiers().getExternalId();
            if (source != null && externalId != null && !externalId.isBlank()) {
                lockString = source + ":" + externalId.trim();
            }
        }

        if (lockString == null) {
            return null; // No identifiers available
        }

        // Convert string to stable 64-bit integer for pg_advisory_xact_lock
        // Use hashCode() which is deterministic for same input
        long hash = (long) lockString.hashCode();

        // Ensure positive value (pg_advisory_xact_lock accepts bigint)
        return Math.abs(hash);
    }

    /**
     * Unsafe book ID lookup without advisory lock protection.
     * Used as fallback for books without identifiers or when lock acquisition fails.
     *
     * Task #9: Prioritizes universal identifiers (ISBN) over source-specific ones to prevent
     * duplicate book creation when same ISBN comes from different sources with different external IDs.
     *
     * RACE CONDITION POSSIBLE: Two concurrent requests may create duplicate books.
     */
    private UUID findOrCreateBookIdUnsafe(BookAggregate aggregate) {
        String sanitizedIsbn13 = IsbnUtils.sanitize(aggregate.getIsbn13());
        if (sanitizedIsbn13 != null) {
            UUID existing = findBookByIsbn13(sanitizedIsbn13);
            if (existing != null) {
                log.debug("Found existing book {} by ISBN-13 {}", existing, sanitizedIsbn13);
                return existing;
            }
        }

        String sanitizedIsbn10 = IsbnUtils.sanitize(aggregate.getIsbn10());
        if (sanitizedIsbn10 != null) {
            UUID existing = findBookByIsbn10(sanitizedIsbn10);
            if (existing != null) {
                log.debug("Found existing book {} by ISBN-10 {}", existing, sanitizedIsbn10);
                return existing;
            }
        }

        if (aggregate.getIdentifiers() != null) {
            String source = aggregate.getIdentifiers().getSource();
            String externalId = aggregate.getIdentifiers().getExternalId();

            if (source != null && externalId != null) {
                UUID existing = findBookByExternalId(source, externalId);
                if (existing != null) {
                    log.debug("Found existing book {} by external identifier {}/{}", existing, source, externalId);
                    return existing;
                }
            }
        }

        UUID clusteredMatch = findBookByWorkCluster(sanitizedIsbn13);
        if (clusteredMatch != null) {
            log.debug("Found existing book {} via work cluster lookup", clusteredMatch);
            return clusteredMatch;
        }

        return null; // Book doesn't exist
    }
    
    /**
     * Find book by external ID.
     */
    private UUID findBookByExternalId(String source, String externalId) {
        try {
            return jdbcTemplate.query(
                "SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1",
                rs -> rs.next() ? (UUID) rs.getObject("book_id") : null,
                source, externalId
            );
        } catch (Exception e) {
            log.debug("Error finding book by external ID: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Find book by ISBN-13.
     * <p>Task #9: Sanitizes ISBN before lookup to match normalized storage format.
     * Ensures "978-0-545-01022-1" finds books stored as "9780545010221".</p>
     */
    private UUID findBookByIsbn13(String isbn13) {
        String sanitized = IsbnUtils.sanitize(isbn13);
        if (sanitized == null) {
            return null;
        }

        try {
            return jdbcTemplate.query(
                "SELECT id FROM books WHERE isbn13 = ? LIMIT 1",
                rs -> rs.next() ? (UUID) rs.getObject("id") : null,
                sanitized
            );
        } catch (Exception e) {
            log.debug("Error finding book by ISBN-13: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Find book by ISBN-10.
     * <p>Task #9: Sanitizes ISBN before lookup to match normalized storage format.
     * Ensures "0-545-01022-6" finds books stored as "0545010226".</p>
     */
    private UUID findBookByIsbn10(String isbn10) {
        String sanitized = IsbnUtils.sanitize(isbn10);
        if (sanitized == null) {
            return null;
        }

        try {
            return jdbcTemplate.query(
                "SELECT id FROM books WHERE isbn10 = ? LIMIT 1",
                rs -> rs.next() ? (UUID) rs.getObject("id") : null,
                sanitized
            );
        } catch (Exception e) {
            log.debug("Error finding book by ISBN-10: {}", e.getMessage());
            return null;
        }
    }

    private UUID findBookByWorkCluster(String sanitizedIsbn13) {
        if (sanitizedIsbn13 == null) {
            return null;
        }

        String isbnPrefix = extractIsbnPrefix(sanitizedIsbn13);
        if (isbnPrefix == null) {
            return null;
        }

        try {
            return jdbcTemplate.query(
                """
                SELECT wcm.book_id
                FROM work_clusters wc
                JOIN work_cluster_members wcm ON wcm.cluster_id = wc.id
                WHERE wc.isbn_prefix = ?
                  AND wcm.is_primary = true
                LIMIT 1
                """,
                rs -> rs.next() ? (UUID) rs.getObject("book_id") : null,
                isbnPrefix
            );
        } catch (Exception e) {
            log.debug("Error finding book by work cluster prefix {}: {}", isbnPrefix, e.getMessage());
            return null;
        }
    }

    /**
     * Performs immediate work-cluster maintenance for a freshly persisted book.
     * <p>
     * This mirrors the scheduled {@link com.williamcallahan.book_recommendation_engine.scheduler.WorkClusterScheduler}
     * but runs in-line so that new editions join their work family within the same transaction.
     *
     * @param bookId   canonical book identifier generated during the upsert
     * @param aggregate normalized payload used to determine ISBN and provider identifiers
     */
    private void clusterNewBook(UUID bookId, BookAggregate aggregate) {
        if (bookId == null) {
            return;
        }

        String sanitizedIsbn13 = IsbnUtils.sanitize(aggregate.getIsbn13());
        if (sanitizedIsbn13 != null) {
            try {
                jdbcTemplate.query(
                    "SELECT cluster_single_book_by_isbn(?)",
                    ps -> ps.setObject(1, bookId),
                    rs -> null
                );
                log.debug("Triggered ISBN-based clustering for book {}", bookId);
            } catch (Exception ex) {
                log.warn("Failed to run cluster_single_book_by_isbn for book {}: {}", bookId, ex.getMessage());
            }
        }

        if (aggregate.getIdentifiers() != null
            && aggregate.getIdentifiers().getGoogleCanonicalId() != null
            && !aggregate.getIdentifiers().getGoogleCanonicalId().isBlank()) {
            try {
                jdbcTemplate.query(
                    "SELECT cluster_single_book_by_google_id(?)",
                    ps -> ps.setObject(1, bookId),
                    rs -> null
                );
                log.debug("Triggered Google canonical clustering for book {}", bookId);
            } catch (Exception ex) {
                log.warn("Failed to run cluster_single_book_by_google_id for book {}: {}", bookId, ex.getMessage());
            }
        }
    }
    
    /**
     * Generate unique slug for book.
     * If book exists, keeps existing slug (stability).
     * If new or slug collision, generates unique slug.
     */
    private String ensureUniqueSlug(String slugBase, UUID bookId, boolean isNew) {
        // For existing books, keep current slug
        if (!isNew) {
            try {
                String existing = jdbcTemplate.query(
                    "SELECT slug FROM books WHERE id = ?",
                    rs -> rs.next() ? rs.getString("slug") : null,
                    bookId
                );
                if (existing != null && !existing.isBlank()) {
                    return existing;
                }
            } catch (Exception e) {
                log.debug("Error fetching existing slug: {}", e.getMessage());
            }
        }
        
        // Generate new slug
        if (slugBase == null || slugBase.isBlank()) {
            return null;
        }
        
        // Use database function to ensure uniqueness
        try {
            return jdbcTemplate.queryForObject(
                "SELECT ensure_unique_slug(?)",
                String.class,
                slugBase
            );
        } catch (Exception e) {
            log.warn("Error generating unique slug, using base: {}", e.getMessage());
            return slugBase;
        }
    }
    
    /**
     * UPSERT books table with COALESCE guards.
     * Only updates if new data is present (COALESCE keeps existing if new is null).
     * <p>Task #9: Sanitizes ISBNs before persistence to ensure consistent formatting.
     * All ISBNs are stored without hyphens or spaces.</p>
     */
    private void upsertBookRecord(UUID bookId, BookAggregate aggregate, String slug) {
        Date sqlDate = aggregate.getPublishedDate() != null
            ? Date.valueOf(aggregate.getPublishedDate())
            : null;

        // Task #9: Sanitize ISBNs to remove hyphens/spaces, then convert empty to null
        String isbn10 = nullIfBlank(IsbnUtils.sanitize(aggregate.getIsbn10()));
        String isbn13 = nullIfBlank(IsbnUtils.sanitize(aggregate.getIsbn13()));
        
        jdbcTemplate.update(
            """
            INSERT INTO books (
                id, title, subtitle, description, isbn10, isbn13, 
                published_date, language, publisher, page_count, slug, 
                created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                subtitle = COALESCE(NULLIF(EXCLUDED.subtitle, ''), books.subtitle),
                description = COALESCE(NULLIF(EXCLUDED.description, ''), books.description),
                isbn10 = COALESCE(NULLIF(EXCLUDED.isbn10, ''), books.isbn10),
                isbn13 = COALESCE(NULLIF(EXCLUDED.isbn13, ''), books.isbn13),
                published_date = COALESCE(EXCLUDED.published_date, books.published_date),
                language = COALESCE(NULLIF(EXCLUDED.language, ''), books.language),
                publisher = COALESCE(NULLIF(EXCLUDED.publisher, ''), books.publisher),
                page_count = COALESCE(EXCLUDED.page_count, books.page_count),
                slug = COALESCE(NULLIF(EXCLUDED.slug, ''), books.slug),
                updated_at = NOW()
            """,
            bookId,
            aggregate.getTitle(),
            aggregate.getSubtitle(),
            aggregate.getDescription(),
            isbn10,
            isbn13,
            sqlDate,
            aggregate.getLanguage(),
            aggregate.getPublisher(),
            aggregate.getPageCount(),
            slug
        );
    }
    
    /**
     * UPSERT authors and book_authors_join table.
     * Authors are deduplicated by name (case-sensitive).
     * Position is preserved for author ordering.
     */
    private void upsertAuthors(UUID bookId, List<String> authors) {
        int position = 0;
        for (String authorName : authors) {
            if (authorName == null || authorName.isBlank()) {
                continue;
            }
            
            String normalized = authorName.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .trim();
            
            String authorId = upsertAuthor(authorName, normalized);
            
            // Link book to author
            jdbcTemplate.update(
                """
                INSERT INTO book_authors_join (id, book_id, author_id, position, created_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (book_id, author_id) DO UPDATE SET
                    position = EXCLUDED.position
                """,
                IdGenerator.generateLong(),
                bookId,
                authorId,
                position++
            );
        }
    }
    
    /**
     * UPSERT single author.
     * Returns authorId (existing or new).
     */
    private String upsertAuthor(String name, String normalized) {
        try {
            return jdbcTemplate.queryForObject(
                """
                INSERT INTO authors (id, name, normalized_name, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                ON CONFLICT (name) DO UPDATE SET updated_at = NOW()
                RETURNING id
                """,
                (rs, rowNum) -> rs.getString("id"),
                IdGenerator.generate(),
                name,
                normalized
            );
        } catch (Exception e) {
            // Fallback: fetch existing
            log.debug("Error upserting author, fetching existing: {}", e.getMessage());
            return jdbcTemplate.query(
                "SELECT id FROM authors WHERE name = ? LIMIT 1",
                rs -> rs.next() ? rs.getString("id") : null,
                name
            );
        }
    }
    
    /**
     * UPSERT book_external_ids table.
     * Links book to external provider identifiers and metadata.
     * <p>Task #9: Sanitizes provider ISBNs before persistence to match canonical ISBN format.</p>
     */
    private void upsertExternalIds(UUID bookId, BookAggregate.ExternalIdentifiers identifiers) {
        String source = identifiers.getSource();
        String externalId = identifiers.getExternalId();

        if (source == null || externalId == null) {
            log.warn("Cannot upsert external ID without source and externalId");
            return;
        }

        // Task #9: Sanitize provider ISBNs to remove hyphens/spaces, then convert empty to null
        String providerIsbn10 = nullIfBlank(IsbnUtils.sanitize(identifiers.getProviderIsbn10()));
        String providerIsbn13 = nullIfBlank(IsbnUtils.sanitize(identifiers.getProviderIsbn13()));
        
        jdbcTemplate.update(
            """
            INSERT INTO book_external_ids (
                id, book_id, source, external_id,
                provider_isbn10, provider_isbn13,
                info_link, preview_link, web_reader_link, purchase_link, canonical_volume_link,
                average_rating, ratings_count, review_count,
                is_ebook, pdf_available, epub_available, embeddable, public_domain,
                viewability, text_readable, image_readable,
                print_type, maturity_rating, content_version, text_to_speech_permission,
                saleability, country_code, is_ebook_for_sale,
                list_price, retail_price, currency_code,
                oclc_work_id, openlibrary_work_id, goodreads_work_id, google_canonical_id,
                created_at, last_updated
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (source, external_id) DO UPDATE SET
                book_id = EXCLUDED.book_id,
                info_link = COALESCE(EXCLUDED.info_link, book_external_ids.info_link),
                preview_link = COALESCE(EXCLUDED.preview_link, book_external_ids.preview_link),
                web_reader_link = COALESCE(EXCLUDED.web_reader_link, book_external_ids.web_reader_link),
                purchase_link = COALESCE(EXCLUDED.purchase_link, book_external_ids.purchase_link),
                canonical_volume_link = COALESCE(EXCLUDED.canonical_volume_link, book_external_ids.canonical_volume_link),
                average_rating = COALESCE(EXCLUDED.average_rating, book_external_ids.average_rating),
                ratings_count = COALESCE(EXCLUDED.ratings_count, book_external_ids.ratings_count),
                review_count = COALESCE(EXCLUDED.review_count, book_external_ids.review_count),
                is_ebook = COALESCE(EXCLUDED.is_ebook, book_external_ids.is_ebook),
                pdf_available = COALESCE(EXCLUDED.pdf_available, book_external_ids.pdf_available),
                epub_available = COALESCE(EXCLUDED.epub_available, book_external_ids.epub_available),
                embeddable = COALESCE(EXCLUDED.embeddable, book_external_ids.embeddable),
                public_domain = COALESCE(EXCLUDED.public_domain, book_external_ids.public_domain),
                viewability = COALESCE(EXCLUDED.viewability, book_external_ids.viewability),
                text_readable = COALESCE(EXCLUDED.text_readable, book_external_ids.text_readable),
                image_readable = COALESCE(EXCLUDED.image_readable, book_external_ids.image_readable),
                print_type = COALESCE(EXCLUDED.print_type, book_external_ids.print_type),
                maturity_rating = COALESCE(EXCLUDED.maturity_rating, book_external_ids.maturity_rating),
                content_version = COALESCE(EXCLUDED.content_version, book_external_ids.content_version),
                text_to_speech_permission = COALESCE(EXCLUDED.text_to_speech_permission, book_external_ids.text_to_speech_permission),
                saleability = COALESCE(EXCLUDED.saleability, book_external_ids.saleability),
                country_code = COALESCE(EXCLUDED.country_code, book_external_ids.country_code),
                is_ebook_for_sale = COALESCE(EXCLUDED.is_ebook_for_sale, book_external_ids.is_ebook_for_sale),
                list_price = COALESCE(EXCLUDED.list_price, book_external_ids.list_price),
                retail_price = COALESCE(EXCLUDED.retail_price, book_external_ids.retail_price),
                currency_code = COALESCE(EXCLUDED.currency_code, book_external_ids.currency_code),
                oclc_work_id = COALESCE(EXCLUDED.oclc_work_id, book_external_ids.oclc_work_id),
                openlibrary_work_id = COALESCE(EXCLUDED.openlibrary_work_id, book_external_ids.openlibrary_work_id),
                goodreads_work_id = COALESCE(EXCLUDED.goodreads_work_id, book_external_ids.goodreads_work_id),
                google_canonical_id = COALESCE(EXCLUDED.google_canonical_id, book_external_ids.google_canonical_id),
                last_updated = NOW()
            """,
            IdGenerator.generate(),
            bookId,
            source,
            externalId,
            providerIsbn10,
            providerIsbn13,
            normalizeToHttps(identifiers.getInfoLink()),
            normalizeToHttps(identifiers.getPreviewLink()),
            normalizeToHttps(identifiers.getWebReaderLink()),
            normalizeToHttps(identifiers.getPurchaseLink()),
            normalizeToHttps(identifiers.getCanonicalVolumeLink()),
            identifiers.getAverageRating(),
            identifiers.getRatingsCount(),
            identifiers.getReviewCount(),
            identifiers.getIsEbook(),
            identifiers.getPdfAvailable(),
            identifiers.getEpubAvailable(),
            identifiers.getEmbeddable(),
            identifiers.getPublicDomain(),
            identifiers.getViewability(),
            identifiers.getTextReadable(),
            identifiers.getImageReadable(),
            identifiers.getPrintType(),
            identifiers.getMaturityRating(),
            identifiers.getContentVersion(),
            identifiers.getTextToSpeechPermission(),
            identifiers.getSaleability(),
            identifiers.getCountryCode(),
            identifiers.getIsEbookForSale(),
            identifiers.getListPrice(),
            identifiers.getRetailPrice(),
            identifiers.getCurrencyCode(),
            identifiers.getOclcWorkId(),
            identifiers.getOpenlibraryWorkId(),
            identifiers.getGoodreadsWorkId(),
            identifiers.getGoogleCanonicalId()
        );
    }
    
    /**
     * UPSERT book_image_links table with enhanced metadata.
     * 
     * Uses CoverPersistenceService (SSOT) to:
     * - Normalize URLs to HTTPS
     * - Estimate dimensions based on image type
     * - Detect high-resolution images
     * - Persist canonical cover metadata into book_image_links
     * 
     * Falls back to simple upsert if CoverPersistenceService fails or reports no change.
     */
    private void upsertImageLinksEnhanced(UUID bookId, BookAggregate.ExternalIdentifiers identifiers) {
        Map<String, String> imageLinks = identifiers.getImageLinks();
        String source = identifiers.getSource() != null ? identifiers.getSource() : "GOOGLE_BOOKS";

        if (imageLinks == null || imageLinks.isEmpty()) {
            log.debug("Skipping cover persistence for book {} because no image links were provided", bookId);
            return;
        }

        CoverQualitySnapshot incomingQuality = computeIncomingCoverQuality(imageLinks);
        if (!incomingQuality.hasData()) {
            log.debug("Skipping cover persistence for book {} because incoming image links contain no renderable covers", bookId);
            return;
        }

        CoverQualitySnapshot existingQuality = fetchExistingCoverQuality(bookId);
        if (existingQuality.isStrictlyBetterThan(incomingQuality)) {
            log.debug(
                "Existing cover quality for book {} (score={}, pixels={}) outranks incoming (score={}, pixels={}), skipping update",
                bookId,
                existingQuality.score(),
                existingQuality.pixelArea(),
                incomingQuality.score(),
                incomingQuality.pixelArea()
            );
            return;
        }
        
        try {
            CoverPersistenceService.PersistenceResult result = coverPersistenceService.persistFromGoogleImageLinks(
                bookId,
                imageLinks,
                source
            );

            if (result.success()) {
                log.debug("Enhanced image metadata persisted for book {}: canonicalUrl={}, dimensions={}x{}, highRes={}",
                    bookId, result.canonicalUrl(), result.width(), result.height(), result.highRes());
                return;
            }

            log.warn("CoverPersistenceService returned no canonical cover for book {}. Falling back to simple upsert.", bookId);
        } catch (Exception e) {
            log.warn("CoverPersistenceService failed for book {}, falling back to simple upsert: {}",
                bookId, e.getMessage());
        }

        upsertImageLinksSimple(bookId, imageLinks, source);
    }
    
    /**
     * Fallback: Simple UPSERT without enhanced metadata.
     * Only persists URL and source - no dimensions or high-res detection.
     * 
     * @deprecated Use upsertImageLinksEnhanced instead
     */
    @Deprecated(since = "2025-09-30", forRemoval = false)
    private void upsertImageLinksSimple(UUID bookId, Map<String, String> imageLinks, String source) {
        for (Map.Entry<String, String> entry : imageLinks.entrySet()) {
            String imageType = entry.getKey();
            String url = entry.getValue();
            
            if (url == null || url.isBlank()) {
                continue;
            }
            
            jdbcTemplate.update(
                """
                INSERT INTO book_image_links (id, book_id, image_type, url, source, created_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (book_id, image_type) DO UPDATE SET
                    url = EXCLUDED.url,
                    source = EXCLUDED.source
                """,
                IdGenerator.generate(),
                bookId,
                imageType,
                normalizeToHttps(url),
                source
            );
        }
    }
    
    /**
     * UPSERT book_dimensions table.
     * Stores physical dimensions (height, width, thickness) from Google Books API.
     * Uses DimensionParser to convert strings like "24.00 cm" to numeric values.
     */
    private void upsertDimensions(UUID bookId, BookAggregate.Dimensions dimensions) {
        // Parse dimension strings to numeric values in centimeters
        DimensionParser.ParsedDimensions parsed = DimensionParser.parseAll(
            dimensions.getHeight(),
            dimensions.getWidth(),
            dimensions.getThickness()
        );
        
        // Only insert if at least one dimension is valid; otherwise remove stale records
        if (!parsed.hasAnyDimension()) {
            log.debug("No valid dimensions to upsert for book {} – removing existing record if present", bookId);
            jdbcTemplate.update("DELETE FROM book_dimensions WHERE book_id = ?", bookId);
            return;
        }
        
        jdbcTemplate.update(
            """
            INSERT INTO book_dimensions (book_id, height, width, thickness, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (book_id) DO UPDATE SET
                height = COALESCE(EXCLUDED.height, book_dimensions.height),
                width = COALESCE(EXCLUDED.width, book_dimensions.width),
                thickness = COALESCE(EXCLUDED.thickness, book_dimensions.thickness),
                updated_at = NOW()
            """,
            bookId,
            parsed.height(),
            parsed.width(),
            parsed.thickness()
        );
    }
    
    /**
     * UPSERT categories via BookCollectionPersistenceService.
     * 
     * <p>Normalizes and deduplicates categories before persistence for consistency
     * in case categories come from different sources or were already partially normalized.
     * 
     * @param bookId Book UUID
     * @param categories Raw or pre-normalized category list
     * @see CategoryNormalizer#normalizeAndDeduplicate(List)
     */
    private void upsertCategories(UUID bookId, List<String> categories) {
        // Normalize and deduplicate for consistency (DRY principle)
        // This ensures consistency even if categories come from different sources
        List<String> normalizedCategories = CategoryNormalizer.normalizeAndDeduplicate(categories);
        
        for (String category : normalizedCategories) {
            collectionPersistenceService.upsertCategory(category)
                .ifPresent(collectionId -> 
                    collectionPersistenceService.addBookToCategory(collectionId, bookId.toString())
                );
        }
    }
    
    /**
     * Emit event to the transactional outbox. The payload now includes optional context
     * and image metadata so consumers (WebSocket relay, dashboards) have everything needed
     * without additional lookups.
     */
    private void emitOutboxEvent(UUID bookId,
                                 String slug,
                                 String title,
                                 boolean isNew,
                                 String context,
                                 String canonicalImageUrl,
                                 Map<String, String> imageLinks,
                                 String source) {
        try {
            String topic = "/topic/book." + bookId;
            Map<String, Object> payload = new HashMap<>();
            payload.put("bookId", bookId.toString());
            payload.put("slug", slug != null ? slug : "");
            payload.put("title", title != null ? title : "");
            payload.put("isNew", isNew);
            payload.put("timestamp", System.currentTimeMillis());
            if (context != null && !context.isBlank()) {
                payload.put("context", context);
            }
            if (canonicalImageUrl != null && !canonicalImageUrl.isBlank()) {
                payload.put("canonicalImageUrl", canonicalImageUrl);
            }
            if (imageLinks != null && !imageLinks.isEmpty()) {
                payload.put("imageLinks", imageLinks);
            }
            if (source != null && !source.isBlank()) {
                payload.put("source", source);
            }

            String payloadJson = objectMapper.writeValueAsString(payload);
            
            jdbcTemplate.update(
                "INSERT INTO events_outbox (topic, payload, created_at) VALUES (?, ?::jsonb, NOW())",
                topic,
                payloadJson
            );
            
            log.debug("Emitted outbox event for book {}", bookId);
        } catch (Exception e) {
            log.warn("Failed to emit outbox event for book {}: {}", bookId, e.getMessage());
            // Don't fail the transaction - event is optional
        }
    }

    /**
     * Publish the in-process application event so components that do not read from the
     * outbox (e.g., S3 orchestration) can react immediately after the transaction commits.
     */
    private void publishBookUpsertEvent(UUID bookId,
                                        String slug,
                                        String title,
                                        boolean isNew,
                                        String context,
                                        String canonicalImageUrl,
                                        Map<String, String> imageLinks,
                                        String source) {
        try {
            eventPublisher.publishEvent(new BookUpsertEvent(
                bookId.toString(),
                slug,
                title,
                isNew,
                context,
                imageLinks,
                canonicalImageUrl,
                source
            ));
        } catch (Exception ex) {
            log.warn("Failed to publish BookUpsertEvent for book {}: {}", bookId, ex.getMessage());
        }
    }

    // Helper methods

    /**
     * Reads the best existing cover for a book from Postgres.
     *
     * @param bookId canonical book identifier
     * @return snapshot describing the strongest persisted cover quality, or empty when none exists
     */
    private CoverQualitySnapshot fetchExistingCoverQuality(UUID bookId) {
        try {
            return jdbcTemplate.query(
                """
                SELECT s3_image_path, url, width, height, is_high_resolution
                FROM book_image_links
                WHERE book_id = ?
                ORDER BY COALESCE(is_high_resolution, false) DESC,
                         COALESCE(width * height, 0) DESC,
                         created_at DESC
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return CoverQualitySnapshot.absent();
                    }
                    String s3Path = rs.getString("s3_image_path");
                    String url = rs.getString("url");
                    Integer width = rs.getObject("width", Integer.class);
                    Integer height = rs.getObject("height", Integer.class);
                    Boolean highRes = rs.getObject("is_high_resolution", Boolean.class);
                    int score = CoverQuality.rank(s3Path, url, width, height, highRes);
                    long pixels = ImageDimensionUtils.totalPixels(width, height);
                    boolean resolvedHighRes = Boolean.TRUE.equals(highRes) || ImageDimensionUtils.isHighResolution(width, height);
                    return new CoverQualitySnapshot(score, pixels, resolvedHighRes, true);
                },
                bookId
            );
        } catch (Exception ex) {
            log.debug("Unable to evaluate existing cover quality for {}: {}", bookId, ex.getMessage());
            return CoverQualitySnapshot.absent();
        }
    }

    /**
     * Scores the incoming Google Books image links so we can compare against persisted covers.
     *
     * @param imageLinks raw provider image links keyed by image type
     * @return snapshot describing the best candidate derived from the payload
     */
    private CoverQualitySnapshot computeIncomingCoverQuality(Map<String, String> imageLinks) {
        if (imageLinks == null || imageLinks.isEmpty()) {
            return CoverQualitySnapshot.absent();
        }

        CoverQualitySnapshot best = CoverQualitySnapshot.absent();
        for (Map.Entry<String, String> entry : imageLinks.entrySet()) {
            String url = entry.getValue();
            if (url == null || url.isBlank()) {
                continue;
            }
            ImageDimensionUtils.DimensionEstimate estimate = ImageDimensionUtils.estimateFromGoogleType(entry.getKey());
            String normalizedUrl = normalizeToHttps(url);
            int score = CoverQuality.rank(null, normalizedUrl, estimate.width(), estimate.height(), estimate.highRes());
            long pixels = ImageDimensionUtils.totalPixels(estimate.width(), estimate.height());
            boolean highRes = estimate.highRes() || ImageDimensionUtils.isHighResolution(estimate.width(), estimate.height());
            CoverQualitySnapshot candidate = new CoverQualitySnapshot(score, pixels, highRes, true);
            if (candidate.isStrictlyBetterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Lightweight holder describing cover quality characteristics for comparison.
     */
    private record CoverQualitySnapshot(int score,
                                        long pixelArea,
                                        boolean highRes,
                                        boolean present) {

        static CoverQualitySnapshot absent() {
            return new CoverQualitySnapshot(0, 0, false, false);
        }

        boolean hasData() {
            return present;
        }

        boolean isStrictlyBetterThan(CoverQualitySnapshot other) {
            if (!present) {
                return false;
            }
            if (other == null || !other.present) {
                return true;
            }
            if (score > other.score) {
                return true;
            }
            if (score < other.score) {
                return false;
            }
            if (pixelArea > other.pixelArea) {
                return true;
            }
            if (pixelArea < other.pixelArea) {
                return false;
            }
            return highRes && !other.highRes;
        }
    }

    /**
     * Normalizes the image link map from the aggregate to HTTPS URLs while preserving insertion
     * order so downstream consumers receive deterministic priority.
     */
    private Map<String, String> collectNormalizedImageLinks(BookAggregate aggregate) {
        if (aggregate.getIdentifiers() == null || aggregate.getIdentifiers().getImageLinks() == null) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        aggregate.getIdentifiers().getImageLinks().forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) {
                sanitized.put(key, normalizeToHttps(value));
            }
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    /**
     * Selects the best candidate URL for upstream S3 upload based on known provider keys.
     */
    private String selectPreferredImageUrl(Map<String, String> imageLinks) {
        if (imageLinks == null || imageLinks.isEmpty()) {
            return null;
        }
        List<String> priorityOrder = List.of(
            "canonical",
            "extraLarge",
            "large",
            "medium",
            "small",
            "thumbnail",
            "smallThumbnail"
        );
        for (String key : priorityOrder) {
            String candidate = imageLinks.get(key);
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return imageLinks.values().stream()
            .filter(url -> url != null && !url.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String extractIsbnPrefix(String isbn13) {
        if (isbn13 == null || isbn13.isBlank()) {
            return null;
        }

        String digits = isbn13.chars()
            .filter(Character::isDigit)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();

        if (digits.length() != 13) {
            return null;
        }

        return digits.substring(0, 11);
    }

    private String nullIfBlank(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }
    
    private String normalizeToHttps(String url) {
        // Use shared utility instead of duplicate implementation
        return UrlUtils.normalizeToHttps(url);
    }
    
    /**
     * Result of upsert operation.
     */
    @Value
    @Builder
    public static class UpsertResult {
        UUID bookId;
        String slug;
        boolean isNew;
    }
}
