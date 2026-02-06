package net.findmybook.service;

import java.sql.Date;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.findmybook.dto.BookAggregate;
import net.findmybook.util.CategoryNormalizer;
import net.findmybook.util.DimensionParser;
import net.findmybook.util.IdGenerator;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.UrlUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Performs transactional table upserts for book-related records.
 */
@Service
@Slf4j
public class BookUpsertTransactionService {

    private final JdbcTemplate jdbcTemplate;
    private final BookCollectionPersistenceService collectionPersistenceService;

    public BookUpsertTransactionService(JdbcTemplate jdbcTemplate,
                                        BookCollectionPersistenceService collectionPersistenceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.collectionPersistenceService = collectionPersistenceService;
    }

    /** Performs immediate clustering for a new book using stable identifiers. */
    public void clusterNewBook(UUID bookId, BookAggregate aggregate) {
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
            } catch (DataAccessException exception) {
                log.warn("Failed to run cluster_single_book_by_isbn for book {}: {}", bookId, exception.getMessage());
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
            } catch (DataAccessException exception) {
                log.warn("Failed to run cluster_single_book_by_google_id for book {}: {}", bookId, exception.getMessage());
            }
        }
    }

    /** Generates or preserves a unique slug for the book row. */
    public String ensureUniqueSlug(String slugBase, UUID bookId, boolean isNew) {
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
            } catch (DataAccessException exception) {
                log.error("Failed to fetch existing slug for book {}: {}", bookId, exception.getMessage(), exception);
                throw exception;
            }
        }

        if (slugBase == null || slugBase.isBlank()) {
            return null;
        }

        try {
            return jdbcTemplate.queryForObject(
                "SELECT ensure_unique_slug(?)",
                String.class,
                slugBase
            );
        } catch (DataAccessException exception) {
            log.error("Error generating unique slug for base '{}': {}", slugBase, exception.getMessage(), exception);
            throw exception;
        }
    }

    /** Upserts the canonical books record. */
    public void upsertBookRecord(UUID bookId, BookAggregate aggregate, String slug) {
        Date sqlDate = aggregate.getPublishedDate() != null
            ? Date.valueOf(aggregate.getPublishedDate())
            : null;

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

    /** Upserts normalized author rows and join relations in positional order. */
    public void upsertAuthors(UUID bookId, List<String> authors) {
        int position = 0;
        for (String authorName : authors) {
            if (authorName == null || authorName.isBlank()) {
                continue;
            }

            String normalized = authorName.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .trim();
            String authorId = upsertAuthor(authorName, normalized);

            jdbcTemplate.update(
                """
                INSERT INTO book_authors_join (id, book_id, author_id, position, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (book_id, author_id) DO UPDATE SET
                    position = EXCLUDED.position,
                    updated_at = NOW()
                """,
                IdGenerator.generateLong(),
                bookId,
                authorId,
                position++
            );
        }
    }

    /** Upserts provider identifiers and related metadata into book_external_ids. */
    public void upsertExternalIds(UUID bookId, BookAggregate.ExternalIdentifiers identifiers) {
        String source = identifiers.getSource();
        String externalId = identifiers.getExternalId();

        if (source == null || externalId == null) {
            log.warn("Cannot upsert external ID without source and externalId");
            return;
        }

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
                provider_isbn10 = COALESCE(EXCLUDED.provider_isbn10, book_external_ids.provider_isbn10),
                provider_isbn13 = COALESCE(EXCLUDED.provider_isbn13, book_external_ids.provider_isbn13),
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

    /** Upserts physical dimensions, or removes stale rows when dimensions are absent. */
    public void upsertDimensions(UUID bookId, BookAggregate.Dimensions dimensions) {
        DimensionParser.ParsedDimensions parsed = DimensionParser.parseAll(
            dimensions.getHeight(),
            dimensions.getWidth(),
            dimensions.getThickness()
        );

        if (!parsed.hasAnyDimension()) {
            log.debug("No valid dimensions to upsert for book {} - removing existing record if present", bookId);
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

    /** Normalizes and upserts category associations for a book. */
    public void upsertCategories(UUID bookId, List<String> categories) {
        List<String> normalizedCategories = CategoryNormalizer.normalizeAndDeduplicate(categories);
        for (String category : normalizedCategories) {
            collectionPersistenceService.upsertCategory(category)
                .ifPresent(collectionId -> collectionPersistenceService.addBookToCategory(collectionId, bookId.toString()));
        }
    }

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
        } catch (DataAccessException exception) {
            log.error("Error upserting author '{}': {}", name, exception.getMessage(), exception);
            throw exception;
        }
    }

    private String nullIfBlank(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }

    private String normalizeToHttps(String url) {
        return UrlUtils.normalizeToHttps(url);
    }
}
