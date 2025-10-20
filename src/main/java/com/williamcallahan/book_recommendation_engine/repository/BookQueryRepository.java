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
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * Repository that centralizes optimized Postgres read queries for book views.
 * Replaces the hydration-heavy implementation with concise functions per DTO use case.
 */
@Repository
public class BookQueryRepository {

    private static final Logger log = LoggerFactory.getLogger(BookQueryRepository.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /**
     * Cache column labels per {@link ResultSet} to avoid recalculating metadata for every column lookup.
     * Weak keys ensure the cache entries disappear once the driver releases the result set.
     */
    private final Map<ResultSet, Set<String>> columnMetadataCache =
        Collections.synchronizedMap(new WeakHashMap<>());

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    public BookQueryRepository(JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               @Value("${s3.enabled:true}") boolean s3Enabled,
                               @Value("${s3.cdn-url:}") String cdnBase) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        String normalizedCdn = (cdnBase == null || cdnBase.isBlank()) ? null : cdnBase.trim();
        CoverUrlResolver.setCdnBase(s3Enabled ? normalizedCdn : null);
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

        List<UUID> resolvedSourceIds = resolveClusterSourceIds(sourceBookId);
        final List<UUID> candidateSourceIds = resolvedSourceIds.isEmpty()
            ? List.of(sourceBookId)
            : resolvedSourceIds;

        String placeholders = candidateSourceIds.stream()
            .map(id -> "?")
            .collect(Collectors.joining(", "));

        int fetchLimit = Math.max(limit, Math.min(limit * candidateSourceIds.size(), limit * 3));

        String sql = """
            SELECT bc.*, br.score, br.reason, br.source
            FROM book_recommendations br
            JOIN LATERAL get_book_cards(ARRAY[br.recommended_book_id]) bc ON TRUE
            WHERE br.source_book_id IN (%s)
              AND (br.expires_at IS NULL OR br.expires_at > NOW())
            ORDER BY CASE WHEN br.source_book_id = ? THEN 0 ELSE 1 END,
                     br.score DESC NULLS LAST,
                     br.generated_at DESC
            LIMIT ?
            """.formatted(placeholders);

        try {
            BookCardRowMapper mapper = new BookCardRowMapper();
            List<RecommendationCard> raw = jdbcTemplate.query(sql, ps -> {
                int index = 1;
                for (UUID id : candidateSourceIds) {
                    ps.setObject(index++, id);
                }
                ps.setObject(index++, sourceBookId);
                ps.setInt(index, fetchLimit);
            }, (rs, rowNum) -> {
                BookCard card = mapper.mapRow(rs, rowNum);
                Double score = getDoubleOrNull(rs, "score");
                String reason = rs.getString("reason");
                String source = rs.getString("source");
                return new RecommendationCard(card, score, reason, source);
            });
            return mergeRecommendations(raw, limit);
        } catch (DataAccessException ex) {
            log.error("Failed to fetch recommendation cards for {}: {}", sourceBookId, ex.getMessage(), ex);
            return List.of();
        }
    }

