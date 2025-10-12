package com.williamcallahan.book_recommendation_engine.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.book_recommendation_engine.dto.BookCard;
import com.williamcallahan.book_recommendation_engine.dto.BookDetail;
import com.williamcallahan.book_recommendation_engine.dto.BookListItem;
import com.williamcallahan.book_recommendation_engine.dto.EditionSummary;
import com.williamcallahan.book_recommendation_engine.dto.RecommendationCard;
import com.williamcallahan.book_recommendation_engine.util.ValidationUtils;
import com.williamcallahan.book_recommendation_engine.util.UuidUtils;
import com.williamcallahan.book_recommendation_engine.util.cover.CoverUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Repository that centralises optimized Postgres read queries for book views.
 * Replaces the hydration-heavy implementation with concise functions per DTO use case.
 */
@Repository
public class BookQueryRepository {

    private static final Logger log = LoggerFactory.getLogger(BookQueryRepository.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    public BookQueryRepository(JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               @Value("${s3.enabled:true}") boolean ignored,
                               @Value("${s3.cdn-url:}") String ignoredCdn) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== Book Cards ====================
    
    /**
     * Fetch minimal book data for card displays (homepage, search grid).
     * SINGLE QUERY replaces 5 hydration queries per book.
     * 
     * This is THE SINGLE SOURCE for card data - all card views must use this method.
     * 
     * @param bookIds List of book UUIDs to fetch
     * @return List of BookCard DTOs with all card-specific data
     */
    public List<BookCard> fetchBookCards(List<UUID> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return List.of();
        }

        try {
            String sql = "SELECT * FROM get_book_cards(?::UUID[])";
            UUID[] idsArray = bookIds.toArray(new UUID[0]);

            List<BookCard> cards = jdbcTemplate.query(sql, new BookCardRowMapper(), (Object) idsArray);
            return normalizeBookCardCovers(cards);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch book cards for {} books: {}", bookIds.size(), ex.getMessage(), ex);
            return List.of();
        }
    }

