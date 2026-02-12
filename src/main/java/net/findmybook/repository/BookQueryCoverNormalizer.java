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

    List<BookCard> normalizeBookCardCovers(List<BookCard> cards) {
        return normalizeCovers(cards, BookCard::id, BookCard::coverUrl, (card, fallback) -> {
            CoverUrlResolver.ResolvedCover resolved = fallback != null ? fallback.resolved() : null;
            String coverUrl = resolved != null ? resolved.url() : card.coverUrl();
            String fallbackUrl = StringUtils.hasText(card.fallbackCoverUrl())
                ? card.fallbackCoverUrl()
                : (resolved != null ? resolved.url() : coverUrl);
            String s3Key = resolveS3Key(resolved, card.coverS3Key());
            Boolean grayscale = fallback != null && fallback.grayscale() != null ? fallback.grayscale() : card.coverGrayscale();
            return new BookCard(
                card.id(), card.slug(), card.title(), card.authors(),
                coverUrl, s3Key, fallbackUrl,
                card.averageRating(), card.ratingsCount(), card.tags(),
                grayscale, card.publishedDate()
            );
        });
    }

    List<BookListItem> normalizeBookListItemCovers(List<BookListItem> items) {
        return normalizeCovers(items, BookListItem::id, BookListItem::coverUrl, (item, fallback) -> {
            CoverUrlResolver.ResolvedCover resolved = fallback != null ? fallback.resolved() : null;
            String coverUrl = resolved != null ? resolved.url() : item.coverUrl();
            Integer width = resolved != null ? resolved.width() : item.coverWidth();
            Integer height = resolved != null ? resolved.height() : item.coverHeight();
            Boolean highRes = resolved != null ? resolved.highResolution() : item.coverHighResolution();
            String fallbackUrl = StringUtils.hasText(item.coverFallbackUrl())
                ? item.coverFallbackUrl()
                : (resolved != null ? resolved.url() : coverUrl);
            String s3Key = resolveS3Key(resolved, item.coverS3Key());
            Boolean grayscale = fallback != null && fallback.grayscale() != null ? fallback.grayscale() : item.coverGrayscale();
            return new BookListItem(
                item.id(), item.slug(), item.title(), item.description(),
                item.authors(), item.categories(),
                coverUrl, s3Key, fallbackUrl, width, height, highRes,
                item.averageRating(), item.ratingsCount(), item.tags(),
                item.publishedDate(), grayscale
            );
        });
    }

    List<BookDetail> normalizeBookDetailCovers(List<BookDetail> details) {
        return normalizeCovers(details, BookDetail::id, BookDetail::coverUrl, (detail, fallback) -> {
            CoverUrlResolver.ResolvedCover resolved = fallback != null ? fallback.resolved() : null;
            String coverUrl = resolved != null ? resolved.url() : detail.coverUrl();
            Integer width = resolved != null ? resolved.width() : detail.coverWidth();
            Integer height = resolved != null ? resolved.height() : detail.coverHeight();
            Boolean highRes = resolved != null ? resolved.highResolution() : detail.coverHighResolution();
            String s3Key = resolveS3Key(resolved, detail.coverS3Key());
            String fallbackUrl = StringUtils.hasText(detail.coverFallbackUrl())
                ? detail.coverFallbackUrl()
                : detail.thumbnailUrl();
            Boolean grayscale = fallback != null && fallback.grayscale() != null ? fallback.grayscale() : detail.coverGrayscale();
            return new BookDetail(
                detail.id(), detail.slug(), detail.title(), detail.description(),
                detail.publisher(), detail.publishedDate(), detail.language(), detail.pageCount(),
                detail.authors(), detail.categories(),
                coverUrl, s3Key, fallbackUrl, detail.thumbnailUrl(), width, height, highRes,
                detail.dataSource(), detail.averageRating(), detail.ratingsCount(),
                detail.isbn10(), detail.isbn13(), detail.previewLink(), detail.infoLink(),
                detail.tags(), grayscale, detail.editions()
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
            return items;
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

    private static String resolveS3Key(CoverUrlResolver.ResolvedCover fallback, String existingS3Key) {
        return fallback != null && StringUtils.hasText(fallback.s3Key())
            ? fallback.s3Key()
            : existingS3Key;
    }

    private Map<UUID, CoverFallback> fetchFallbackCovers(List<UUID> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Map.of();
        }

        try {
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

            return jdbcTemplate.query(sql, ps -> {
                Array uuidArray = createUuidArray(ps.getConnection(), idsArray);
                ps.setArray(1, uuidArray);
            }, rs -> {
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
                                    width,
                                    height,
                                    highRes
                                ),
                                grayscale
                            )
                        );
                    }
                }
                return resolved;
            });
        } catch (DataAccessException ex) {
            log.error("Failed to fetch fallback cover URLs for {} books: {}", bookIds.size(), ex.getMessage(), ex);
            throw new IllegalStateException("Cover URL fallback query failed for " + bookIds.size() + " books", ex);
        }
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
