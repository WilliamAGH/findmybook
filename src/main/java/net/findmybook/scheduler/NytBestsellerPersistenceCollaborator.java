package net.findmybook.scheduler;

import tools.jackson.databind.JsonNode;
import net.findmybook.dto.BookAggregate;
import net.findmybook.service.BookLookupService;
import net.findmybook.service.BookSupplementalPersistenceService;
import net.findmybook.service.BookUpsertService;
import net.findmybook.util.IdGenerator;
import net.findmybook.util.IsbnUtils;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Encapsulates NYT-specific write operations beyond list and membership upserts.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Backfill/enrich canonical book metadata from NYT payload values.</li>
 *   <li>Maintain NYT external identifier rows.</li>
 *   <li>Assign NYT canonical and list-specific tags with normalized metadata.</li>
 * </ul>
 */
@Component
public class NytBestsellerPersistenceCollaborator {

    private static final Logger log = LoggerFactory.getLogger(NytBestsellerPersistenceCollaborator.class);
    private static final String NYT_SOURCE = "NEW_YORK_TIMES";
    private static final String NYT_IDENTITY_LOCK_PREFIX = "findmybook:nyt-book-identity:";

    private final JdbcTemplate jdbcTemplate;
    private final BookSupplementalPersistenceService supplementalPersistenceService;
    private final NytBestsellerPayloadMapper payloadMapper;
    private final BookLookupService bookLookupService;
    private final BookUpsertService bookUpsertService;

    public NytBestsellerPersistenceCollaborator(JdbcTemplate jdbcTemplate,
                                                BookSupplementalPersistenceService supplementalPersistenceService,
                                                NytBestsellerPayloadMapper payloadMapper,
                                                BookLookupService bookLookupService,
                                                BookUpsertService bookUpsertService) {
        this.jdbcTemplate = jdbcTemplate;
        this.supplementalPersistenceService = supplementalPersistenceService;
        this.payloadMapper = payloadMapper;
        this.bookLookupService = bookLookupService;
        this.bookUpsertService = bookUpsertService;
    }