    /**
     * Fetch a single book card by canonical UUID.
     */
    public Optional<BookCard> fetchBookCard(UUID bookId) {
        if (bookId == null) {
            return Optional.empty();
        }
        try {
            String sql = "SELECT * FROM get_book_cards(?::UUID[])";
            UUID[] idsArray = new UUID[] { bookId };
            List<BookCard> cards = jdbcTemplate.query(sql, new BookCardRowMapper(), (Object) idsArray);
            return cards.isEmpty() ? Optional.empty() : Optional.of(cards.get(0));
        } catch (DataAccessException ex) {
            log.error("Failed to fetch book card for {}: {}", bookId, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    /**
     * Fetch book cards by collection (e.g., NYT bestsellers for homepage).
     * SINGLE QUERY with position ordering.
     * 
     * This is THE SINGLE SOURCE for collection-based card fetching.
     * 
     * @param collectionId Collection ID (NanoID text, not UUID)
     * @param limit Maximum number of books to return
     * @return List of BookCard DTOs ordered by collection position
     */
    public List<BookCard> fetchBookCardsByCollection(String collectionId, int limit) {
        if (collectionId == null || collectionId.isBlank() || limit <= 0) {
            return List.of();
        }

        try {
            // Note: collection_id is TEXT (NanoID), not UUID
            String sql = "SELECT * FROM get_book_cards_by_collection(?, ?)";
            
            List<BookCard> cards = jdbcTemplate.query(sql, new BookCardRowMapper(), collectionId, limit);
            return normalizeBookCardCovers(cards);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch book cards for collection {}: {}", collectionId, ex.getMessage(), ex);
            return List.of();
        }
    }

    /**
     * Fetch book cards by NYT bestseller list provider code.
     * THE SINGLE SOURCE for NYT bestseller fetching.
     * 
     * This method finds the latest collection for the given provider code,
     * then fetches the book cards. Replaces PostgresBookRepository.fetchLatestBestsellerBooks.
     * 
     * @param providerListCode NYT list code (e.g., "hardcover-fiction")
     * @param limit Maximum number of books to return
     * @return List of BookCard DTOs ordered by position
     */
    public List<BookCard> fetchBookCardsByProviderListCode(String providerListCode, int limit) {
        String trimmedCode = providerListCode == null ? null : providerListCode.trim();
        if (trimmedCode == null || trimmedCode.isBlank() || limit <= 0) {
            return List.of();
        }

        try {
            // First get the latest collection ID for this provider code
            String collectionSql = """
                SELECT id
                FROM book_collections
                WHERE collection_type = 'BESTSELLER_LIST'
                  AND source = 'NYT'
                  AND provider_list_code = ?
                ORDER BY published_date DESC, updated_at DESC
                LIMIT 1
                """;
            
            List<String> collectionIds = jdbcTemplate.query(
                collectionSql,
                (rs, rowNum) -> rs.getString("id"),
                trimmedCode
            ).stream()
            .filter(Objects::nonNull)
            .filter(id -> !id.isBlank())
            .collect(Collectors.toList());
            
            if (collectionIds.isEmpty()) {
                log.debug("No collection found for provider list code: {}", trimmedCode);
                return List.of();
            }
            
            // Then fetch the book cards for that collection
            return fetchBookCardsByCollection(collectionIds.get(0), limit);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch book cards for provider list '{}': {}", providerListCode, ex.getMessage(), ex);
            return List.of();
        }
    }

    /**
     * Fetch recommendation cards for a canonical book from the persisted recommendation table.
     */
    public List<RecommendationCard> fetchRecommendationCards(UUID sourceBookId, int limit) {
        if (sourceBookId == null || limit <= 0) {
            return List.of();
        }

        try {
            String sql = """
                SELECT bc.*, br.score, br.reason
                FROM book_recommendations br
                JOIN LATERAL get_book_cards(ARRAY[br.recommended_book_id]) bc ON TRUE
                WHERE br.source_book_id = ?::uuid
                  AND (br.expires_at IS NULL OR br.expires_at > NOW())
                ORDER BY br.score DESC NULLS LAST, br.generated_at DESC
                LIMIT ?
                """;
            BookCardRowMapper mapper = new BookCardRowMapper();
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                BookCard card = mapper.mapRow(rs, rowNum);
                Double score = getDoubleOrNull(rs, "score");
                String reason = rs.getString("reason");
                return new RecommendationCard(card, score, reason);
            }, sourceBookId, limit);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch recommendation cards for {}: {}", sourceBookId, ex.getMessage(), ex);
            return List.of();
        }
    }

    // ==================== Book List Items ====================
    
    /**
     * Fetch extended book data for search list view.
     * SINGLE QUERY replaces 6 hydration queries per book.
     * 
     * This is THE SINGLE SOURCE for list item data - all list views must use this method.
     * 
     * @param bookIds List of book UUIDs to fetch
     * @return List of BookListItem DTOs with description and categories
     */
    public List<BookListItem> fetchBookListItems(List<UUID> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return List.of();
        }

        try {
            String sql = "SELECT * FROM get_book_list_items(?::UUID[])";
            UUID[] idsArray = bookIds.toArray(new UUID[0]);
            
            List<BookListItem> items = jdbcTemplate.query(sql, new BookListItemRowMapper(), (Object) idsArray);
            return normalizeBookListItemCovers(items);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch book list items for {} books: {}", bookIds.size(), ex.getMessage(), ex);
            return List.of();
        }
    }

    // ==================== Book Detail ====================
    
