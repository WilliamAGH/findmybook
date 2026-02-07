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
 * Central write path for persisting a normalized book aggregate.
 */
@Service
@Slf4j
public class BookUpsertService {

    private static final long FNV_64_OFFSET_BASIS = 0xcbf29ce484222325L;
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
     * Writes the aggregate into canonical tables, emitting an outbox event in the same transaction.
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
        Optional<UUID> existingBookId = findExistingBookId(aggregate);
        boolean isNew = existingBookId.isEmpty();
        UUID bookId = existingBookId.orElseGet(() -> UUID.fromString(IdGenerator.uuidV7()));

        if (isNew) {
            log.info("Creating new book: id={}, title='{}'", bookId, aggregate.getTitle());
        } else {
            log.info("Updating existing book: id={}, title='{}'", bookId, aggregate.getTitle());
        }
        String slug = bookUpsertTransactionService.ensureUniqueSlug(aggregate.getSlugBase(), bookId, isNew);
        bookUpsertTransactionService.upsertBookRecord(bookId, aggregate, slug);
        if (aggregate.getAuthors() != null && !aggregate.getAuthors().isEmpty()) {
            bookUpsertTransactionService.upsertAuthors(bookId, aggregate.getAuthors());
        }
        if (aggregate.getIdentifiers() != null) {
            bookUpsertTransactionService.upsertExternalIds(bookId, aggregate.getIdentifiers());
        }
        BookImageLinkPersistenceService.ImageLinkPersistenceResult imageLinkPersistenceResult =
            bookImageLinkPersistenceService.persistImageLinks(bookId, aggregate.getIdentifiers());
        if (aggregate.getCategories() != null && !aggregate.getCategories().isEmpty()) {
            bookUpsertTransactionService.upsertCategories(bookId, aggregate.getCategories());
        }
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
    
    private Optional<UUID> findExistingBookId(BookAggregate aggregate) {
        Long lockKey = computeBookLockKey(aggregate);
        if (lockKey == null) {
            log.warn("Book has no identifiers (ISBN/externalId) - cannot use advisory lock. Race condition possible.");
            return lookupBookByIdentifiers(aggregate);
        }
        try {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
            log.debug("Acquired advisory lock {} for book lookup", lockKey);
        } catch (DataAccessException e) {
            log.error("Failed to acquire advisory lock {} during book lookup: {}", lockKey, e.getMessage(), e);
            throw new AdvisoryLockAcquisitionException(lockKey, e);
        }
        return lookupBookByIdentifiers(aggregate);
    }

    private Optional<UUID> lookupBookByIdentifiers(BookAggregate aggregate) {
        return findBookByIdentifiersUnsafe(aggregate);
    }

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

    private Optional<UUID> findBookByExternalId(String source, String externalId) {
        UUID id = jdbcTemplate.query(
            "SELECT book_id FROM book_external_ids WHERE source = ? AND external_id = ? LIMIT 1",
            rs -> rs.next() ? (UUID) rs.getObject("book_id") : null,
            source, externalId
        );
        return Optional.ofNullable(id);
    }

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

    private Long computeBookLockKey(BookAggregate aggregate) {
        String lockString = null;
        String sanitizedIsbn13 = IsbnUtils.sanitize(aggregate.getIsbn13());
        if (sanitizedIsbn13 != null) {
            lockString = "ISBN13:" + sanitizedIsbn13;
        }
        else {
            String sanitizedIsbn10 = IsbnUtils.sanitize(aggregate.getIsbn10());
            if (sanitizedIsbn10 != null) {
                lockString = "ISBN10:" + sanitizedIsbn10;
            }
        }
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
        return fnv1a64(lockString);
    }

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

    @Value
    @Builder
    public static class UpsertResult {
        UUID bookId;
        String slug;
        boolean isNew;
    }
}
