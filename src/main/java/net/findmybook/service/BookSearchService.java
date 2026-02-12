package net.findmybook.service;

import net.findmybook.dto.BookCard;
import net.findmybook.dto.BookDetail;
import net.findmybook.dto.EditionSummary;
import net.findmybook.dto.RecommendationCard;
import net.findmybook.repository.BookQueryRepository;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.PagingUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.ValidationUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class BookSearchService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_CATEGORY_FACET_LIMIT = 24;
    private static final int MIN_CATEGORY_FACET_LIMIT = 1;
    private static final int MAX_CATEGORY_FACET_MIN_BOOKS = 10_000;

    private final JdbcTemplate jdbcTemplate;
    private final ExternalBookIdResolver externalBookIdResolver;
    private final BackfillCoordinator backfillCoordinator;
    private final BookQueryRepository bookQueryRepository;
    private final SearchResultDeduplicator deduplicator;
    private final boolean asyncBackfillEnabled;

    public BookSearchService(
        JdbcTemplate jdbcTemplate,
        SearchDependencies deps,
        @Value("${app.features.async-backfill.enabled:false}") boolean asyncBackfillEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.externalBookIdResolver = deps.externalBookIdResolver().orElse(null);
        this.backfillCoordinator = deps.backfillCoordinator().orElse(null);
        this.bookQueryRepository = deps.bookQueryRepository().orElse(null);
        this.deduplicator = new SearchResultDeduplicator(jdbcTemplate);
        this.asyncBackfillEnabled = asyncBackfillEnabled;
    }

    @Component
    public static class ConfigLoader {
        @Bean
        public SearchDependencies searchDependencies(
            Optional<ExternalBookIdResolver> externalBookIdResolver,
            Optional<BackfillCoordinator> backfillCoordinator,
            Optional<BookQueryRepository> bookQueryRepository
        ) {
            return new SearchDependencies(externalBookIdResolver, backfillCoordinator, bookQueryRepository);
        }
    }

    public record SearchDependencies(
        Optional<ExternalBookIdResolver> externalBookIdResolver,
        Optional<BackfillCoordinator> backfillCoordinator,
        Optional<BookQueryRepository> bookQueryRepository
    ) {}

    public List<SearchResult> searchBooks(String query, Integer limit) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("BookSearchService requires JdbcTemplate but it is null — database may be unavailable");
        }
        String sanitizedQuery = SearchQueryUtils.normalize(query);
        if (SearchQueryUtils.isWildcard(sanitizedQuery)) {
            log.debug("Postgres search skipped for blank query");
            return List.of();
        }
        int safeLimit = PagingUtils.safeLimit(limit != null ? limit : 0, DEFAULT_LIMIT, 1, MAX_LIMIT);
        List<SearchResult> rawResults = jdbcTemplate.query(
                "SELECT * FROM search_books(?, ?)",
                ps -> {
                    ps.setString(1, sanitizedQuery);
                    ps.setInt(2, safeLimit);
                },
                (rs, rowNum) -> {
                    UUID bookId = rs.getObject("book_id", UUID.class);
                    if (bookId == null) {
                        return null; // Skip null book IDs
                    }
                    return new SearchResult(
                        bookId,
                        rs.getDouble("relevance_score"),
                        rs.getString("match_type"),
                        new ClusterInfo(rs.getInt("edition_count"), rs.getObject("cluster_id", UUID.class)));
                }
            ).stream()
             .filter(result -> result != null)
             .toList();

        List<SearchResult> deduplicated = deduplicator.deduplicate(rawResults);

        if (asyncBackfillEnabled && !deduplicated.isEmpty()) {
            enqueueBackfillForResults(deduplicated);
        }

        return deduplicated;
    }

    public Optional<IsbnSearchResult> searchByIsbn(String isbnQuery) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("BookSearchService requires JdbcTemplate but it is null — database may be unavailable");
        }
        String sanitized = IsbnUtils.sanitize(isbnQuery);
        if (sanitized == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT * FROM search_by_isbn(?)",
                ps -> ps.setString(1, sanitized),
                rs -> rs.next()
                        ? Optional.of(new IsbnSearchResult(
                                rs.getObject("book_id", UUID.class),
                                new BookMetadata(
                                    rs.getString("title"),
                                    rs.getString("subtitle"),
                                    rs.getString("authors")
                                ),
                                new IsbnInfo(
                                    rs.getString("isbn13"),
                                    rs.getString("isbn10")
                                ),
                                rs.getDate("published_date"),
                                ValidationUtils.stripWrappingQuotes(rs.getString("publisher"))))
                        : Optional.empty()
        );
    }

    public List<AuthorResult> searchAuthors(String query, Integer limit) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("BookSearchService requires JdbcTemplate but it is null — database may be unavailable");
        }
        String sanitizedQuery = SearchQueryUtils.normalize(query);
        if (SearchQueryUtils.isWildcard(sanitizedQuery)) {
            log.debug("Author search skipped for blank query");
            return List.of();
        }
        int safeLimit = PagingUtils.safeLimit(limit != null ? limit : 0, DEFAULT_LIMIT, 1, MAX_LIMIT);
        return jdbcTemplate.query(
                "SELECT * FROM search_authors(?, ?)",
                ps -> {
                    ps.setString(1, sanitizedQuery);
                    ps.setInt(2, safeLimit);
                },
                (rs, rowNum) -> new AuthorResult(
                        rs.getString("author_id"),
                        rs.getString("author_name"),
                        rs.getLong("book_count"),
                        rs.getDouble("relevance_score"))
        );
    }

    /**
     * Loads the most populated category/genre facets for the categories route.
     *
     * @param limit maximum number of facets to return
     * @param minBooks minimum number of books required for a category to be included
     * @return descending list of category facets
     */
    public List<CategoryFacet> fetchCategoryFacets(Integer limit, Integer minBooks) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("BookSearchService requires JdbcTemplate but it is null — database may be unavailable");
        }
        int safeLimit = PagingUtils.safeLimit(
            limit != null ? limit : 0,
            DEFAULT_CATEGORY_FACET_LIMIT,
            MIN_CATEGORY_FACET_LIMIT,
            MAX_LIMIT
        );
        int safeMinBooks = minBooks == null ? 1 : Math.max(0, Math.min(minBooks, MAX_CATEGORY_FACET_MIN_BOOKS));
        String sql = """
            SELECT category_name,
                   book_count::int AS book_count
            FROM (
                SELECT DISTINCT ON (bc.normalized_name)
                       bc.display_name AS category_name,
                       COUNT(DISTINCT bcj.book_id) OVER (PARTITION BY bc.normalized_name) AS book_count,
                       bc.normalized_name
                FROM book_collections bc
                JOIN book_collections_join bcj ON bcj.collection_id = bc.id
                WHERE bc.collection_type = 'CATEGORY'
                  AND bc.display_name IS NOT NULL
                  AND TRIM(bc.display_name) <> ''
                ORDER BY bc.normalized_name, bc.display_name
            ) deduped
            WHERE book_count >= ?
            ORDER BY book_count DESC, category_name ASC
            LIMIT ?
            """;
        return jdbcTemplate.query(
            sql,
            ps -> {
                ps.setInt(1, safeMinBooks);
                ps.setInt(2, safeLimit);
            },
            (rs, rowNum) -> new CategoryFacet(
                rs.getString("category_name"),
                rs.getInt("book_count")
            )
        );
    }

    public List<BookCard> fetchBookCards(List<UUID> bookIds) {
        return requireBookQueryRepository().fetchBookCards(bookIds);
    }

    public List<RecommendationCard> fetchRecommendationCards(UUID bookId, int limit) {
        return requireBookQueryRepository().fetchRecommendationCards(bookId, limit);
    }

    /**
     * Checks whether the canonical source work has non-expired recommendation rows.
     *
     * @param bookId canonical source-book UUID
     * @return true when active recommendation rows exist
     */
    public boolean hasActiveRecommendationCards(UUID bookId) {
        return requireBookQueryRepository().hasActiveRecommendationRows(bookId);
    }

    public Optional<BookDetail> fetchBookDetailBySlug(String slug) {
        return requireBookQueryRepository().fetchBookDetailBySlug(slug);
    }

    public Optional<BookDetail> fetchBookDetail(UUID bookId) {
        return requireBookQueryRepository().fetchBookDetail(bookId);
    }

    public Optional<BookCard> fetchBookCard(UUID bookId) {
        return requireBookQueryRepository().fetchBookCard(bookId);
    }

    public List<EditionSummary> fetchBookEditions(UUID bookId) {
        return requireBookQueryRepository().fetchBookEditions(bookId);
    }

    private BookQueryRepository requireBookQueryRepository() {
        // Fail fast: DTO projection endpoints must not silently degrade when repository wiring is missing.
        if (bookQueryRepository == null) {
            throw new IllegalStateException("BookQueryRepository is unavailable for Postgres DTO lookups");
        }
        return bookQueryRepository;
    }

    public void refreshMaterializedView() {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.execute("SELECT refresh_book_search_view()");
        } catch (DataAccessException ex) {
            // Non-critical: search still works with stale materialized view data
            log.warn("Non-critical: Failed to refresh book_search_view: {}", ex.getMessage());
        }
    }
    
    /**
     * Enqueue backfill tasks for search results that might need data enrichment.
     * <p>
     * Strategy:
     * - For each book in search results, look up external IDs
     * - Enqueue high-priority backfill tasks (priority=3) for user-facing search
     * - Runs asynchronously, doesn't block search response
     * <p>
     * Only runs when async-backfill feature flag is enabled.
     */
    private void enqueueBackfillForResults(List<SearchResult> results) {
        if (externalBookIdResolver == null || backfillCoordinator == null) {
            log.debug("Backfill components not available, skipping enqueue");
            return;
        }

        for (SearchResult result : results) {
            Map<String, String> externalIds = externalBookIdResolver.reverse(result.bookId());
            if (externalIds == null) {
                throw new IllegalStateException("External ID resolver returned null map for book " + result.bookId());
            }

            for (Map.Entry<String, String> entry : externalIds.entrySet()) {
                String source = entry.getKey();
                String externalId = entry.getValue();
                backfillCoordinator.enqueue(source, externalId, SEARCH_BACKFILL_PRIORITY);
            }
        }

        log.debug("Enqueued backfill for {} search results", results.size());
    }

    private static final int SEARCH_BACKFILL_PRIORITY = 3;

    /**
     * Value object representing a Postgres search hit.
     *
     * @param bookId canonical (primary) book identifier to hydrate downstream
     * @param relevanceScore ts_rank / lexical relevance score returned by the search function
     * @param matchType indicates which tsvector matched (title, author, etc.)
     * @param editionCount actual number of editions from work cluster (or 1 if not clustered)
     * @param clusterId work cluster identifier (null if book is not in a cluster)
     */
    public record SearchResult(UUID bookId,
                               double relevanceScore,
                               String matchType,
                               ClusterInfo clusterInfo) {
        public SearchResult {
            Objects.requireNonNull(bookId, "bookId");
        }

        public SearchResult(UUID bookId, double relevanceScore, String matchType) {
            this(bookId, relevanceScore, matchType, new ClusterInfo(1, null));
        }

        public SearchResult(UUID bookId, double relevanceScore, String matchType, int editionCount) {
            this(bookId, relevanceScore, matchType, new ClusterInfo(editionCount, null));
        }
        


        public int editionCount() {
            return clusterInfo.editionCount();
        }

        public UUID clusterId() {
            return clusterInfo.clusterId();
        }

        public String matchTypeNormalized() {
            return matchType == null ? "UNKNOWN" : matchType.toUpperCase(Locale.ROOT);
        }
    }

    public record ClusterInfo(int editionCount, UUID clusterId) {
        public ClusterInfo {
            editionCount = editionCount < 1 ? 1 : editionCount;
        }
    }

    public record AuthorResult(String authorId, String authorName, long bookCount, double relevanceScore) {
    }

    public record CategoryFacet(String name, int bookCount) {
        public CategoryFacet {
            name = name == null ? null : name.trim();
            if (!StringUtils.hasText(name)) {
                throw new IllegalArgumentException("Category facet name cannot be blank");
            }
            bookCount = Math.max(0, bookCount);
        }
    }

    public record IsbnSearchResult(UUID bookId,
                                   BookMetadata metadata,
                                   IsbnInfo isbns,
                                   java.util.Date publishedDate,
                                   String publisher) {
        

        
        public String title() { return metadata.title(); }
        public String subtitle() { return metadata.subtitle(); }
        public String authors() { return metadata.authors(); }
        public String isbn13() { return isbns.isbn13(); }
        public String isbn10() { return isbns.isbn10(); }
    }
    
    public record BookMetadata(String title, String subtitle, String authors) {}
    public record IsbnInfo(String isbn13, String isbn10) {}
}