    /**
     * Resolves and persists one NYT book while a transaction-scoped lock protects every
     * provider identity present in the payload. The remote NYT request has already
     * completed before this boundary is entered.
     *
     * @return the canonical book ID, or {@code null} when the payload has no safe identity
     */
    @Nullable
    @Transactional
    public String resolveOrCreateCanonicalBook(JsonNode bookNode,
                                               NytListContext listContext,
                                               @Nullable String isbn13,
                                               @Nullable String isbn10) {
        String payloadExternalId = payloadMapper.resolveNytExternalId(bookNode, isbn13, isbn10);
        if (!StringUtils.hasText(payloadExternalId)) {
            return null;
        }

        String bookUri = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "book_uri"));
        acquireNytIdentityLocks(bookUri, isbn13, isbn10);
        NytStoredIdentity storedIdentity = reconcileStoredIdentity(
            bookUri,
            payloadExternalId,
            loadMatchingStoredIdentities(bookUri, isbn13, isbn10)
        );

        String canonicalId = storedIdentity.bookId();
        if (canonicalId == null) {
            canonicalId = bookLookupService.resolveCanonicalBookId(isbn13, isbn10);
        }
        boolean isNewBook = canonicalId == null;

        if (canonicalId == null) {
            canonicalId = createCanonicalFromNyt(
                bookNode,
                listContext,
                storedIdentity.externalId(),
                isbn13,
                isbn10
            );
        } else {
            enrichExistingCanonicalBookMetadata(canonicalId, bookNode);
        }

        String title = payloadMapper.firstNonEmptyText(bookNode, "title", "book_title");
        if (canonicalId == null) {
            log.warn("Unable to locate or create canonical book for NYT list entry (ISBN13: {}, ISBN10: {}, title: {}).",
                isbn13,
                isbn10,
                title != null ? title : "unknown");
            return null;
        }

        upsertNytExternalIdentifiers(
            canonicalId,
            bookNode,
            storedIdentity.externalId(),
            isbn13,
            isbn10
        );
        log.info("Processing NYT book: canonicalId='{}', isNew={}, listCode='{}', isbn13='{}', title='{}'",
            canonicalId,
            isNewBook,
            listContext.listCode(),
            isbn13,
            title != null ? title : "unknown");
        return canonicalId;
    }

    private void acquireNytIdentityLocks(@Nullable String bookUri,
                                         @Nullable String isbn13,
                                         @Nullable String isbn10) {
        Stream.of(
                identityLockName("uri", bookUri),
                identityLockName("isbn", resolveIsbnIdentity(isbn13, isbn10))
            )
            .filter(StringUtils::hasText)
            .distinct()
            .sorted()
            .forEach(identity -> jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null,
                identity
            ));
    }

    @Nullable
    private static String resolveIsbnIdentity(@Nullable String isbn13, @Nullable String isbn10) {
        String isbnIdentity = IsbnUtils.isbn13Identity(isbn13);
        return isbnIdentity != null ? isbnIdentity : IsbnUtils.isbn13Identity(isbn10);
    }

    @Nullable
    private static String identityLockName(String identityType, @Nullable String identity) {
        return StringUtils.hasText(identity)
            ? NYT_IDENTITY_LOCK_PREFIX + identityType + ":" + identity.trim()
            : null;
    }

    private List<NytStoredIdentityRow> loadMatchingStoredIdentities(@Nullable String bookUri,
                                                                    @Nullable String isbn13,
                                                                    @Nullable String isbn10) {
        List<NytStoredIdentityRow> matchingRows = jdbcTemplate.query(
            """
            SELECT id, book_id, external_id, canonical_volume_link
            FROM book_external_ids
            WHERE source = 'NEW_YORK_TIMES'
              AND (
                (CAST(? AS text) IS NOT NULL AND (external_id = ? OR canonical_volume_link = ?))
                OR (CAST(? AS text) IS NOT NULL AND (external_id = ? OR provider_isbn13 = ?))
                OR (CAST(? AS text) IS NOT NULL AND (external_id = ? OR provider_isbn10 = ?))
              )
            ORDER BY CASE WHEN external_id = ? THEN 0 WHEN canonical_volume_link = ? THEN 1 ELSE 2 END, id
            FOR UPDATE
            """,
            (resultSet, rowNumber) -> new NytStoredIdentityRow(
                resultSet.getString("id"),
                resultSet.getObject("book_id", UUID.class).toString(),
                resultSet.getString("external_id"),
                resultSet.getString("canonical_volume_link")
            ),
            bookUri,
            bookUri,
            bookUri,
            isbn13,
            isbn13,
            isbn13,
            isbn10,
            isbn10,
            isbn10,
            bookUri, bookUri
        );
        return matchingRows == null ? List.of() : matchingRows;
    }

    private NytStoredIdentity reconcileStoredIdentity(@Nullable String bookUri,
                                                      String payloadExternalId,
                                                      List<NytStoredIdentityRow> matchingRows) {
        if (matchingRows.isEmpty()) {
            return new NytStoredIdentity(payloadExternalId, null);
        }

        String storedBookId = matchingRows.getFirst().bookId();
        if (matchingRows.stream().anyMatch(row -> !storedBookId.equals(row.bookId()))) {
            throw new IllegalStateException("NYT provider identity resolves to multiple canonical books; refusing an ambiguous reassignment");
        }

        NytStoredIdentityRow storedRow = matchingRows.getFirst();
        if (!StringUtils.hasText(bookUri) || bookUri.equals(storedRow.externalId())) {
            return new NytStoredIdentity(storedRow.externalId(), storedRow.bookId());
        }
        if (StringUtils.hasText(storedRow.canonicalVolumeLink())
            && !bookUri.equals(storedRow.canonicalVolumeLink())) {
            throw conflictingStableIdentity();
        }
        if (IsbnUtils.isbn13Identity(storedRow.externalId()) == null) {
            throw conflictingStableIdentity();
        }

        int updatedRows = jdbcTemplate.update(
            """
            UPDATE book_external_ids
            SET external_id = ?,
                canonical_volume_link = COALESCE(NULLIF(canonical_volume_link, ''), ?),
                last_updated = NOW()
            WHERE id = ?
              AND source = 'NEW_YORK_TIMES'
            """,
            bookUri,
            bookUri,
            storedRow.id()
        );
        if (updatedRows != 1) {
            throw new IllegalStateException("NYT provider identity upgrade did not update exactly one stored row");
        }
        return new NytStoredIdentity(bookUri, storedRow.bookId());
    }

    private static IllegalStateException conflictingStableIdentity() {
        return new IllegalStateException(
            "NYT payload conflicts with a stored stable provider URI; refusing canonical reassignment"
        );
    }

    @Nullable
    private String createCanonicalFromNyt(JsonNode bookNode,
                                          NytListContext listContext,
                                          String externalId,
                                          @Nullable String isbn13,
                                          @Nullable String isbn10) {
        BookAggregate aggregate = payloadMapper.buildBookAggregateFromNyt(
            bookNode,
            listContext,
            externalId,
            isbn13,
            isbn10
        );
        if (aggregate == null) {
            return null;
        }
        try {
            return bookUpsertService.upsert(aggregate).getBookId().toString();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "Failed to create canonical book from NYT data (isbn13=" + isbn13 + ", isbn10=" + isbn10 + ")",
                exception
            );
        }
    }

    public void enrichExistingCanonicalBookMetadata(String canonicalId, JsonNode bookNode) {
        String nytDescription = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "description", "summary"));
        String nytPublisher = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "publisher"));
        LocalDate nytPublishedDate = payloadMapper.parsePublishedLocalDate(bookNode);
        Date sqlPublishedDate = nytPublishedDate != null ? Date.valueOf(nytPublishedDate) : null;
        boolean hasDescription = nytDescription != null;
        boolean hasPublisher = nytPublisher != null;
        boolean hasPublishedDate = sqlPublishedDate != null;

        jdbcTemplate.update(
            """
            UPDATE books
            SET description = CASE
                    WHEN (books.description IS NULL OR btrim(books.description) = '') AND ?
                        THEN ?
                    ELSE books.description
                END,
                publisher = CASE
                    WHEN (books.publisher IS NULL OR btrim(books.publisher) = '') AND ?
                        THEN ?
                    ELSE books.publisher
                END,
                published_date = COALESCE(books.published_date, ?),
                updated_at = CASE
                    WHEN ((books.description IS NULL OR btrim(books.description) = '') AND ?)
                        OR ((books.publisher IS NULL OR btrim(books.publisher) = '') AND ?)
                        OR (books.published_date IS NULL AND ?)
                        THEN NOW()
                    ELSE books.updated_at
                END
            WHERE id = ?
            """,
            hasDescription,
            nytDescription,
            hasPublisher,
            nytPublisher,
            sqlPublishedDate,
            hasDescription,
            hasPublisher,
            hasPublishedDate,
            UUID.fromString(canonicalId)
        );
    }

    /**
     * Persists a NYT external identifier only when the source row carries a
     * reproducible NYT provider identity.
     */
    void upsertNytExternalIdentifiers(String canonicalId,
                                      JsonNode bookNode,
                                      @Nullable String externalId,
                                      @Nullable String isbn13,
                                      @Nullable String isbn10) {
        if (!StringUtils.hasText(canonicalId) || jdbcTemplate == null) {
            return;
        }

        if (!StringUtils.hasText(externalId)) {
            log.warn("Skipping NYT external identifier persistence without reproducible provider identity for canonical book '{}'.",
                canonicalId);
            return;
        }
        UUID canonicalUuid = UUID.fromString(canonicalId);
        String infoLink = payloadMapper.nullIfBlank(
            payloadMapper.firstNonEmptyText(bookNode, "book_review_link", "sunday_review_link", "article_chapter_link")
        );
        String previewLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "first_chapter_link"));
        String webReaderLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "article_chapter_link"));
        String purchaseLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "amazon_product_url"));
        String canonicalVolumeLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "book_uri"));

        int updatedRows = jdbcTemplate.update(
            """
            INSERT INTO book_external_ids (
                id,
                book_id,
                source,
                external_id,
                provider_isbn13,
                provider_isbn10,
                info_link,
                preview_link,
                web_reader_link,
                purchase_link,
                canonical_volume_link,
                last_updated,
                created_at
            )
            VALUES (?, ?, 'NEW_YORK_TIMES', ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (source, external_id) DO UPDATE
            SET provider_isbn13 = COALESCE(NULLIF(book_external_ids.provider_isbn13, ''), EXCLUDED.provider_isbn13),
                provider_isbn10 = COALESCE(NULLIF(book_external_ids.provider_isbn10, ''), EXCLUDED.provider_isbn10),
                info_link = COALESCE(NULLIF(book_external_ids.info_link, ''), EXCLUDED.info_link),
                preview_link = COALESCE(NULLIF(book_external_ids.preview_link, ''), EXCLUDED.preview_link),
                web_reader_link = COALESCE(NULLIF(book_external_ids.web_reader_link, ''), EXCLUDED.web_reader_link),
                purchase_link = COALESCE(NULLIF(book_external_ids.purchase_link, ''), EXCLUDED.purchase_link),
                canonical_volume_link = COALESCE(NULLIF(book_external_ids.canonical_volume_link, ''), EXCLUDED.canonical_volume_link),
                last_updated = NOW()
            WHERE book_external_ids.book_id = EXCLUDED.book_id
            """,
            IdGenerator.generateLong(),
            canonicalUuid,
            externalId,
            isbn13,
            isbn10,
            infoLink,
            previewLink,
            webReaderLink,
            purchaseLink,
            canonicalVolumeLink
        );
        if (updatedRows != 1) {
            throw new IllegalStateException(
                "NYT provider identity already belongs to a different canonical book; refusing reassignment"
            );
        }
    }

    private record NytStoredIdentity(String externalId, @Nullable String bookId) {}

    private record NytStoredIdentityRow(
        String id,
        String bookId,
        String externalId,
        @Nullable String canonicalVolumeLink
    ) {}

    public void assignCoreTags(String bookId,
                               NytListContext listContext,
                               JsonNode bookNode,
                               RankingStats stats) {
        String naturalListLabel = payloadMapper.resolveNaturalListLabel(
            listContext.listDisplayName(),
            listContext.listName(),
            listContext.listCode()
        );
        Map<String, Serializable> metadata = buildNytTagMetadata(
            listContext,
            bookNode,
            stats,
            naturalListLabel
        );

        String listTagKey = "nyt_list_" + listContext.listCode().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        String listTagDisplayName = "NYT List: " + naturalListLabel;

        supplementalPersistenceService.assignTag(bookId, "nyt_bestseller", "NYT Bestseller", "NYT", 1.0, metadata);
        supplementalPersistenceService.assignTag(bookId, listTagKey, listTagDisplayName, "NYT", 1.0, metadata);
    }

    private Map<String, Serializable> buildNytTagMetadata(NytListContext listContext,
                                                           JsonNode bookNode,
                                                           RankingStats stats,
                                                           String naturalListLabel) {
        Map<String, Serializable> metadata = new LinkedHashMap<>();
        metadata.put("list_code", listContext.listCode());
        putIfHasText(metadata, "list_display_name", naturalListLabel);
        putIfHasText(metadata, "list_name", listContext.listName());
        putIfHasText(metadata, "provider_list_id", listContext.providerListId());
        putIfHasText(metadata, "updated_frequency", listContext.updatedFrequency());
        putIfHasText(metadata, "published_date", formatDate(listContext.publishedDate()));
        putIfHasText(metadata, "bestsellers_date", formatDate(listContext.bestsellersDate()));

        if (stats.rank() != null) metadata.put("rank", stats.rank());
        if (stats.weeksOnList() != null) metadata.put("weeks_on_list", stats.weeksOnList());
        if (stats.rankLastWeek() != null) metadata.put("rank_last_week", stats.rankLastWeek());
        if (stats.peakPosition() != null) metadata.put("peak_position", stats.peakPosition());

        putIfHasText(metadata, "title", payloadMapper.firstNonEmptyText(bookNode, "title", "book_title"));
        putIfHasText(metadata, "description", payloadMapper.firstNonEmptyText(bookNode, "description", "summary"));
        putIfHasText(metadata, "author", payloadMapper.firstNonEmptyText(bookNode, "author"));
        putIfHasText(metadata, "contributor", payloadMapper.firstNonEmptyText(bookNode, "contributor"));
        putIfHasText(metadata, "contributor_note", payloadMapper.firstNonEmptyText(bookNode, "contributor_note"));
        putIfHasText(metadata, "publisher", payloadMapper.firstNonEmptyText(bookNode, "publisher"));
        putIfHasText(metadata, "primary_isbn13", payloadMapper.resolveNytIsbn13(bookNode));
        putIfHasText(metadata, "primary_isbn10", payloadMapper.resolveNytIsbn10(bookNode));
        putIfHasText(metadata, "created_date", payloadMapper.firstNonEmptyText(bookNode, "created_date"));
        putIfHasText(metadata, "updated_date", payloadMapper.firstNonEmptyText(bookNode, "updated_date"));
        putIfHasText(metadata, "asterisk", payloadMapper.firstNonEmptyText(bookNode, "asterisk"));
        putIfHasText(metadata, "dagger", payloadMapper.firstNonEmptyText(bookNode, "dagger"));
        putIfHasText(metadata, "age_group", payloadMapper.firstNonEmptyText(bookNode, "age_group"));
        putIfHasText(metadata, "price", payloadMapper.firstNonEmptyText(bookNode, "price"));
        putIfHasText(metadata, "book_uri", payloadMapper.firstNonEmptyText(bookNode, "book_uri"));
        putIfHasText(metadata, "book_review_link", payloadMapper.firstNonEmptyText(bookNode, "book_review_link"));
        putIfHasText(metadata, "sunday_review_link", payloadMapper.firstNonEmptyText(bookNode, "sunday_review_link"));
        putIfHasText(metadata, "article_chapter_link", payloadMapper.firstNonEmptyText(bookNode, "article_chapter_link"));
        putIfHasText(metadata, "first_chapter_link", payloadMapper.firstNonEmptyText(bookNode, "first_chapter_link"));
        putIfHasText(metadata, "amazon_product_url", payloadMapper.firstNonEmptyText(bookNode, "amazon_product_url"));
        putIfHasText(metadata, "book_image", payloadMapper.firstNonEmptyText(bookNode, "book_image"));
        Integer imageWidth = payloadMapper.parseIntegerField(bookNode, "book_image_width");
        if (imageWidth != null) {
            metadata.put("book_image_width", imageWidth);
        }
        Integer imageHeight = payloadMapper.parseIntegerField(bookNode, "book_image_height");
        if (imageHeight != null) {
            metadata.put("book_image_height", imageHeight);
        }
        Map<String, String> buyLinks = payloadMapper.extractBuyLinks(bookNode);
        if (!buyLinks.isEmpty()) {
            metadata.put("buy_links", new LinkedHashMap<>(buyLinks));
        }
        List<Map<String, String>> isbnEntries = payloadMapper.extractIsbnEntries(bookNode);
        if (!isbnEntries.isEmpty()) {
            metadata.put("isbns", new ArrayList<>(isbnEntries));
        }
        return metadata;
    }

    public record RankingStats(@Nullable Integer rank, @Nullable Integer weeksOnList, @Nullable Integer rankLastWeek, @Nullable Integer peakPosition) {}

    @Nullable
    private static String formatDate(@Nullable LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private static void putIfHasText(Map<String, Serializable> metadata, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value.trim());
        }
    }
}
