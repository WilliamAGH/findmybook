package net.findmybook.repository;

import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.BookListItem;
import net.findmybook.util.UuidUtils;
import org.springframework.util.StringUtils;
import net.findmybook.util.cover.CoverUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Replaces placeholder or broken cover URLs with the best available fallback
 * from {@code book_image_links}, using the centralized {@code cover_image_priority()}
 * SQL function for consistent ordering across all surfaces.
 */
final class BookQueryCoverNormalizer {
    private static final Logger log = LoggerFactory.getLogger(BookQueryCoverNormalizer.class);

    private static final int MIN_COVER_WIDTH = 180;
    private static final int MIN_COVER_HEIGHT = 280;
    private static final double MIN_ASPECT_RATIO = 1.2;
    private static final double MAX_ASPECT_RATIO = 2.0;

    private static final String PLACEHOLDER_COVER_PATTERN = "placeholder-book-cover.svg";
    private static final String LOCALHOST_PREFIX = "://localhost";
    private static final String LOOPBACK_IP_PREFIX = "://127.0.0.1";
    private static final String ANY_LOCAL_IP_PREFIX = "://0.0.0.0";
    private static final String COVER_PATH_SEGMENT = "/images/book-covers/";

    private final JdbcTemplate jdbcTemplate;
    private final BookQueryResultSetSupport resultSetSupport;

    BookQueryCoverNormalizer(JdbcTemplate jdbcTemplate, BookQueryResultSetSupport resultSetSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.resultSetSupport = resultSetSupport;
    }

    /**
     * Carries a resolved fallback cover together with its grayscale status
     * so normalization lambdas can propagate both to the reconstructed DTO.
     */
    record CoverFallback(CoverUrlResolver.ResolvedCover resolved, Boolean grayscale) {}

    /**
     * Pre-resolved cover fields from a {@link CoverFallback}, preferring fallback
     * values over originals. Eliminates repeated null-checking across normalize methods.
     */
    record ResolvedFields(
        String coverUrl, String s3Key,
        Integer width, Integer height, Boolean highResolution,
        Boolean grayscale
    ) {
        /** Resolves cover fields by preferring fallback values over {@code originals}. */
        static ResolvedFields from(CoverFallback fallback, ResolvedFields originals) {
            CoverUrlResolver.ResolvedCover r = fallback != null ? fallback.resolved() : null;
            return new ResolvedFields(
                r != null ? r.url() : originals.coverUrl(),
                r != null && StringUtils.hasText(r.s3Key()) ? r.s3Key() : originals.s3Key(),
                r != null ? Integer.valueOf(r.width()) : originals.width(),
                r != null ? Integer.valueOf(r.height()) : originals.height(),
                r != null ? Boolean.valueOf(r.highResolution()) : originals.highResolution(),
                fallback != null && fallback.grayscale() != null
                    ? fallback.grayscale() : originals.grayscale()
            );
        }
    }

    List<BookCard> normalizeBookCardCovers(List<BookCard> cards) {
        return normalizeCovers(cards, BookCard::id, BookCard::coverUrl, (card, fallback) -> {
            var f = ResolvedFields.from(fallback, new ResolvedFields(
                card.coverUrl(), card.coverS3Key(), null, null, null, card.coverGrayscale()));
            String fallbackUrl = StringUtils.hasText(card.fallbackCoverUrl())
                ? card.fallbackCoverUrl() : f.coverUrl();
            return new BookCard(
                card.id(), card.slug(), card.title(), card.authors(),
                f.coverUrl(), f.s3Key(), fallbackUrl,
                card.averageRating(), card.ratingsCount(), card.tags(),
                f.grayscale(), card.publishedDate()
            );
        });
    }

    List<BookListItem> normalizeBookListItemCovers(List<BookListItem> items) {
        return normalizeCovers(items, BookListItem::id, BookListItem::coverUrl, (item, fallback) -> {
            var f = ResolvedFields.from(fallback, new ResolvedFields(
                item.coverUrl(), item.coverS3Key(),
                item.coverWidth(), item.coverHeight(), item.coverHighResolution(), item.coverGrayscale()));
            String fallbackUrl = StringUtils.hasText(item.coverFallbackUrl())
                ? item.coverFallbackUrl() : f.coverUrl();
            return new BookListItem(
                item.id(), item.slug(), item.title(), item.description(),
                item.authors(), item.categories(),
                f.coverUrl(), f.s3Key(), fallbackUrl, f.width(), f.height(), f.highResolution(),
                item.averageRating(), item.ratingsCount(), item.tags(),
                item.publishedDate(), f.grayscale()
            );
        });
    }

    List<BookDetail> normalizeBookDetailCovers(List<BookDetail> details) {
        return normalizeCovers(details, BookDetail::id, BookDetail::coverUrl, (detail, fallback) -> {
            var f = ResolvedFields.from(fallback, new ResolvedFields(
                detail.coverUrl(), detail.coverS3Key(),
                detail.coverWidth(), detail.coverHeight(), detail.coverHighResolution(), detail.coverGrayscale()));
            String fallbackUrl = StringUtils.hasText(detail.coverFallbackUrl())
                ? detail.coverFallbackUrl() : detail.thumbnailUrl();
            return new BookDetail(
                detail.id(), detail.slug(), detail.title(), detail.description(),
                detail.publisher(), detail.publishedDate(), detail.language(), detail.pageCount(),
                detail.authors(), detail.categories(),
                f.coverUrl(), f.s3Key(), fallbackUrl, detail.thumbnailUrl(),
                f.width(), f.height(), f.highResolution(),
                detail.dataSource(), detail.averageRating(), detail.ratingsCount(),
                detail.isbn10(), detail.isbn13(), detail.previewLink(), detail.infoLink(),
                detail.tags(), f.grayscale(), detail.editions()
            );
        });
    }

