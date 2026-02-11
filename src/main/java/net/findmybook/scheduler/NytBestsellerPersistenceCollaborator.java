package net.findmybook.scheduler;

import tools.jackson.databind.JsonNode;
import net.findmybook.service.BookSupplementalPersistenceService;
import net.findmybook.util.IdGenerator;
import jakarta.annotation.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    private final JdbcTemplate jdbcTemplate;
    private final BookSupplementalPersistenceService supplementalPersistenceService;
    private final NytBestsellerPayloadMapper payloadMapper;

    public NytBestsellerPersistenceCollaborator(JdbcTemplate jdbcTemplate,
                                                BookSupplementalPersistenceService supplementalPersistenceService,
                                                NytBestsellerPayloadMapper payloadMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.supplementalPersistenceService = supplementalPersistenceService;
        this.payloadMapper = payloadMapper;
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

    public void upsertNytExternalIdentifiers(String canonicalId, JsonNode bookNode, String isbn13, String isbn10) {
        if (!StringUtils.hasText(canonicalId) || jdbcTemplate == null) {
            return;
        }

        UUID canonicalUuid = UUID.fromString(canonicalId);
        String externalId = payloadMapper.nullIfBlank(isbn13 != null ? isbn13 : isbn10);
        if (!StringUtils.hasText(externalId)) {
            externalId = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "book_uri"));
        }
        if (!StringUtils.hasText(externalId)) {
            externalId = canonicalId;
        }
        String infoLink = payloadMapper.nullIfBlank(
            payloadMapper.firstNonEmptyText(bookNode, "book_review_link", "sunday_review_link", "article_chapter_link")
        );
        String previewLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "first_chapter_link"));
        String webReaderLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "article_chapter_link"));
        String purchaseLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "amazon_product_url"));
        String canonicalVolumeLink = payloadMapper.nullIfBlank(payloadMapper.firstNonEmptyText(bookNode, "book_uri"));

        jdbcTemplate.update(
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
            SET book_id = EXCLUDED.book_id,
                provider_isbn13 = COALESCE(NULLIF(book_external_ids.provider_isbn13, ''), EXCLUDED.provider_isbn13),
                provider_isbn10 = COALESCE(NULLIF(book_external_ids.provider_isbn10, ''), EXCLUDED.provider_isbn10),
                info_link = COALESCE(NULLIF(book_external_ids.info_link, ''), EXCLUDED.info_link),
                preview_link = COALESCE(NULLIF(book_external_ids.preview_link, ''), EXCLUDED.preview_link),
                web_reader_link = COALESCE(NULLIF(book_external_ids.web_reader_link, ''), EXCLUDED.web_reader_link),
                purchase_link = COALESCE(NULLIF(book_external_ids.purchase_link, ''), EXCLUDED.purchase_link),
                canonical_volume_link = COALESCE(NULLIF(book_external_ids.canonical_volume_link, ''), EXCLUDED.canonical_volume_link),
                last_updated = NOW()
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
    }

    public void assignCoreTags(String bookId,
                               NytListContext listContext,
                               JsonNode bookNode,
                               @Nullable Integer rank,
                               @Nullable Integer weeksOnList,
                               @Nullable Integer rankLastWeek,
                               @Nullable Integer peakPosition) {
        String naturalListLabel = payloadMapper.resolveNaturalListLabel(
            listContext.listDisplayName(),
            listContext.listName(),
            listContext.listCode()
        );
        Map<String, Object> metadata = buildNytTagMetadata(
            listContext,
            bookNode,
            rank,
            weeksOnList,
            rankLastWeek,
            peakPosition,
            naturalListLabel
        );

        String listTagKey = "nyt_list_" + listContext.listCode().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        String listTagDisplayName = "NYT List: " + naturalListLabel;

        supplementalPersistenceService.assignTag(bookId, "nyt_bestseller", "NYT Bestseller", "NYT", 1.0, metadata);
        supplementalPersistenceService.assignTag(bookId, listTagKey, listTagDisplayName, "NYT", 1.0, metadata);
    }

    private Map<String, Object> buildNytTagMetadata(NytListContext listContext,
                                                     JsonNode bookNode,
                                                     @Nullable Integer rank,
                                                     @Nullable Integer weeksOnList,
                                                     @Nullable Integer rankLastWeek,
                                                     @Nullable Integer peakPosition,
                                                     String naturalListLabel) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("list_code", listContext.listCode());
        putIfHasText(metadata, "list_display_name", naturalListLabel);
        putIfHasText(metadata, "list_name", listContext.listName());
        putIfHasText(metadata, "provider_list_id", listContext.providerListId());
        putIfHasText(metadata, "updated_frequency", listContext.updatedFrequency());
        putIfHasText(metadata, "published_date", formatDate(listContext.publishedDate()));
        putIfHasText(metadata, "bestsellers_date", formatDate(listContext.bestsellersDate()));
        if (rank != null) {
            metadata.put("rank", rank);
        }
        if (weeksOnList != null) {
            metadata.put("weeks_on_list", weeksOnList);
        }
        if (rankLastWeek != null) {
            metadata.put("rank_last_week", rankLastWeek);
        }
        if (peakPosition != null) {
            metadata.put("peak_position", peakPosition);
        }
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
            metadata.put("buy_links", buyLinks);
        }
        List<Map<String, String>> isbnEntries = payloadMapper.extractIsbnEntries(bookNode);
        if (!isbnEntries.isEmpty()) {
            metadata.put("isbns", isbnEntries);
        }
        return metadata;
    }

    @Nullable
    private static String formatDate(@Nullable LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private static void putIfHasText(Map<String, Object> metadata, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value.trim());
        }
    }
}
