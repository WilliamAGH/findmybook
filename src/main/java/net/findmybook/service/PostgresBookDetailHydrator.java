package net.findmybook.service;

import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImages;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.cover.CoverSourceMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Enriches a {@link Book} with detail-oriented relational data: editions, cover images,
 * recommendations, provider metadata, and data source classification.
 *
 * <p>Companion to {@link PostgresBookSectionHydrator} (which handles text-oriented metadata
 * like authors, categories, collections). Each hydration method wraps its query failure in
 * {@link IllegalStateException} because these sections are critical to the book detail view.</p>
 */
final class PostgresBookDetailHydrator {

    private static final String PROVIDER_GOOGLE_BOOKS = ApplicationConstants.Provider.GOOGLE_BOOKS;

    private final JdbcTemplate jdbcTemplate;

    PostgresBookDetailHydrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void hydrateEditions(Book book, UUID canonicalId) {
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
                       COALESCE(bil.is_high_resolution, false) AS has_high_res,
                       COALESCE((bil.width::bigint * bil.height::bigint), 0) AS cover_area
                FROM work_cluster_members wcm1
                JOIN work_cluster_members wcm ON wcm.cluster_id = wcm1.cluster_id
                JOIN books b ON b.id = wcm.book_id
                JOIN work_clusters wc ON wc.id = wcm.cluster_id
                LEFT JOIN book_external_ids bei
                       ON bei.book_id = b.id AND bei.source = ?
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
                         has_high_res DESC,
                         cover_area DESC,
                         wcm.confidence DESC NULLS LAST,
                         b.published_date DESC NULLS LAST,
                         lower(b.title)
                """;
        try {
            List<Book.EditionInfo> editions = jdbcTemplate.query(sql,
                    ps -> {
                        ps.setString(1, PROVIDER_GOOGLE_BOOKS);
                        ps.setObject(2, canonicalId);
                        ps.setObject(3, canonicalId);
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

    void hydrateCover(Book book, UUID canonicalId) {
        String sql = """
                SELECT image_type, url, source, s3_image_path, width, height, is_high_resolution
                FROM book_image_links
                WHERE book_id = ?
                ORDER BY
                    COALESCE(is_high_resolution, false) DESC,
                    COALESCE((width::bigint * height::bigint), 0) DESC,
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
                    CoverSourceMapper.toCoverImageSource(primary.source()));
            book.setCoverImages(coverImages);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to hydrate cover for canonical book " + canonicalId, ex);
        }
    }

    void hydrateRecommendations(Book book, UUID canonicalId) {
        String sql = """
                SELECT recommended_book_id::text
                FROM book_recommendations
                WHERE source_book_id = ?
                ORDER BY score DESC NULLS LAST, created_at DESC
                LIMIT 20
                """;
        try {
            List<String> recommendations = jdbcTemplate.query(sql, ps -> ps.setObject(1, canonicalId), (rs, rowNum) -> rs.getString("recommended_book_id"));
            book.setCachedRecommendationIds(recommendations.isEmpty() ? List.of() : recommendations);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to hydrate recommendations for canonical book " + canonicalId, ex);
        }
    }

    void hydrateProviderMetadata(Book book, UUID canonicalId) {
        String sql = """
                SELECT info_link, preview_link, web_reader_link, purchase_link,
                       average_rating, ratings_count, list_price, currency_code, provider_asin
                FROM book_external_ids
                WHERE book_id = ?
                ORDER BY CASE WHEN source = ? THEN 0 ELSE 1 END, created_at DESC
                LIMIT 1
                """;
        try {
            jdbcTemplate.query(sql, ps -> {
                ps.setObject(1, canonicalId);
                ps.setString(2, PROVIDER_GOOGLE_BOOKS);
            }, rs -> {
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

    void hydrateDataSource(Book book, UUID canonicalId) {
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

    private record CoverCandidate(String url,
                                  String s3Path,
                                  String source,
                                  Integer width,
                                  Integer height,
                                  Boolean highRes) {
    }
}