    /**
     * Fetch complete book detail for detail page by UUID.
     * SINGLE QUERY replaces 6-10 hydration queries per book.
     * 
     * This is THE SINGLE SOURCE for book detail data by ID.
     * 
     * @param bookId Book UUID
     * @return Optional BookDetail DTO, empty if not found
     */
    public Optional<BookDetail> fetchBookDetail(UUID bookId) {
        if (bookId == null) {
            return Optional.empty();
        }

        try {
            String sql = "SELECT * FROM get_book_detail(?::UUID)";
            
            List<BookDetail> results = jdbcTemplate.query(sql, new BookDetailRowMapper(), bookId);
            List<BookDetail> normalized = normalizeBookDetailCovers(results);
            return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized.get(0));
        } catch (DataAccessException ex) {
            log.error("Failed to fetch book detail for {}: {}", bookId, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    /**
     * Fetch book detail by slug (URL-friendly identifier).
     * This is THE SINGLE SOURCE for book detail data by slug.
     * 
     * @param slug Book slug (e.g., "harry-potter-philosophers-stone")
     * @return Optional BookDetail DTO, empty if not found
     */
    public Optional<BookDetail> fetchBookDetailBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }

        try {
            // First get the book ID by slug, then fetch detail
            String idSql = "SELECT id FROM books WHERE slug = ? LIMIT 1";
            List<UUID> ids = jdbcTemplate.query(idSql, (rs, rowNum) -> (UUID) rs.getObject("id"), slug.trim());
            
            if (ids.isEmpty()) {
                return Optional.empty();
            }
            
            return fetchBookDetail(ids.get(0));
        } catch (DataAccessException ex) {
            log.error("Failed to fetch book detail by slug '{}': {}", slug, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    // ==================== Book Editions ====================
    
    /**
     * Fetch other editions of a book (for Editions tab).
     * This is THE SINGLE SOURCE for editions data.
     * 
     * @param bookId Book UUID to find editions for
     * @return List of EditionSummary DTOs (other editions excluding the book itself)
     */
    public List<EditionSummary> fetchBookEditions(UUID bookId) {
        if (bookId == null) {
            return List.of();
        }

        try {
            String sql = "SELECT * FROM get_book_editions(?::UUID)";
            
            return jdbcTemplate.query(sql, new EditionSummaryRowMapper(), bookId);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch editions for book {}: {}", bookId, ex.getMessage(), ex);
            return List.of();
        }
    }

    // ==================== Row Mappers (Internal) ====================
    
    /**
     * Maps database row to BookCard DTO - internal implementation detail.
     */
    private class BookCardRowMapper implements RowMapper<BookCard> {
        @Override
        public BookCard mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            List<String> authors = parseTextArray(rs.getArray("authors"));
            String rawCover = rs.getString("cover_url");
            CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(rawCover, rawCover, null, null, null);
            log.trace("BookCard row: id={}, title={}, authors={}, coverUrl={} -> {}",
                rs.getString("id"), rs.getString("title"), authors, rawCover, resolved.url());
            return new BookCard(
                rs.getString("id"),
                rs.getString("slug"),
                rs.getString("title"),
                authors,
                resolved.url(),
                getDoubleOrNull(rs, "average_rating"),
                getIntOrNull(rs, "ratings_count"),
                parseJsonb(rs.getString("tags"))
            );
        }
    }

    /**
     * Maps database row to BookListItem DTO - internal implementation detail.
     */
    private class BookListItemRowMapper implements RowMapper<BookListItem> {
        @Override
        public BookListItem mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            Integer width = getIntOrNull(rs, "cover_width");
            Integer height = getIntOrNull(rs, "cover_height");
            Boolean highRes = getBooleanOrNull(rs, "cover_is_high_resolution");
            CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
                rs.getString("cover_url"),
                rs.getString("cover_url"),
                width,
                height,
                highRes
            );
            return new BookListItem(
                rs.getString("id"),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("description"),
                parseTextArray(rs.getArray("authors")),
                parseTextArray(rs.getArray("categories")),
                resolved.url(),
                resolved.width(),
                resolved.height(),
                resolved.highResolution(),
                getDoubleOrNull(rs, "average_rating"),
                getIntOrNull(rs, "ratings_count"),
                parseJsonb(rs.getString("tags"))
            );
        }
    }

