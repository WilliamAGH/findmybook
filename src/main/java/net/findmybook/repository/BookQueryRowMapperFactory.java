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

    private static final String COLUMN_ID = "id";
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_SLUG = "slug";
    private static final String COLUMN_AUTHORS = "authors";
    private static final String COLUMN_COVER_S3_KEY = "cover_s3_key";
    private static final String COLUMN_COVER_FALLBACK_URL = "cover_fallback_url";
    private static final String COLUMN_COVER_IS_GRAYSCALE = "cover_is_grayscale";
    private static final String COLUMN_AVERAGE_RATING = "average_rating";
    private static final String COLUMN_RATINGS_COUNT = "ratings_count";
    private static final String COLUMN_TAGS = "tags";
    private static final String COLUMN_PUBLISHED_DATE = "published_date";
    private static final String COLUMN_COVER_WIDTH = "cover_width";
    private static final String COLUMN_COVER_HEIGHT = "cover_height";
    private static final String COLUMN_COVER_IS_HIGH_RESOLUTION = "cover_is_high_resolution";
    private static final String COLUMN_DESCRIPTION = "description";
    private static final String COLUMN_CATEGORIES = "categories";
    private static final String COLUMN_THUMBNAIL_URL = "thumbnail_url";
    private static final String COLUMN_PUBLISHER = "publisher";
    private static final String COLUMN_LANGUAGE = "language";
    private static final String COLUMN_PAGE_COUNT = "page_count";
    private static final String COLUMN_DATA_SOURCE = "data_source";
    private static final String COLUMN_ISBN_10 = "isbn_10";
    private static final String COLUMN_ISBN_13 = "isbn_13";
    private static final String COLUMN_PREVIEW_LINK = "preview_link";
    private static final String COLUMN_INFO_LINK = "info_link";

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
        List<String> authors = resultSetSupport.parseTextArray(rs.getArray(COLUMN_AUTHORS));
        String s3Key = rs.getString(COLUMN_COVER_S3_KEY);
        String fallbackUrl = rs.getString(COLUMN_COVER_FALLBACK_URL);
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            s3Key,
            fallbackUrl,
            null,
            null,
            null
        );
        String preferredUrl = resolved.url();
        String effectiveFallback = StringUtils.hasText(fallbackUrl) ? fallbackUrl : preferredUrl;
        Boolean grayscale = resultSetSupport.getBooleanOrNull(rs, COLUMN_COVER_IS_GRAYSCALE);
        log.trace("BookCard row: id={}, title={}, authors={}, s3Key={}, fallback={}, resolved={}",
            rs.getString(COLUMN_ID), rs.getString(COLUMN_TITLE), authors, s3Key, fallbackUrl, preferredUrl);
        return new BookCard(
            rs.getString(COLUMN_ID),
            rs.getString(COLUMN_SLUG),
            rs.getString(COLUMN_TITLE),
            authors,
            preferredUrl,
            resolved.s3Key(),
            effectiveFallback,
            resultSetSupport.getDoubleOrNull(rs, COLUMN_AVERAGE_RATING),
            resultSetSupport.getIntOrNull(rs, COLUMN_RATINGS_COUNT),
            resultSetSupport.parseJsonb(rs.getString(COLUMN_TAGS)),
            grayscale,
            resultSetSupport.getLocalDateOrNull(rs, COLUMN_PUBLISHED_DATE)
        );
    }

    private BookListItem mapBookListItem(ResultSet rs, int rowNum) throws SQLException {
        Integer width = resultSetSupport.getIntOrNull(rs, COLUMN_COVER_WIDTH);
        Integer height = resultSetSupport.getIntOrNull(rs, COLUMN_COVER_HEIGHT);
        Boolean highRes = resultSetSupport.getBooleanOrNull(rs, COLUMN_COVER_IS_HIGH_RESOLUTION);
        Boolean grayscale = resultSetSupport.getBooleanOrNull(rs, COLUMN_COVER_IS_GRAYSCALE);
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            resultSetSupport.getStringOrNull(rs, COLUMN_COVER_S3_KEY),
            resultSetSupport.getStringOrNull(rs, COLUMN_COVER_FALLBACK_URL),
            width,
            height,
            highRes
        );
        String fallbackUrl = resultSetSupport.getStringOrNull(rs, COLUMN_COVER_FALLBACK_URL);
        String effectiveFallback = StringUtils.hasText(fallbackUrl) ? fallbackUrl : resolved.url();
        return new BookListItem(
            rs.getString(COLUMN_ID),
            rs.getString(COLUMN_SLUG),
            rs.getString(COLUMN_TITLE),
            rs.getString(COLUMN_DESCRIPTION),
            resultSetSupport.parseTextArray(rs.getArray(COLUMN_AUTHORS)),
            resultSetSupport.parseTextArray(rs.getArray(COLUMN_CATEGORIES)),
            resolved.url(),
            resolved.s3Key(),
            effectiveFallback,
            resolved.width(),
            resolved.height(),
            resolved.highResolution(),
            resultSetSupport.getDoubleOrNull(rs, COLUMN_AVERAGE_RATING),
            resultSetSupport.getIntOrNull(rs, COLUMN_RATINGS_COUNT),
            resultSetSupport.parseJsonb(rs.getString(COLUMN_TAGS)),
            resultSetSupport.getLocalDateOrNull(rs, COLUMN_PUBLISHED_DATE),
            grayscale
        );
    }

    private BookDetail mapBookDetail(ResultSet rs, int rowNum) throws SQLException {
        Integer width = resultSetSupport.getIntOrNull(rs, COLUMN_COVER_WIDTH);
        Integer height = resultSetSupport.getIntOrNull(rs, COLUMN_COVER_HEIGHT);
        Boolean highRes = resultSetSupport.getBooleanOrNull(rs, COLUMN_COVER_IS_HIGH_RESOLUTION);
        Boolean grayscale = resultSetSupport.getBooleanOrNull(rs, COLUMN_COVER_IS_GRAYSCALE);
        CoverUrlResolver.ResolvedCover cover = CoverUrlResolver.resolve(
            resultSetSupport.getStringOrNull(rs, COLUMN_COVER_S3_KEY),
            resultSetSupport.getStringOrNull(rs, COLUMN_COVER_FALLBACK_URL),
            width,
            height,
            highRes
        );
        String thumbnailUrl = resultSetSupport.getStringOrNull(rs, COLUMN_THUMBNAIL_URL);
        String fallbackUrl = resultSetSupport.getStringOrNull(rs, COLUMN_COVER_FALLBACK_URL);
        String effectiveFallback = StringUtils.hasText(fallbackUrl) ? fallbackUrl : thumbnailUrl;
        CoverUrlResolver.ResolvedCover thumb = CoverUrlResolver.resolve(
            thumbnailUrl,
            thumbnailUrl,
            null,
            null,
            null
        );
        return new BookDetail(
            rs.getString(COLUMN_ID),
            rs.getString(COLUMN_SLUG),
            rs.getString(COLUMN_TITLE),
            rs.getString(COLUMN_DESCRIPTION),
            ValidationUtils.stripWrappingQuotes(rs.getString(COLUMN_PUBLISHER)),
            resultSetSupport.getLocalDateOrNull(rs, COLUMN_PUBLISHED_DATE),
            rs.getString(COLUMN_LANGUAGE),
            resultSetSupport.getIntOrNull(rs, COLUMN_PAGE_COUNT),
            resultSetSupport.parseTextArray(rs.getArray(COLUMN_AUTHORS)),
            resultSetSupport.parseTextArray(rs.getArray(COLUMN_CATEGORIES)),
            cover.url(),
            cover.s3Key(),
            effectiveFallback,
            thumb.url(),
            cover.width(),
            cover.height(),
            cover.highResolution(),
            rs.getString(COLUMN_DATA_SOURCE),
            resultSetSupport.getDoubleOrNull(rs, COLUMN_AVERAGE_RATING),
            resultSetSupport.getIntOrNull(rs, COLUMN_RATINGS_COUNT),
            resultSetSupport.getStringOrNull(rs, COLUMN_ISBN_10),
            resultSetSupport.getStringOrNull(rs, COLUMN_ISBN_13),
            resultSetSupport.getStringOrNull(rs, COLUMN_PREVIEW_LINK),
            resultSetSupport.getStringOrNull(rs, COLUMN_INFO_LINK),
            resultSetSupport.parseJsonb(rs.getString(COLUMN_TAGS)),
            grayscale,
            List.of()
        );
    }

    private EditionSummary mapEditionSummary(ResultSet rs, int rowNum) throws SQLException {
        return new EditionSummary(
            rs.getString(COLUMN_ID),
            rs.getString(COLUMN_SLUG),
            rs.getString(COLUMN_TITLE),
            resultSetSupport.getLocalDateOrNull(rs, COLUMN_PUBLISHED_DATE),
            ValidationUtils.stripWrappingQuotes(rs.getString(COLUMN_PUBLISHER)),
            rs.getString(COLUMN_ISBN_13),
            rs.getString("cover_url"),
            rs.getString("language"),
            resultSetSupport.getIntOrNull(rs, "page_count")
        );
    }
}
