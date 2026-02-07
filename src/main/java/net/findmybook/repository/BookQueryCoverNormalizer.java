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

final class BookQueryCoverNormalizer {
    private static final Logger log = LoggerFactory.getLogger(BookQueryCoverNormalizer.class);

    private final JdbcTemplate jdbcTemplate;
    private final BookQueryResultSetSupport resultSetSupport;

    BookQueryCoverNormalizer(JdbcTemplate jdbcTemplate, BookQueryResultSetSupport resultSetSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.resultSetSupport = resultSetSupport;
    }

    List<BookCard> normalizeBookCardCovers(List<BookCard> cards) {
        return normalizeCovers(cards, BookCard::id, BookCard::coverUrl, (card, fallback) -> {
            String coverUrl = fallback != null ? fallback.url() : card.coverUrl();
            String fallbackUrl = StringUtils.hasText(card.fallbackCoverUrl())
                ? card.fallbackCoverUrl()
                : (fallback != null ? fallback.url() : coverUrl);
            String s3Key = resolveS3Key(fallback, card.coverS3Key());
            return new BookCard(
                card.id(), card.slug(), card.title(), card.authors(),
                coverUrl, s3Key, fallbackUrl,
                card.averageRating(), card.ratingsCount(), card.tags()
            );
        });
    }

    List<BookListItem> normalizeBookListItemCovers(List<BookListItem> items) {
        return normalizeCovers(items, BookListItem::id, BookListItem::coverUrl, (item, fallback) -> {
            String coverUrl = fallback != null ? fallback.url() : item.coverUrl();
            Integer width = fallback != null ? fallback.width() : item.coverWidth();
            Integer height = fallback != null ? fallback.height() : item.coverHeight();
            Boolean highRes = fallback != null ? fallback.highResolution() : item.coverHighResolution();
            String fallbackUrl = StringUtils.hasText(item.coverFallbackUrl())
                ? item.coverFallbackUrl()
                : (fallback != null ? fallback.url() : coverUrl);
            String s3Key = resolveS3Key(fallback, item.coverS3Key());
            return new BookListItem(
                item.id(), item.slug(), item.title(), item.description(),
                item.authors(), item.categories(),
                coverUrl, s3Key, fallbackUrl, width, height, highRes,
                item.averageRating(), item.ratingsCount(), item.tags()
            );
        });
    }

    List<BookDetail> normalizeBookDetailCovers(List<BookDetail> details) {
        return normalizeCovers(details, BookDetail::id, BookDetail::coverUrl, (detail, fallback) -> {
            String coverUrl = fallback != null ? fallback.url() : detail.coverUrl();
            Integer width = fallback != null ? fallback.width() : detail.coverWidth();
            Integer height = fallback != null ? fallback.height() : detail.coverHeight();
            Boolean highRes = fallback != null ? fallback.highResolution() : detail.coverHighResolution();
            String s3Key = resolveS3Key(fallback, detail.coverS3Key());
            String fallbackUrl = StringUtils.hasText(detail.coverFallbackUrl())
                ? detail.coverFallbackUrl()
                : detail.thumbnailUrl();
            return new BookDetail(
                detail.id(), detail.slug(), detail.title(), detail.description(),
                detail.publisher(), detail.publishedDate(), detail.language(), detail.pageCount(),
                detail.authors(), detail.categories(),
                coverUrl, s3Key, fallbackUrl, detail.thumbnailUrl(), width, height, highRes,
                detail.dataSource(), detail.averageRating(), detail.ratingsCount(),
                detail.isbn10(), detail.isbn13(), detail.previewLink(), detail.infoLink(),
                detail.tags(), detail.editions()
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
                                         BiFunction<T, CoverUrlResolver.ResolvedCover, T> applyCover) {
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

        Map<UUID, CoverUrlResolver.ResolvedCover> fallbacks = fetchFallbackCovers(fallbackIds);

        return items.stream()
            .map(item -> {
                UUID id = UuidUtils.parseUuidOrNull(idExtractor.apply(item));
                CoverUrlResolver.ResolvedCover fallback = id != null ? fallbacks.get(id) : null;
                return applyCover.apply(item, fallback);
            })
            .toList();
    }

    private static String resolveS3Key(CoverUrlResolver.ResolvedCover fallback, String existingS3Key) {
        return fallback != null && StringUtils.hasText(fallback.s3Key())
            ? fallback.s3Key()
            : existingS3Key;
    }

    private Map<UUID, CoverUrlResolver.ResolvedCover> fetchFallbackCovers(List<UUID> bookIds) {
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
                       bil.is_high_resolution
                FROM book_image_links bil
                WHERE bil.book_id = ANY(?::UUID[])
                  AND (
                      (bil.url IS NOT NULL AND bil.url <> '')
                      OR (bil.s3_image_path IS NOT NULL AND bil.s3_image_path <> '')
                  )
                ORDER BY bil.book_id,
                         CASE
                             WHEN bil.image_type = 'canonical' THEN 0
                             WHEN bil.image_type = 'extraLarge' THEN 1
                             WHEN bil.image_type = 'large' THEN 2
                             WHEN bil.image_type = 'medium' THEN 3
                             WHEN bil.image_type = 'small' THEN 4
                             WHEN bil.image_type = 'thumbnail' THEN 5
                             WHEN bil.image_type = 'smallThumbnail' THEN 6
                             ELSE 7
                         END,
                         bil.created_at DESC
                """;

            UUID[] idsArray = bookIds.toArray(new UUID[0]);

            return jdbcTemplate.query(sql, rs -> {
                Map<UUID, CoverUrlResolver.ResolvedCover> resolved = new java.util.HashMap<>();
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("book_id");
                    String url = rs.getString("url");
                    String s3Key = rs.getString("s3_image_path");
                    Integer width = resultSetSupport.getIntOrNull(rs, "width");
                    Integer height = resultSetSupport.getIntOrNull(rs, "height");
                    Boolean highRes = resultSetSupport.getBooleanOrNull(rs, "is_high_resolution");

                    if (StringUtils.hasText(url) || StringUtils.hasText(s3Key)) {
                        resolved.put(
                            id,
                            CoverUrlResolver.resolve(
                                StringUtils.hasText(s3Key) ? s3Key : url,
                                StringUtils.hasText(url) ? url : null,
                                width,
                                height,
                                highRes
                            )
                        );
                    }
                }
                return resolved;
            }, (Object) idsArray);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch fallback cover URLs for {} books: {}", bookIds.size(), ex.getMessage(), ex);
            throw new IllegalStateException("Cover URL fallback query failed for " + bookIds.size() + " books", ex);
        }
    }

    private boolean needsFallback(String url) {
        if (!StringUtils.hasText(url)) {
            return true;
        }
        String lower = url.toLowerCase();
        if (lower.contains("placeholder-book-cover.svg")) {
            return true;
        }
        if (lower.contains("://localhost") || lower.contains("://127.0.0.1") || lower.contains("://0.0.0.0")) {
            return true;
        }
        if (lower.contains("/images/book-covers/") && !CoverUrlResolver.isCdnUrl(url)) {
            return true;
        }
        return !(url.startsWith("http://") || url.startsWith("https://"));
    }
}
