package net.findmybook.repository;

import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.BookListItem;
import net.findmybook.dto.EditionSummary;
import net.findmybook.util.ValidationUtils;
import org.springframework.util.StringUtils;
import net.findmybook.util.cover.CoverUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

final class BookQueryRowMapperFactory {
    private static final Logger log = LoggerFactory.getLogger(BookQueryRowMapperFactory.class);

    private final BookQueryResultSetSupport resultSetSupport;

    BookQueryRowMapperFactory(BookQueryResultSetSupport resultSetSupport) {
        this.resultSetSupport = resultSetSupport;
    }

    RowMapper<BookCard> bookCardRowMapper() {
        return this::mapBookCard;
    }

    RowMapper<BookListItem> bookListItemRowMapper() {
        return this::mapBookListItem;
    }

    RowMapper<BookDetail> bookDetailRowMapper() {
        return this::mapBookDetail;
    }

    RowMapper<EditionSummary> editionSummaryRowMapper() {
        return this::mapEditionSummary;
    }

    private BookCard mapBookCard(ResultSet rs, int rowNum) throws SQLException {
        List<String> authors = resultSetSupport.parseTextArray(rs.getArray("authors"));
        String s3Key = rs.getString("cover_s3_key");
        String fallbackUrl = rs.getString("cover_fallback_url");
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            s3Key,
            fallbackUrl,
            null,
            null,
            null
        );
        String preferredUrl = resolved.url();
        String effectiveFallback = StringUtils.hasText(fallbackUrl) ? fallbackUrl : preferredUrl;
        log.trace("BookCard row: id={}, title={}, authors={}, s3Key={}, fallback={}, resolved={}",
            rs.getString("id"), rs.getString("title"), authors, s3Key, fallbackUrl, preferredUrl);
        return new BookCard(
            rs.getString("id"),
            rs.getString("slug"),
            rs.getString("title"),
            authors,
            preferredUrl,
            resolved.s3Key(),
            effectiveFallback,
            resultSetSupport.getDoubleOrNull(rs, "average_rating"),
            resultSetSupport.getIntOrNull(rs, "ratings_count"),
            resultSetSupport.parseJsonb(rs.getString("tags"))
        );
    }

    private BookListItem mapBookListItem(ResultSet rs, int rowNum) throws SQLException {
        Integer width = resultSetSupport.getIntOrNull(rs, "cover_width");
        Integer height = resultSetSupport.getIntOrNull(rs, "cover_height");
        Boolean highRes = resultSetSupport.getBooleanOrNull(rs, "cover_is_high_resolution");
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            resultSetSupport.getStringOrNull(rs, "cover_s3_key"),
            resultSetSupport.getStringOrNull(rs, "cover_fallback_url"),
            width,
            height,
            highRes
        );
        String fallbackUrl = resultSetSupport.getStringOrNull(rs, "cover_fallback_url");
        String effectiveFallback = StringUtils.hasText(fallbackUrl) ? fallbackUrl : resolved.url();
        return new BookListItem(
            rs.getString("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("description"),
            resultSetSupport.parseTextArray(rs.getArray("authors")),
            resultSetSupport.parseTextArray(rs.getArray("categories")),
            resolved.url(),
            resolved.s3Key(),
            effectiveFallback,
            resolved.width(),
            resolved.height(),
            resolved.highResolution(),
            resultSetSupport.getDoubleOrNull(rs, "average_rating"),
            resultSetSupport.getIntOrNull(rs, "ratings_count"),
            resultSetSupport.parseJsonb(rs.getString("tags")),
            resultSetSupport.getLocalDateOrNull(rs, "published_date")
        );
    }

    private BookDetail mapBookDetail(ResultSet rs, int rowNum) throws SQLException {
        Integer width = resultSetSupport.getIntOrNull(rs, "cover_width");
        Integer height = resultSetSupport.getIntOrNull(rs, "cover_height");
        Boolean highRes = resultSetSupport.getBooleanOrNull(rs, "cover_is_high_resolution");
        CoverUrlResolver.ResolvedCover cover = CoverUrlResolver.resolve(
            resultSetSupport.getStringOrNull(rs, "cover_s3_key"),
            resultSetSupport.getStringOrNull(rs, "cover_fallback_url"),
            width,
            height,
            highRes
        );
        String thumbnailUrl = resultSetSupport.getStringOrNull(rs, "thumbnail_url");
        String fallbackUrl = resultSetSupport.getStringOrNull(rs, "cover_fallback_url");
        String effectiveFallback = StringUtils.hasText(fallbackUrl) ? fallbackUrl : thumbnailUrl;
        CoverUrlResolver.ResolvedCover thumb = CoverUrlResolver.resolve(
            thumbnailUrl,
            thumbnailUrl,
            null,
            null,
            null
        );
        return new BookDetail(
            rs.getString("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("description"),
            ValidationUtils.stripWrappingQuotes(rs.getString("publisher")),
            resultSetSupport.getLocalDateOrNull(rs, "published_date"),
            rs.getString("language"),
            resultSetSupport.getIntOrNull(rs, "page_count"),
            resultSetSupport.parseTextArray(rs.getArray("authors")),
            resultSetSupport.parseTextArray(rs.getArray("categories")),
            cover.url(),
            cover.s3Key(),
            effectiveFallback,
            thumb.url(),
            cover.width(),
            cover.height(),
            cover.highResolution(),
            rs.getString("data_source"),
            resultSetSupport.getDoubleOrNull(rs, "average_rating"),
            resultSetSupport.getIntOrNull(rs, "ratings_count"),
            resultSetSupport.getStringOrNull(rs, "isbn_10"),
            resultSetSupport.getStringOrNull(rs, "isbn_13"),
            resultSetSupport.getStringOrNull(rs, "preview_link"),
            resultSetSupport.getStringOrNull(rs, "info_link"),
            resultSetSupport.parseJsonb(rs.getString("tags")),
            List.of()
        );
    }

    private EditionSummary mapEditionSummary(ResultSet rs, int rowNum) throws SQLException {
        return new EditionSummary(
            rs.getString("id"),
            rs.getString("slug"),
            rs.getString("title"),
            resultSetSupport.getLocalDateOrNull(rs, "published_date"),
            ValidationUtils.stripWrappingQuotes(rs.getString("publisher")),
            rs.getString("isbn_13"),
            rs.getString("cover_url"),
            rs.getString("language"),
            resultSetSupport.getIntOrNull(rs, "page_count")
        );
    }
}