    /**
     * Shared pipeline: collect IDs needing cover fallback, batch-fetch from book_image_links,
     * then apply per-item reconstruction via the provided function.
     */
    private <T> List<T> normalizeCovers(List<T> items,
                                         Function<T, String> idExtractor,
                                         Function<T, String> coverUrlExtractor,
                                         BiFunction<T, CoverFallback, T> applyCover) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<UUID> fallbackIds = items.stream()
            .filter(item -> needsFallback(coverUrlExtractor.apply(item)))
            .map(idExtractor)
            .map(UuidUtils::parseUuidOrNull)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<UUID, CoverFallback> fallbacks = fetchFallbackCovers(fallbackIds);

        return items.stream()
            .map(item -> {
                UUID id = UuidUtils.parseUuidOrNull(idExtractor.apply(item));
                CoverFallback fallback = id != null ? fallbacks.get(id) : null;
                return applyCover.apply(item, fallback);
            })
            .toList();
    }

    private Map<UUID, CoverFallback> fetchFallbackCovers(List<UUID> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Map.of();
        }

        String sql = """
            SELECT DISTINCT ON (bil.book_id)
                   bil.book_id,
                   bil.url,
                   bil.s3_image_path,
                   bil.width,
                   bil.height,
                   bil.is_high_resolution,
                   bil.is_grayscale
            FROM book_image_links bil
            WHERE bil.book_id = ANY(?::UUID[])
              AND bil.download_error IS NULL
              AND (
                  (bil.url IS NOT NULL AND bil.url <> '')
                  OR (bil.s3_image_path IS NOT NULL AND bil.s3_image_path <> '')
              )
              AND (bil.url IS NULL OR bil.url NOT LIKE '%%placeholder-book-cover.svg%%')
              AND (bil.s3_image_path IS NULL OR bil.s3_image_path NOT LIKE '%%placeholder-book-cover.svg%%')
              AND (bil.url IS NULL OR (
                  bil.url NOT LIKE '%%printsec=titlepage%%'
                  AND bil.url NOT LIKE '%%printsec=copyright%%'
                  AND bil.url NOT LIKE '%%printsec=toc%%'
              ))
              AND (
                  bil.width IS NULL OR bil.height IS NULL
                  OR (
                      bil.width >= %d
                      AND bil.height >= %d
                      AND (bil.height::float / NULLIF(bil.width, 0)) BETWEEN %.1f AND %.1f
                  )
              )
            """.formatted(MIN_COVER_WIDTH, MIN_COVER_HEIGHT, MIN_ASPECT_RATIO, MAX_ASPECT_RATIO);

        UUID[] idsArray = bookIds.toArray(new UUID[0]);

        try {
            return jdbcTemplate.query(sql, ps -> {
                Array uuidArray = createUuidArray(ps.getConnection(), idsArray);
                ps.setArray(1, uuidArray);
            }, this::extractFallbackCovers);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch fallback cover URLs for {} books: {}", bookIds.size(), ex.getMessage(), ex);
            throw new IllegalStateException("Cover URL fallback query failed for " + bookIds.size() + " books", ex);
        }
    }

    /** Maps each result row to a resolved {@link CoverFallback} keyed by book ID. */
    private Map<UUID, CoverFallback> extractFallbackCovers(java.sql.ResultSet rs) throws SQLException {
        Map<UUID, CoverFallback> resolved = new java.util.HashMap<>();
        while (rs.next()) {
            UUID id = (UUID) rs.getObject("book_id");
            String url = rs.getString("url");
            String s3Key = rs.getString("s3_image_path");
            Integer width = resultSetSupport.getIntOrNull(rs, "width");
            Integer height = resultSetSupport.getIntOrNull(rs, "height");
            Boolean highRes = resultSetSupport.getBooleanOrNull(rs, "is_high_resolution");
            Boolean grayscale = resultSetSupport.getBooleanOrNull(rs, "is_grayscale");

            if (StringUtils.hasText(url) || StringUtils.hasText(s3Key)) {
                resolved.put(
                    id,
                    new CoverFallback(
                        CoverUrlResolver.resolve(
                            StringUtils.hasText(s3Key) ? s3Key : url,
                            StringUtils.hasText(url) ? url : null,
                            width, height, highRes
                        ),
                        grayscale
                    )
                );
            }
        }
        return resolved;
    }

    private static Array createUuidArray(Connection connection, UUID[] idsArray) {
        try {
            return connection.createArrayOf("uuid", idsArray);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create SQL uuid[] array parameter", ex);
        }
    }

    private boolean needsFallback(String url) {
        if (!StringUtils.hasText(url)) {
            return true;
        }
        String lower = url.toLowerCase();
        if (lower.contains(PLACEHOLDER_COVER_PATTERN)) {
            return true;
        }
        if (lower.contains(LOCALHOST_PREFIX) || lower.contains(LOOPBACK_IP_PREFIX) || lower.contains(ANY_LOCAL_IP_PREFIX)) {
            return true;
        }
        if (lower.contains(COVER_PATH_SEGMENT) && !CoverUrlResolver.isCdnUrl(url)) {
            return true;
        }
        return !(url.startsWith("http://") || url.startsWith("https://"));
    }
}