    private List<UUID> resolveClusterSourceIds(UUID sourceBookId) {
        if (jdbcTemplate == null || sourceBookId == null) {
            return new ArrayList<>();
        }

        try {
            UUID canonical = jdbcTemplate.query(
                """
                SELECT primary_wcm.book_id
                FROM work_cluster_members wcm
                JOIN work_cluster_members primary_wcm
                  ON primary_wcm.cluster_id = wcm.cluster_id
                 AND primary_wcm.is_primary = true
                WHERE wcm.book_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? (UUID) rs.getObject(1) : null,
                sourceBookId
            );

            List<UUID> clusterMembers = jdbcTemplate.query(
                """
                SELECT DISTINCT wcm2.book_id
                FROM work_cluster_members wcm1
                JOIN work_cluster_members wcm2 ON wcm1.cluster_id = wcm2.cluster_id
                WHERE wcm1.book_id = ?
                """,
                (rs, rowNum) -> (UUID) rs.getObject(1),
                sourceBookId
            );

            LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
            if (canonical != null) {
                ordered.add(canonical);
            }
            ordered.add(sourceBookId);
            ordered.addAll(clusterMembers);
            return new ArrayList<>(ordered);
        } catch (DataAccessException ex) {
            log.debug("Failed to resolve cluster member IDs for {}: {}", sourceBookId, ex.getMessage());
            return new ArrayList<>(List.of(sourceBookId));
        }
    }

    private List<RecommendationCard> mergeRecommendations(List<RecommendationCard> raw, int limit) {
        if (raw == null || raw.isEmpty() || limit <= 0) {
            return List.of();
        }

        List<RecommendationCard> pipeline = new ArrayList<>();
        List<RecommendationCard> sameAuthor = new ArrayList<>();
        List<RecommendationCard> sameCategory = new ArrayList<>();
        List<RecommendationCard> others = new ArrayList<>();

        for (RecommendationCard card : raw) {
            if (card == null) {
                continue;
            }
            String source = card.source() != null ? card.source().toUpperCase(Locale.ROOT) : "";
            if ("RECOMMENDATION_PIPELINE".equals(source)) {
                pipeline.add(card);
            } else if ("SAME_AUTHOR".equals(source)) {
                sameAuthor.add(card);
            } else if ("SAME_CATEGORY".equals(source)) {
                sameCategory.add(card);
            } else {
                others.add(card);
            }
        }

        List<RecommendationCard> merged = new ArrayList<>(limit);
        Set<String> seen = new LinkedHashSet<>();
        int remaining = limit;

        remaining = appendRecommendations(merged, seen, pipeline, remaining, pipeline.size());
        if (remaining > 0) {
            remaining = appendRecommendations(merged, seen, sameAuthor, remaining, Math.min(3, remaining));
        }
        if (remaining > 0) {
            remaining = appendRecommendations(merged, seen, sameCategory, remaining, Math.min(3, remaining));
        }
        if (remaining > 0) {
            remaining = appendRecommendations(merged, seen, others, remaining, remaining);
        }

        return merged;
    }

    private int appendRecommendations(List<RecommendationCard> target,
                                      Set<String> seen,
                                      List<RecommendationCard> source,
                                      int remaining,
                                      int maxFromSource) {
        if (remaining <= 0 || source.isEmpty() || maxFromSource <= 0) {
            return remaining;
        }

        int added = 0;
        for (RecommendationCard card : source) {
            if (remaining <= 0 || added >= maxFromSource) {
                break;
            }
            if (card == null || card.card() == null || !ValidationUtils.hasText(card.card().id())) {
                continue;
            }
            if (seen.add(card.card().id())) {
                target.add(card);
                remaining--;
                added++;
            }
        }
        return remaining;
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
            String effectiveFallback = ValidationUtils.hasText(fallbackUrl) ? fallbackUrl : preferredUrl;
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
                getStringOrNull(rs, "cover_s3_key"),
                getStringOrNull(rs, "cover_fallback_url"),
                width,
                height,
                highRes
            );
            String fallbackUrl = getStringOrNull(rs, "cover_fallback_url");
            String effectiveFallback = ValidationUtils.hasText(fallbackUrl) ? fallbackUrl : resolved.url();
            return new BookListItem(
                rs.getString("id"),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("description"),
                parseTextArray(rs.getArray("authors")),
                parseTextArray(rs.getArray("categories")),
                resolved.url(),
                resolved.s3Key(),
                effectiveFallback,
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
                getStringOrNull(rs, "cover_s3_key"),
                getStringOrNull(rs, "cover_fallback_url"),
                width,
                height,
                highRes
            );
            String thumbnailUrl = getStringOrNull(rs, "thumbnail_url");
            String fallbackUrl = getStringOrNull(rs, "cover_fallback_url");
            String effectiveFallback = ValidationUtils.hasText(fallbackUrl) ? fallbackUrl : thumbnailUrl;
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
                getLocalDateOrNull(rs, "published_date"),
                rs.getString("language"),
                getIntOrNull(rs, "page_count"),
                parseTextArray(rs.getArray("authors")),
                parseTextArray(rs.getArray("categories")),
                cover.url(),
                cover.s3Key(),
                effectiveFallback,
                thumb.url(),
                cover.width(),
                cover.height(),
                cover.highResolution(),
                rs.getString("data_source"),
                getDoubleOrNull(rs, "average_rating"),
                getIntOrNull(rs, "ratings_count"),
                rs.getString("isbn_10"),
                rs.getString("isbn_13"),
                getStringOrNull(rs, "preview_link"),
                getStringOrNull(rs, "info_link"),
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
                String fallbackUrl = ValidationUtils.hasText(card.fallbackCoverUrl())
                    ? card.fallbackCoverUrl()
                    : (fallback != null ? fallback.url() : coverUrl);
                String s3Key = fallback != null && ValidationUtils.hasText(fallback.s3Key())
                    ? fallback.s3Key()
                    : card.coverS3Key();
                return new BookCard(
                    card.id(),
                    card.slug(),
                    card.title(),
                    card.authors(),
                    coverUrl,
                    s3Key,
                    fallbackUrl,
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
                String fallbackUrl = ValidationUtils.hasText(item.coverFallbackUrl())
                    ? item.coverFallbackUrl()
                    : (fallback != null ? fallback.url() : coverUrl);
                String s3Key = fallback != null && ValidationUtils.hasText(fallback.s3Key())
                    ? fallback.s3Key()
                    : item.coverS3Key();

                return new BookListItem(
                    item.id(),
                    item.slug(),
                    item.title(),
                    item.description(),
                    item.authors(),
                    item.categories(),
                    coverUrl,
                    s3Key,
                    fallbackUrl,
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
                String s3Key = fallback != null && ValidationUtils.hasText(fallback.s3Key())
                    ? fallback.s3Key()
                    : detail.coverS3Key();
                String fallbackUrl = ValidationUtils.hasText(detail.coverFallbackUrl())
                    ? detail.coverFallbackUrl()
                    : detail.thumbnailUrl();

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
                    s3Key,
                    fallbackUrl,
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
        if (lower.contains("/images/book-covers/") && !CoverUrlResolver.isCdnUrl(url)) {
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
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    /**
     * Safely get nullable Integer from ResultSet.
     */
    private Integer getIntOrNull(ResultSet rs, String columnName) throws SQLException {
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    /**
     * Safely get nullable Boolean from ResultSet.
     */
    private Boolean getBooleanOrNull(ResultSet rs, String columnName) throws SQLException {
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        boolean value = rs.getBoolean(columnName);
        return rs.wasNull() ? null : value;
    }

    /**
     * Safely get nullable LocalDate from ResultSet.
     */
    private LocalDate getLocalDateOrNull(ResultSet rs, String columnName) throws SQLException {
        if (!hasColumn(rs, columnName)) {
            return null;
        }
        Date date = rs.getDate(columnName);
        return date == null ? null : date.toLocalDate();
    }

    private String getStringOrNull(ResultSet rs, String columnName) throws SQLException {
        return hasColumn(rs, columnName) ? rs.getString(columnName) : null;
    }

    private boolean hasColumn(ResultSet rs, String columnName) throws SQLException {
        Set<String> columnLabels = columnMetadataCache.get(rs);
        if (columnLabels == null) {
            columnLabels = loadColumnLabels(rs);
            columnMetadataCache.put(rs, columnLabels);
        }
        return columnLabels.contains(columnName.toLowerCase(Locale.ROOT));
    }

    private Set<String> loadColumnLabels(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        Set<String> labels = new HashSet<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            if (label != null) {
                labels.add(label.toLowerCase(Locale.ROOT));
            }
        }
        return labels;
    }
}
