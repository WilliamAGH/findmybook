package net.findmybook.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Collapses multiple editions returned by the search function so each work
 * appears at most once in the final result list.
 *
 * <p>Two-pass deduplication:
 * <ol>
 *   <li>Cluster-based: group books by {@code work_cluster_id}</li>
 *   <li>Title+Author-based: group remaining unclustered books by normalized title+authors</li>
 * </ol>
 *
 * <p>Extracted from {@link BookSearchService} to keep it under the LOC ceiling.</p>
 */
final class SearchResultDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(SearchResultDeduplicator.class);

    private final JdbcTemplate jdbcTemplate;

    SearchResultDeduplicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<BookSearchService.SearchResult> deduplicate(List<BookSearchService.SearchResult> rawResults) {
        if (rawResults == null || rawResults.isEmpty()) {
            return List.of();
        }

        List<UUID> editionIds = rawResults.stream()
            .map(BookSearchService.SearchResult::bookId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        Map<UUID, ClusterMapping> clusterMappings = fetchClusterMappings(editionIds);
        Map<UUID, TitleAuthorKey> titleAuthorKeys = fetchTitleAuthorKeys(editionIds);

        Map<UUID, BookSearchService.SearchResult> byCluster = deduplicateByCluster(rawResults, clusterMappings);
        return deduplicateByTitleAuthor(byCluster, titleAuthorKeys);
    }

    private Map<UUID, BookSearchService.SearchResult> deduplicateByCluster(
            List<BookSearchService.SearchResult> rawResults,
            Map<UUID, ClusterMapping> clusterMappings) {

        Map<UUID, BookSearchService.SearchResult> byCluster = new LinkedHashMap<>();
        Map<UUID, UUID> clusterCanonical = new HashMap<>();

        for (BookSearchService.SearchResult result : rawResults) {
            UUID editionId = result.bookId();
            if (editionId == null) {
                continue;
            }
            ClusterMapping mapping = clusterMappings.get(editionId);
            UUID clusterId = mapping != null ? mapping.clusterId() : result.clusterId();
            UUID canonicalId = resolveCanonicalId(editionId, mapping, clusterId, clusterCanonical);
            int editionCount = Math.max(mapping != null ? mapping.editionCount() : result.editionCount(), 1);

            BookSearchService.SearchResult existing = byCluster.get(canonicalId);
            if (existing == null) {
                byCluster.put(canonicalId, new BookSearchService.SearchResult(
                        canonicalId, result.relevanceScore(), result.matchType(), editionCount, clusterId));
            } else {
                byCluster.put(canonicalId, mergeResults(existing, result, editionCount, clusterId));
            }
        }
        return byCluster;
    }

    private UUID resolveCanonicalId(UUID editionId, ClusterMapping mapping, UUID clusterId,
                                    Map<UUID, UUID> clusterCanonical) {
        UUID canonicalId = null;
        if (mapping != null && mapping.primaryId() != null) {
            canonicalId = mapping.primaryId();
            if (clusterId != null) {
                clusterCanonical.putIfAbsent(clusterId, canonicalId);
                if (!mapping.hasExplicitPrimary() && mapping.editionCount() > 1 && log.isDebugEnabled()) {
                    log.debug("Cluster {} lacks explicit primary edition; using {} as canonical for search dedupe",
                            clusterId, canonicalId);
                }
            }
        }
        if (canonicalId == null && clusterId != null) {
            canonicalId = clusterCanonical.computeIfAbsent(clusterId, id -> editionId);
        }
        return canonicalId != null ? canonicalId : editionId;
    }

    private List<BookSearchService.SearchResult> deduplicateByTitleAuthor(
            Map<UUID, BookSearchService.SearchResult> byCluster,
            Map<UUID, TitleAuthorKey> titleAuthorKeys) {

        Map<String, BookSearchService.SearchResult> byTitleAuthor = new LinkedHashMap<>();
        for (BookSearchService.SearchResult result : byCluster.values()) {
            TitleAuthorKey key = titleAuthorKeys.get(result.bookId());
            if (key == null || key.normalizedKey() == null) {
                byTitleAuthor.put(result.bookId().toString(), result);
                continue;
            }

            BookSearchService.SearchResult existing = byTitleAuthor.get(key.normalizedKey());
            if (existing == null) {
                byTitleAuthor.put(key.normalizedKey(), result);
            } else {
                double bestScore = Math.max(existing.relevanceScore(), result.relevanceScore());
                String matchType = bestScore == existing.relevanceScore() ? existing.matchType() : result.matchType();
                int combinedEditionCount = existing.editionCount() + result.editionCount();
                UUID canonicalId = bestScore == existing.relevanceScore() ? existing.bookId() : result.bookId();
                UUID clusterId = existing.clusterId() != null ? existing.clusterId() : result.clusterId();
                byTitleAuthor.put(key.normalizedKey(),
                        new BookSearchService.SearchResult(canonicalId, bestScore, matchType, combinedEditionCount, clusterId));
            }
        }
        return List.copyOf(byTitleAuthor.values());
    }

    private static BookSearchService.SearchResult mergeResults(
            BookSearchService.SearchResult existing,
            BookSearchService.SearchResult incoming,
            int editionCount,
            UUID clusterId) {
        double bestScore = Math.max(existing.relevanceScore(), incoming.relevanceScore());
        String matchType = bestScore == existing.relevanceScore() ? existing.matchType() : incoming.matchType();
        int combinedEditionCount = Math.max(existing.editionCount(), editionCount);
        UUID effectiveClusterId = existing.clusterId() != null ? existing.clusterId() : clusterId;
        return new BookSearchService.SearchResult(existing.bookId(), bestScore, matchType, combinedEditionCount, effectiveClusterId);
    }

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
    }

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
    }

    private record ClusterMapping(UUID primaryId, UUID clusterId, int editionCount, boolean hasExplicitPrimary) {
    }

    private record TitleAuthorKey(String normalizedKey) {
    }
}
