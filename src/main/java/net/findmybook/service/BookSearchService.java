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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class BookSearchService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ExternalBookIdResolver externalBookIdResolver;
    private final BackfillCoordinator backfillCoordinator;
    private final BookQueryRepository bookQueryRepository;
    private final SearchResultDeduplicator deduplicator;
    private final boolean asyncBackfillEnabled;

    public BookSearchService(
        JdbcTemplate jdbcTemplate,
        Optional<ExternalBookIdResolver> externalBookIdResolver,
        Optional<BackfillCoordinator> backfillCoordinator,
        Optional<BookQueryRepository> bookQueryRepository,
        @Value("${app.features.async-backfill.enabled:false}") boolean asyncBackfillEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.externalBookIdResolver = externalBookIdResolver.orElse(null);
        this.backfillCoordinator = backfillCoordinator.orElse(null);
        this.bookQueryRepository = bookQueryRepository.orElse(null);
        this.deduplicator = new SearchResultDeduplicator(jdbcTemplate);
        this.asyncBackfillEnabled = asyncBackfillEnabled;
    }

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
                        rs.getInt("edition_count"),
                        rs.getObject("cluster_id", UUID.class));
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
                                rs.getString("title"),
                                rs.getString("subtitle"),
                                rs.getString("authors"),
                                rs.getString("isbn13"),
                                rs.getString("isbn10"),
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

    public List<BookCard> fetchBookCards(List<UUID> bookIds) {
        return requireBookQueryRepository().fetchBookCards(bookIds);
    }

    public List<RecommendationCard> fetchRecommendationCards(UUID bookId, int limit) {
        return requireBookQueryRepository().fetchRecommendationCards(bookId, limit);
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
                backfillCoordinator.enqueue(source, externalId, 3);
            }
        }

        log.debug("Enqueued backfill for {} search results", results.size());
    }

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
                               int editionCount,
                               UUID clusterId) {
        public SearchResult {
            Objects.requireNonNull(bookId, "bookId");
            editionCount = editionCount < 1 ? 1 : editionCount;
        }

        public SearchResult(UUID bookId, double relevanceScore, String matchType) {
            this(bookId, relevanceScore, matchType, 1, null);
        }

        public SearchResult(UUID bookId, double relevanceScore, String matchType, int editionCount) {
            this(bookId, relevanceScore, matchType, editionCount, null);
        }

        public String matchTypeNormalized() {
            return matchType == null ? "UNKNOWN" : matchType.toUpperCase(Locale.ROOT);
        }
    }

    public record AuthorResult(String authorId, String authorName, long bookCount, double relevanceScore) {
    }

    public record IsbnSearchResult(UUID bookId,
                                   String title,
                                   String subtitle,
                                   String authors,
                                   String isbn13,
                                   String isbn10,
                                   java.util.Date publishedDate,
                                   String publisher) {
    }
}
