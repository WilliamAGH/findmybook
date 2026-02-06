package net.findmybook.repository;

import tools.jackson.databind.ObjectMapper;
import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.BookListItem;
import net.findmybook.dto.EditionSummary;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.util.cover.CoverUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Repository that centralizes optimized Postgres read queries for book views.
 * Replaces the hydration-heavy implementation with concise functions per DTO use case.
 */
@Repository
public class BookQueryRepository {

    private static final Logger log = LoggerFactory.getLogger(BookQueryRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final BookQueryRowMapperFactory rowMapperFactory;
    private final BookQueryCoverNormalizer coverNormalizer;
    private final BookRecommendationQuerySupport recommendationSupport;

    public BookQueryRepository(JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               @Value("${s3.enabled:true}") boolean s3Enabled,
                               @Value("${s3.cdn-url:}") String cdnBase) {
        this.jdbcTemplate = jdbcTemplate;
        var resultSetSupport = new BookQueryResultSetSupport(objectMapper);
        this.rowMapperFactory = new BookQueryRowMapperFactory(resultSetSupport);
        this.coverNormalizer = new BookQueryCoverNormalizer(jdbcTemplate, resultSetSupport);
        this.recommendationSupport = new BookRecommendationQuerySupport(jdbcTemplate, resultSetSupport, rowMapperFactory);
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

        String sql = "SELECT * FROM get_book_cards(?::UUID[])";
        UUID[] idsArray = bookIds.toArray(new UUID[0]);

        List<BookCard> cards = jdbcTemplate.query(sql, rowMapperFactory.bookCardRowMapper(), (Object) idsArray);
        return coverNormalizer.normalizeBookCardCovers(cards);
    }

    /**
     * Fetch a single book card by canonical UUID.
     */
    public Optional<BookCard> fetchBookCard(UUID bookId) {
        if (bookId == null) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM get_book_cards(?::UUID[])";
        UUID[] idsArray = new UUID[] { bookId };
        List<BookCard> cards = jdbcTemplate.query(sql, rowMapperFactory.bookCardRowMapper(), (Object) idsArray);
        return cards.isEmpty() ? Optional.empty() : Optional.of(cards.get(0));
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

        // Note: collection_id is TEXT (NanoID), not UUID
        String sql = "SELECT * FROM get_book_cards_by_collection(?, ?)";

        List<BookCard> cards = jdbcTemplate.query(sql, rowMapperFactory.bookCardRowMapper(), collectionId, limit);
        return coverNormalizer.normalizeBookCardCovers(cards);
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
    }

    /**
     * Fetch recommendation cards for a canonical book from the persisted recommendation table.
     * Delegates to {@link BookRecommendationQuerySupport} for cluster resolution and merge.
     */
    public List<RecommendationCard> fetchRecommendationCards(UUID sourceBookId, int limit) {
        return recommendationSupport.fetchRecommendationCards(sourceBookId, limit);
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

        String sql = "SELECT * FROM get_book_list_items(?::UUID[])";
        UUID[] idsArray = bookIds.toArray(new UUID[0]);

        List<BookListItem> items = jdbcTemplate.query(sql, rowMapperFactory.bookListItemRowMapper(), (Object) idsArray);
        return coverNormalizer.normalizeBookListItemCovers(items);
    }

    /**
     * Fetch publication years for the supplied book IDs.
     *
     * @param bookIds canonical book identifiers
     * @return map of book id to publication year for rows that have a published date
     */
    public Map<UUID, Integer> fetchPublishedYears(List<UUID> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Map.of();
        }

        String sql = """
            SELECT id, EXTRACT(YEAR FROM published_date)::int AS published_year
            FROM books
            WHERE id = ANY(?::UUID[])
              AND published_date IS NOT NULL
            """;
        UUID[] idsArray = bookIds.toArray(new UUID[0]);
        List<Map.Entry<UUID, Integer>> rows = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> Map.entry(
                (UUID) rs.getObject("id"),
                rs.getInt("published_year")
            ),
            (Object) idsArray
        );

        return rows.stream()
            .filter(entry -> entry.getKey() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
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

        String sql = "SELECT * FROM get_book_detail(?::UUID)";

        List<BookDetail> results = jdbcTemplate.query(sql, rowMapperFactory.bookDetailRowMapper(), bookId);
        List<BookDetail> normalized = coverNormalizer.normalizeBookDetailCovers(results);
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized.get(0));
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

        // First get the book ID by slug, then fetch detail
        String idSql = "SELECT id FROM books WHERE slug = ? LIMIT 1";
        List<UUID> ids = jdbcTemplate.query(idSql, (rs, rowNum) -> (UUID) rs.getObject("id"), slug.trim());

        if (ids.isEmpty()) {
            return Optional.empty();
        }

        return fetchBookDetail(ids.get(0));
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

        String sql = "SELECT * FROM get_book_editions(?::UUID)";

        return jdbcTemplate.query(sql, rowMapperFactory.editionSummaryRowMapper(), bookId);
    }

}
