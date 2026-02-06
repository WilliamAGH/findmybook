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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BookSearchService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ExternalBookIdResolver externalBookIdResolver;
    private final BackfillCoordinator backfillCoordinator;
    private final BookQueryRepository bookQueryRepository;
    
    @Value("${app.features.async-backfill.enabled:false}")
    private boolean asyncBackfillEnabled;

    public BookSearchService(
        JdbcTemplate jdbcTemplate,
        Optional<ExternalBookIdResolver> externalBookIdResolver,
        Optional<BackfillCoordinator> backfillCoordinator,
        Optional<BookQueryRepository> bookQueryRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.externalBookIdResolver = externalBookIdResolver.orElse(null);
        this.backfillCoordinator = backfillCoordinator.orElse(null);
        this.bookQueryRepository = bookQueryRepository.orElse(null);
    }

    public List<SearchResult> searchBooks(String query, Integer limit) {
        if (jdbcTemplate == null) {
            return List.of();
        }
        String sanitizedQuery = SearchQueryUtils.normalize(query);
        if (SearchQueryUtils.isWildcard(sanitizedQuery)) {
            log.debug("Postgres search skipped for blank query");
            return List.of();
        }
        int safeLimit = PagingUtils.safeLimit(limit != null ? limit : 0, DEFAULT_LIMIT, 1, MAX_LIMIT);
        try {
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

            List<SearchResult> deduplicated = deduplicateSearchResults(rawResults);

            if (asyncBackfillEnabled && !deduplicated.isEmpty()) {
                enqueueBackfillForResults(deduplicated);
            }

            return deduplicated;
        } catch (DataAccessException ex) {
            log.debug("Postgres search failed for query '{}': {}", sanitizedQuery, ex.getMessage());
            return Collections.emptyList();
        }
    }

    public Optional<IsbnSearchResult> searchByIsbn(String isbnQuery) {
        if (jdbcTemplate == null) {
            return Optional.empty();
        }
        String sanitized = IsbnUtils.sanitize(isbnQuery);
        if (sanitized == null) {
            return Optional.empty();
        }
        try {
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
        } catch (DataAccessException ex) {
            log.debug("Postgres ISBN search failed for '{}': {}", sanitized, ex.getMessage());
            return Optional.empty();
        }
    }

    public List<AuthorResult> searchAuthors(String query, Integer limit) {
        if (jdbcTemplate == null) {
            return List.of();
        }
        String sanitizedQuery = SearchQueryUtils.normalize(query);
        if (SearchQueryUtils.isWildcard(sanitizedQuery)) {
            log.debug("Author search skipped for blank query");
            return List.of();
        }
        int safeLimit = PagingUtils.safeLimit(limit != null ? limit : 0, DEFAULT_LIMIT, 1, MAX_LIMIT);
        try {
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
        } catch (DataAccessException ex) {
            log.debug("Postgres author search failed for query '{}': {}", sanitizedQuery, ex.getMessage());
            return Collections.emptyList();
        }
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
            log.debug("Failed to refresh book_search_view: {}", ex.getMessage());
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
        
        try {
            for (SearchResult result : results) {
                // Look up external IDs for this book
                Map<String, String> externalIds = externalBookIdResolver.reverse(result.bookId());
                
                // Enqueue backfill for each external provider
                for (Map.Entry<String, String> entry : externalIds.entrySet()) {
                    String source = entry.getKey();
                    String externalId = entry.getValue();
                    
                    // Priority 3 = high priority (user just searched)
                    backfillCoordinator.enqueue(source, externalId, 3);
                }
            }
            
            log.debug("Enqueued backfill for {} search results", results.size());
        } catch (RuntimeException ex) {
            // Don't let backfill enqueue errors affect search results
            log.warn("Error enqueuing backfill for search results", ex);
        }
    }

    /**
     * Collapses multiple editions returned by the search function so each work
     * appears at most once in the final result list.
     * 
     * Two-pass deduplication:
     * 1. Cluster-based: Group books by work_cluster_id
     * 2. Title+Author-based: Group remaining unclustered books by normalized title+authors
     */
    private List<SearchResult> deduplicateSearchResults(List<SearchResult> rawResults) {
        if (rawResults == null || rawResults.isEmpty()) {
            return List.of();
        }

        List<UUID> editionIds = rawResults.stream()
            .map(SearchResult::bookId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        Map<UUID, ClusterMapping> clusterMappings = fetchClusterMappings(editionIds);
        Map<UUID, TitleAuthorKey> titleAuthorKeys = fetchTitleAuthorKeys(editionIds);

        // Pass 1: Deduplicate by cluster
        Map<UUID, SearchResult> byCluster = new LinkedHashMap<>();
        Map<UUID, UUID> clusterCanonical = new HashMap<>();
        for (SearchResult result : rawResults) {
            UUID editionId = result.bookId();
            if (editionId == null) {
                continue;
            }
            ClusterMapping mapping = clusterMappings.get(editionId);
            UUID clusterId = mapping != null ? mapping.clusterId() : result.clusterId();
            UUID canonicalId = null;
            if (mapping != null && mapping.primaryId() != null) {
                canonicalId = mapping.primaryId();
                if (clusterId != null) {
                    clusterCanonical.putIfAbsent(clusterId, canonicalId);
                    if (!mapping.hasExplicitPrimary() && mapping.editionCount() > 1 && log.isDebugEnabled()) {
                        log.debug("Cluster {} lacks explicit primary edition; using {} as canonical for search dedupe", clusterId, canonicalId);
                    }
                }
            }
            if (canonicalId == null && clusterId != null) {
                canonicalId = clusterCanonical.computeIfAbsent(clusterId, id -> editionId);
            }
            if (canonicalId == null) {
                canonicalId = editionId;
            }
            int editionCount = mapping != null ? mapping.editionCount() : result.editionCount();
            editionCount = Math.max(editionCount, 1);

            SearchResult existing = byCluster.get(canonicalId);
            if (existing == null) {
                byCluster.put(canonicalId, new SearchResult(canonicalId, result.relevanceScore(), result.matchType(), editionCount, clusterId));
            } else {
                double bestScore = Math.max(existing.relevanceScore(), result.relevanceScore());
                String matchType = bestScore == existing.relevanceScore() ? existing.matchType() : result.matchType();
                int combinedEditionCount = Math.max(existing.editionCount(), editionCount);
                UUID effectiveClusterId = existing.clusterId() != null ? existing.clusterId() : clusterId;
                byCluster.put(canonicalId, new SearchResult(canonicalId, bestScore, matchType, combinedEditionCount, effectiveClusterId));
            }
        }

        // Pass 2: Deduplicate by title+author (catches books in different clusters or unclustered)
        Map<String, SearchResult> byTitleAuthor = new LinkedHashMap<>();
        for (SearchResult result : byCluster.values()) {
            TitleAuthorKey key = titleAuthorKeys.get(result.bookId());
            if (key == null || key.normalizedKey() == null) {
                // No title/author data, keep as-is
                byTitleAuthor.put(result.bookId().toString(), result);
                continue;
            }
            
            SearchResult existing = byTitleAuthor.get(key.normalizedKey());
            if (existing == null) {
                byTitleAuthor.put(key.normalizedKey(), result);
            } else {
                // Merge: keep higher relevance, sum edition counts
                double bestScore = Math.max(existing.relevanceScore(), result.relevanceScore());
                String matchType = bestScore == existing.relevanceScore() ? existing.matchType() : result.matchType();
                int combinedEditionCount = existing.editionCount() + result.editionCount();
                UUID canonicalId = bestScore == existing.relevanceScore() ? existing.bookId() : result.bookId();
                UUID clusterId = existing.clusterId() != null ? existing.clusterId() : result.clusterId();
                byTitleAuthor.put(key.normalizedKey(), new SearchResult(canonicalId, bestScore, matchType, combinedEditionCount, clusterId));
            }
        }

        return List.copyOf(byTitleAuthor.values());
    }

    /**
     * Fetches normalized title+author keys for deduplication.
     */
    private Map<UUID, TitleAuthorKey> fetchTitleAuthorKeys(List<UUID> bookIds) {
        if (bookIds == null || bookIds.isEmpty() || jdbcTemplate == null) {
            return Map.of();
        }

        String placeholders = bookIds.stream()
            .map(id -> "?")
            .collect(Collectors.joining(","));

        String sql = """
            SELECT
                b.id AS book_id,
                normalize_title_for_clustering(b.title) AS normalized_title,
                get_normalized_authors(b.id) AS normalized_authors
            FROM books b
            WHERE b.id IN (%s)
            """.formatted(placeholders);

        try {
            return jdbcTemplate.query(sql, ps -> {
                for (int i = 0; i < bookIds.size(); i++) {
                    ps.setObject(i + 1, bookIds.get(i));
                }
            }, rs -> {
                Map<UUID, TitleAuthorKey> map = new HashMap<>();
                while (rs.next()) {
                    UUID bookId = (UUID) rs.getObject("book_id");
                    String title = rs.getString("normalized_title");
                    String authors = rs.getString("normalized_authors");
                    String key = (title != null ? title : "") + "::" + (authors != null ? authors : "");
                    map.put(bookId, new TitleAuthorKey(key));
                }
                return map;
            });
        } catch (DataAccessException ex) {
            log.debug("Failed to fetch title/author keys for {} books: {}", bookIds.size(), ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Looks up work-cluster metadata for a set of book identifiers.
     *
     * @param bookIds edition identifiers returned from the search function
     * @return mapping from edition id to cluster metadata (primary id + edition count)
     */
    private Map<UUID, ClusterMapping> fetchClusterMappings(List<UUID> bookIds) {
        if (bookIds == null || bookIds.isEmpty() || jdbcTemplate == null) {
            return Map.of();
        }

        String placeholders = bookIds.stream()
            .map(id -> "?")
            .collect(Collectors.joining(","));

        String sql = """
            SELECT
                wcm.book_id AS edition_id,
                COALESCE(primary_wcm.book_id, fallback_wcm.book_id, wcm.book_id) AS resolved_primary_book_id,
                wcm.cluster_id,
                COALESCE(wc.member_count, 1) AS edition_count,
                primary_wcm.book_id IS NOT NULL AS has_primary
            FROM work_cluster_members wcm
            LEFT JOIN work_cluster_members primary_wcm
              ON primary_wcm.cluster_id = wcm.cluster_id
             AND primary_wcm.is_primary = true
            LEFT JOIN LATERAL (
                SELECT wcm2.book_id
                FROM work_cluster_members wcm2
                WHERE wcm2.cluster_id = wcm.cluster_id
                ORDER BY wcm2.confidence DESC NULLS LAST, wcm2.book_id
                LIMIT 1
            ) fallback_wcm ON TRUE
            LEFT JOIN work_clusters wc ON wc.id = wcm.cluster_id
            WHERE wcm.book_id IN (%s)
            """.formatted(placeholders);

        try {
            return jdbcTemplate.query(
                sql,
                ps -> {
                    for (int i = 0; i < bookIds.size(); i++) {
                        ps.setObject(i + 1, bookIds.get(i));
                    }
                },
                rs -> {
                    Map<UUID, ClusterMapping> mappings = new HashMap<>();
                    while (rs.next()) {
                        UUID editionId = (UUID) rs.getObject("edition_id");
                        UUID primaryId = (UUID) rs.getObject("resolved_primary_book_id");
                        UUID clusterId = (UUID) rs.getObject("cluster_id");
                        int editionCount = rs.getInt("edition_count");
                        boolean hasPrimary = rs.getBoolean("has_primary");
                        if (editionId != null) {
                            mappings.put(editionId, new ClusterMapping(
                                primaryId != null ? primaryId : editionId,
                                clusterId,
                                editionCount > 0 ? editionCount : 1,
                                hasPrimary
                            ));
                        }
                    }
                    return mappings;
                }
            );
        } catch (DataAccessException ex) {
            log.debug("Failed to resolve work cluster mappings for {} search results: {}", bookIds.size(), ex.getMessage());
            return Map.of();
        }
    }

    private record ClusterMapping(UUID primaryId, UUID clusterId, int editionCount, boolean hasExplicitPrimary) {
    }

    private record TitleAuthorKey(String normalizedKey) {
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
