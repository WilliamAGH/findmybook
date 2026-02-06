package net.findmybook.service;

import net.findmybook.dto.BookAggregate;
import net.findmybook.support.retry.AdvisoryLockAcquisitionException;
import net.findmybook.util.IdGenerator;
import net.findmybook.util.IsbnUtils;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
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

    /** FNV-1a 64-bit offset basis constant for hash computation. */
    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;

    /** FNV-1a 64-bit prime constant for hash computation. */
    private static final long FNV_64_PRIME = 0x100000001b3L;

    private final JdbcTemplate jdbcTemplate;
    private final BookUpsertTransactionService bookUpsertTransactionService;
    private final BookImageLinkPersistenceService bookImageLinkPersistenceService;
    private final BookOutboxEventService bookOutboxEventService;

    public BookUpsertService(
        JdbcTemplate jdbcTemplate,
        BookUpsertTransactionService bookUpsertTransactionService,
        BookImageLinkPersistenceService bookImageLinkPersistenceService,
        BookOutboxEventService bookOutboxEventService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookUpsertTransactionService = bookUpsertTransactionService;
        this.bookImageLinkPersistenceService = bookImageLinkPersistenceService;
        this.bookOutboxEventService = bookOutboxEventService;
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
        Optional<UUID> existingBookId = findExistingBookId(aggregate);
        boolean isNew = existingBookId.isEmpty();
        UUID bookId = existingBookId.orElseGet(() -> UUID.fromString(IdGenerator.uuidV7()));

        if (isNew) {
            log.info("Creating new book: id={}, title='{}'", bookId, aggregate.getTitle());
        } else {
            log.info("Updating existing book: id={}, title='{}'", bookId, aggregate.getTitle());
        }
        
        // 2. Generate unique slug
        String slug = bookUpsertTransactionService.ensureUniqueSlug(aggregate.getSlugBase(), bookId, isNew);
        
        // 3. UPSERT books table
        bookUpsertTransactionService.upsertBookRecord(bookId, aggregate, slug);
        
        // 4. UPSERT authors
        if (aggregate.getAuthors() != null && !aggregate.getAuthors().isEmpty()) {
            bookUpsertTransactionService.upsertAuthors(bookId, aggregate.getAuthors());
        }
        
        // 5. UPSERT external IDs
        if (aggregate.getIdentifiers() != null) {
            bookUpsertTransactionService.upsertExternalIds(bookId, aggregate.getIdentifiers());
        }
        
        // 6. UPSERT image links with enhanced metadata (dimensions, high-res detection)
        BookImageLinkPersistenceService.ImageLinkPersistenceResult imageLinkPersistenceResult =
            bookImageLinkPersistenceService.persistImageLinks(bookId, aggregate.getIdentifiers());
        
        // 7. UPSERT categories
        if (aggregate.getCategories() != null && !aggregate.getCategories().isEmpty()) {
            bookUpsertTransactionService.upsertCategories(bookId, aggregate.getCategories());
        }
        
        // 8. UPSERT dimensions (if provided)
        if (aggregate.getDimensions() != null) {
            bookUpsertTransactionService.upsertDimensions(bookId, aggregate.getDimensions());
        }
        
        Map<String, String> normalizedImageLinks = imageLinkPersistenceResult.normalizedImageLinks();
        String source = aggregate.getIdentifiers() != null ? aggregate.getIdentifiers().getSource() : null;
        String context = source != null ? source : "POSTGRES_UPSERT";
        String canonicalImageUrl = imageLinkPersistenceResult.canonicalImageUrl();

        if (isNew) {
            bookUpsertTransactionService.clusterNewBook(bookId, aggregate);
        }

        // 9. Emit outbox event (transactional) and publish in-process event
        bookOutboxEventService.emitBookUpsert(new BookOutboxEventService.BookUpsertRequest(
            bookId,
            slug,
            aggregate.getTitle(),
            isNew,
            context,
            canonicalImageUrl,
            normalizedImageLinks,
            source
        ));
        
        log.info("Successfully upserted book: id={}, slug='{}', isNew={}", bookId, slug, isNew);
        
        return UpsertResult.builder()
            .bookId(bookId)
            .slug(slug)
            .isNew(isNew)
            .build();
    }
    
    /**
     * Finds an existing book ID by its identifiers.
     * Uses PostgreSQL advisory locks to prevent race conditions during concurrent lookups.
     *
     * @param aggregate the book data containing identifiers to search by
     * @return Optional containing the existing book's UUID, or empty if no match found
     *
     * <p>Lookup strategy (in order, per Task #9):</p>
     * <ol>
     *   <li>ISBN-13 (universal identifier)</li>
     *   <li>ISBN-10 (universal identifier)</li>
     *   <li>External ID (source-specific identifier)</li>
     * </ol>
     *
     * <p>For books WITH identifiers: Acquires advisory lock based on stable hash of identifiers
     * to prevent duplicate inserts from concurrent requests.</p>
     *
     * <p>For books WITHOUT identifiers: Falls back to unsafe path (no lock protection).</p>
     */
    private Optional<UUID> findExistingBookId(BookAggregate aggregate) {
        Long lockKey = computeBookLockKey(aggregate);

        if (lockKey == null) {
            log.warn("Book has no identifiers (ISBN/externalId) - cannot use advisory lock. Race condition possible.");
            return lookupBookByIdentifiers(aggregate);
        }

        // Acquire PostgreSQL advisory lock for this book
        // Lock is automatically released when transaction commits/rolls back
        try {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
            log.debug("Acquired advisory lock {} for book lookup", lockKey);
        } catch (DataAccessException e) {
            log.error("Failed to acquire advisory lock {} during book lookup: {}", lockKey, e.getMessage(), e);
            throw new AdvisoryLockAcquisitionException(lockKey, e);
        }

        // Now safely query within locked section
        return lookupBookByIdentifiers(aggregate);
    }

    /**
     * Looks up existing book by identifiers and returns as Optional.
     */
    private Optional<UUID> lookupBookByIdentifiers(BookAggregate aggregate) {
        return findBookByIdentifiersUnsafe(aggregate);
    }



    /**
     * Looks up existing book ID by identifiers without advisory lock protection.
     * Used as fallback for books without identifiers or when lock acquisition fails.
     *
     * <p>Task #9: Prioritizes universal identifiers (ISBN) over source-specific ones to prevent
     * duplicate book creation when same ISBN comes from different sources with different external IDs.</p>
     *
     * @param aggregate the book data containing identifiers to search by
     * @return Optional containing the existing book's UUID, or empty if no match found
     *
     * <p><strong>Warning:</strong> RACE CONDITION POSSIBLE - two concurrent requests may
     * both get empty and attempt to create duplicate books.</p>
     */
    private Optional<UUID> findBookByIdentifiersUnsafe(BookAggregate aggregate) {
        String sanitizedIsbn13 = IsbnUtils.sanitize(aggregate.getIsbn13());
        if (sanitizedIsbn13 != null) {
            Optional<UUID> existing = findBookByIsbn13(sanitizedIsbn13);
            if (existing.isPresent()) {
                log.debug("Found existing book {} by ISBN-13 {}", existing.get(), sanitizedIsbn13);
                return existing;
            }
        }

        String sanitizedIsbn10 = IsbnUtils.sanitize(aggregate.getIsbn10());
        if (sanitizedIsbn10 != null) {
            Optional<UUID> existing = findBookByIsbn10(sanitizedIsbn10);
            if (existing.isPresent()) {
                log.debug("Found existing book {} by ISBN-10 {}", existing.get(), sanitizedIsbn10);
                return existing;
            }
        }

        if (aggregate.getIdentifiers() != null) {
            String source = aggregate.getIdentifiers().getSource();
            String externalId = aggregate.getIdentifiers().getExternalId();

            if (source != null && externalId != null) {
                Optional<UUID> existing = findBookByExternalId(source, externalId);
                if (existing.isPresent()) {
                    log.debug("Found existing book {} by external identifier {}/{}", existing.get(), source, externalId);
                    return existing;
                }
            }
        }

        Optional<UUID> clusteredMatch = findBookByWorkCluster(sanitizedIsbn13);
        if (clusteredMatch.isPresent()) {
            log.debug("Found existing book {} via work cluster lookup", clusteredMatch.get());
            return clusteredMatch;
        }

        return Optional.empty();
    }
    
    /**
     * Find book by external ID.
     */
    private Optional<UUID> findBookByExternalId(String source, String externalId) {
        UUID id = jdbcTemplate.query(
            "SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1",
            rs -> rs.next() ? (UUID) rs.getObject("book_id") : null,
            source, externalId
        );
        return Optional.ofNullable(id);
    }
    
    /**
     * Find book by ISBN-13.
     * <p>Task #9: Sanitizes ISBN before lookup to match normalized storage format.
     * Ensures "978-0-545-01022-1" finds books stored as "9780545010221".</p>
     */
    private Optional<UUID> findBookByIsbn13(String isbn13) {
        String sanitized = IsbnUtils.sanitize(isbn13);
        if (sanitized == null) {
            return Optional.empty();
        }

        UUID id = jdbcTemplate.query(
            "SELECT id FROM books WHERE isbn13 = ? LIMIT 1",
            rs -> rs.next() ? (UUID) rs.getObject("id") : null,
            sanitized
        );
        return Optional.ofNullable(id);
    }

    /**
     * Find book by ISBN-10.
     * <p>Task #9: Sanitizes ISBN before lookup to match normalized storage format.
     * Ensures "0-545-01022-6" finds books stored as "0545010226".</p>
     */
    private Optional<UUID> findBookByIsbn10(String isbn10) {
        String sanitized = IsbnUtils.sanitize(isbn10);
        if (sanitized == null) {
            return Optional.empty();
        }

        UUID id = jdbcTemplate.query(
            "SELECT id FROM books WHERE isbn10 = ? LIMIT 1",
            rs -> rs.next() ? (UUID) rs.getObject("id") : null,
            sanitized
        );
        return Optional.ofNullable(id);
    }

    private Optional<UUID> findBookByWorkCluster(String sanitizedIsbn13) {
        if (sanitizedIsbn13 == null) {
            return Optional.empty();
        }

        String isbnPrefix = extractIsbnPrefix(sanitizedIsbn13);
        if (isbnPrefix == null) {
            return Optional.empty();
        }

        UUID id = jdbcTemplate.query(
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
        return Optional.ofNullable(id);
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
     * <p>Uses a custom 64-bit hash function to reduce collision probability compared to
     * Java's 32-bit String.hashCode(). With ~4 billion possible 32-bit values, collisions
     * become likely at scale; 64-bit provides adequate space for millions of books.</p>
     *
     * Returns null if book has no identifiers (title-only books).
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

        // Use 64-bit FNV-1a hash for better distribution and fewer collisions than 32-bit hashCode
        return fnv1a64(lockString);
    }

    /**
     * FNV-1a 64-bit hash function for generating advisory lock keys.
     * Provides better collision resistance than Java's 32-bit String.hashCode().
     *
     * @param input the string to hash
     * @return 64-bit hash value (always positive for pg_advisory_xact_lock compatibility)
     */
    private static long fnv1a64(String input) {
        long hash = FNV_64_OFFSET_BASIS;
        for (int i = 0; i < input.length(); i++) {
            hash ^= input.charAt(i);
            hash *= FNV_64_PRIME;
        }
        return hash & Long.MAX_VALUE;
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