    /**
     * Maps database row to BookDetail DTO - internal implementation detail.
     */
    private class BookDetailRowMapper implements RowMapper<BookDetail> {
        @Override
        public BookDetail mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            Integer width = getIntOrNull(rs, "cover_width");
            Integer height = getIntOrNull(rs, "cover_height");
            Boolean highRes = getBooleanOrNull(rs, "cover_is_high_resolution");
            CoverUrlResolver.ResolvedCover cover = CoverUrlResolver.resolve(
                rs.getString("cover_url"),
                rs.getString("thumbnail_url"),
                width,
                height,
                highRes
            );
            CoverUrlResolver.ResolvedCover thumb = CoverUrlResolver.resolve(
                rs.getString("thumbnail_url"),
                rs.getString("thumbnail_url"),
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
                getLocalDateOrNull(rs, "published_date"),
                rs.getString("language"),
                getIntOrNull(rs, "page_count"),
                parseTextArray(rs.getArray("authors")),
                parseTextArray(rs.getArray("categories")),
                cover.url(),
                thumb.url(),
                cover.width(),
                cover.height(),
                cover.highResolution(),
                rs.getString("data_source"),
                getDoubleOrNull(rs, "average_rating"),
                getIntOrNull(rs, "ratings_count"),
                rs.getString("isbn_10"),
                rs.getString("isbn_13"),
                rs.getString("preview_link"),
                rs.getString("info_link"),
                parseJsonb(rs.getString("tags")),
                List.of()
            );
        }
    }

    /**
     * Maps database row to EditionSummary DTO - internal implementation detail.
     */
    private class EditionSummaryRowMapper implements RowMapper<EditionSummary> {
        @Override
        public EditionSummary mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            return new EditionSummary(
                rs.getString("id"),
                rs.getString("slug"),
                rs.getString("title"),
                getLocalDateOrNull(rs, "published_date"),
                ValidationUtils.stripWrappingQuotes(rs.getString("publisher")),
                rs.getString("isbn_13"),
                rs.getString("cover_url"),
                rs.getString("language"),
                getIntOrNull(rs, "page_count")
            );
        }
    }

    private List<BookCard> normalizeBookCardCovers(List<BookCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return cards;
        }

        List<UUID> fallbackIds = cards.stream()
            .filter(card -> needsFallback(card.coverUrl()))
            .map(BookCard::id)
            .map(UuidUtils::parseUuidOrNull)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<UUID, CoverUrlResolver.ResolvedCover> fallbacks = fetchFallbackCovers(fallbackIds);

        return cards.stream()
            .map(card -> {
                UUID id = UuidUtils.parseUuidOrNull(card.id());
                CoverUrlResolver.ResolvedCover fallback = id != null ? fallbacks.get(id) : null;
                String coverUrl = fallback != null ? fallback.url() : card.coverUrl();
                return new BookCard(
                    card.id(),
                    card.slug(),
                    card.title(),
                    card.authors(),
                    coverUrl,
                    card.averageRating(),
                    card.ratingsCount(),
                    card.tags()
                );
            })
            .toList();
    }

    private List<BookListItem> normalizeBookListItemCovers(List<BookListItem> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }

        List<UUID> fallbackIds = items.stream()
            .filter(item -> needsFallback(item.coverUrl()))
            .map(BookListItem::id)
            .map(UuidUtils::parseUuidOrNull)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<UUID, CoverUrlResolver.ResolvedCover> fallbacks = fetchFallbackCovers(fallbackIds);

        return items.stream()
            .map(item -> {
                UUID id = UuidUtils.parseUuidOrNull(item.id());
                CoverUrlResolver.ResolvedCover fallback = id != null ? fallbacks.get(id) : null;
                String coverUrl = fallback != null ? fallback.url() : item.coverUrl();
                Integer width = fallback != null ? fallback.width() : item.coverWidth();
                Integer height = fallback != null ? fallback.height() : item.coverHeight();
                Boolean highRes = fallback != null ? fallback.highResolution() : item.coverHighResolution();

                return new BookListItem(
                    item.id(),
                    item.slug(),
                    item.title(),
                    item.description(),
                    item.authors(),
                    item.categories(),
                    coverUrl,
                    width,
                    height,
                    highRes,
                    item.averageRating(),
                    item.ratingsCount(),
                    item.tags()
                );
            })
            .toList();
    }

    private List<BookDetail> normalizeBookDetailCovers(List<BookDetail> details) {
        if (details == null || details.isEmpty()) {
            return details;
        }

        List<UUID> fallbackIds = details.stream()
            .filter(detail -> needsFallback(detail.coverUrl()))
            .map(BookDetail::id)
            .map(UuidUtils::parseUuidOrNull)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<UUID, CoverUrlResolver.ResolvedCover> fallbacks = fetchFallbackCovers(fallbackIds);

        return details.stream()
            .map(detail -> {
                UUID id = UuidUtils.parseUuidOrNull(detail.id());
                CoverUrlResolver.ResolvedCover fallback = id != null ? fallbacks.get(id) : null;
                String coverUrl = fallback != null ? fallback.url() : detail.coverUrl();
                Integer width = fallback != null ? fallback.width() : detail.coverWidth();
                Integer height = fallback != null ? fallback.height() : detail.coverHeight();
                Boolean highRes = fallback != null ? fallback.highResolution() : detail.coverHighResolution();

                return new BookDetail(
                    detail.id(),
                    detail.slug(),
                    detail.title(),
                    detail.description(),
                    detail.publisher(),
                    detail.publishedDate(),
                    detail.language(),
                    detail.pageCount(),
                    detail.authors(),
                    detail.categories(),
                    coverUrl,
                    detail.thumbnailUrl(),
                    width,
                    height,
                    highRes,
                    detail.dataSource(),
                    detail.averageRating(),
                    detail.ratingsCount(),
                    detail.isbn10(),
                    detail.isbn13(),
                    detail.previewLink(),
                    detail.infoLink(),
                    detail.tags(),
                    detail.editions()
                );
            })
            .toList();
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
                    Integer width = getIntOrNull(rs, "width");
                    Integer height = getIntOrNull(rs, "height");
                    Boolean highRes = getBooleanOrNull(rs, "is_high_resolution");

                    if (ValidationUtils.hasText(url) || ValidationUtils.hasText(s3Key)) {
                        resolved.put(
                            id,
                            CoverUrlResolver.resolve(
                                ValidationUtils.hasText(s3Key) ? s3Key : url,
                                ValidationUtils.hasText(url) ? url : null,
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
            log.warn("Failed to fetch fallback cover URLs for {} books: {}", bookIds.size(), ex.getMessage());
            return Map.of();
        }
    }

    private boolean needsFallback(String url) {
        if (!ValidationUtils.hasText(url)) {
            return true;
        }
        String lower = url.toLowerCase();
        if (lower.contains("placeholder-book-cover.svg")) {
            return true;
        }
        if (lower.contains("://localhost") || lower.contains("://127.0.0.1") || lower.contains("://0.0.0.0")) {
            return true;
        }
        if (lower.contains("/images/book-covers/")) {
            return true;
        }
        return !(url.startsWith("http://") || url.startsWith("https://"));
    }

    // ==================== Helper Methods (Internal) ====================
    
    /**
     * Parse Postgres TEXT[] array to Java List<String>.
     */
    private List<String> parseTextArray(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        
        String[] array = (String[]) sqlArray.getArray();
        return array == null ? List.of() : Arrays.asList(array);
    }

    /**
     * Parse Postgres JSONB to Java Map.
     */
    private Map<String, Object> parseJsonb(String jsonb) {
        if (jsonb == null || jsonb.isBlank() || jsonb.equals("{}")) {
            return Map.of();
        }
        
        try {
            return objectMapper.readValue(jsonb, MAP_TYPE);
        } catch (Exception ex) {
            log.warn("Failed to parse JSONB: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Safely get nullable Double from ResultSet.
     */
    private Double getDoubleOrNull(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    /**
     * Safely get nullable Integer from ResultSet.
     */
    private Integer getIntOrNull(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    /**
     * Safely get nullable Boolean from ResultSet.
     */
    private Boolean getBooleanOrNull(ResultSet rs, String columnName) throws SQLException {
        boolean value = rs.getBoolean(columnName);
        return rs.wasNull() ? null : value;
    }

    /**
     * Safely get nullable LocalDate from ResultSet.
     */
    private LocalDate getLocalDateOrNull(ResultSet rs, String columnName) throws SQLException {
        Date date = rs.getDate(columnName);
        return date == null ? null : date.toLocalDate();
    }
}
