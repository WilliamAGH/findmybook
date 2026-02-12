package net.findmybook.repository;

import net.findmybook.dto.BookCard;
import net.findmybook.dto.RecommendationCard;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Package-private helper that owns recommendation query logic: cluster resolution,
 * fetch, and source-prioritized merge/deduplication.
 *
 * <p>Extracted from {@link BookQueryRepository} to keep the main repository focused
 * on single-concern query methods while this class encapsulates the multi-step
 * recommendation assembly pipeline.</p>
 */
final class BookRecommendationQuerySupport {

    private static final Logger log = LoggerFactory.getLogger(BookRecommendationQuerySupport.class);
    private static final int MAX_PER_SOURCE_PRIORITY = 3;
    private static final int CANDIDATE_BUFFER_FACTOR = 3;

    private final JdbcTemplate jdbcTemplate;
    private final BookQueryResultSetSupport resultSetSupport;
    private final BookQueryRowMapperFactory rowMapperFactory;

    BookRecommendationQuerySupport(JdbcTemplate jdbcTemplate,
                                   BookQueryResultSetSupport resultSetSupport,
                                   BookQueryRowMapperFactory rowMapperFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.resultSetSupport = resultSetSupport;
        this.rowMapperFactory = rowMapperFactory;
    }

    /** Database values for the {@code book_recommendations.source} column. */
    enum RecommendationSource {
        RECOMMENDATION_PIPELINE,
        SAME_AUTHOR,
        SAME_CATEGORY,
        OTHER;

        static RecommendationSource fromDatabase(String value) {
            if (value == null) {
                return OTHER;
            }
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "RECOMMENDATION_PIPELINE" -> RECOMMENDATION_PIPELINE;
                case "SAME_AUTHOR" -> SAME_AUTHOR;
                case "SAME_CATEGORY" -> SAME_CATEGORY;
                default -> OTHER;
            };
        }
    }

    /**
     * Fetch recommendation cards for a canonical book from the persisted recommendation table.
     * Resolves cluster members to broaden the source search, then merges by source priority.
     */
    List<RecommendationCard> fetchRecommendationCards(UUID sourceBookId, int limit) {
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

        int fetchLimit = Math.max(limit, Math.min(limit * candidateSourceIds.size(), limit * CANDIDATE_BUFFER_FACTOR));

        // Keep recommendations available even when refresh jobs lag behind expiry.
        // Active rows are ranked first; stale rows remain eligible as a deterministic fallback.
        String sql = """
            SELECT bc.*, br.score, br.reason, br.source
            FROM book_recommendations br
            JOIN LATERAL get_book_cards(ARRAY[br.recommended_book_id]) bc ON TRUE
            WHERE br.source_book_id IN (%s)
            ORDER BY CASE WHEN br.source_book_id = ? THEN 0 ELSE 1 END,
                     CASE WHEN br.expires_at IS NULL OR br.expires_at > NOW() THEN 0 ELSE 1 END,
                     br.score DESC NULLS LAST,
                     br.generated_at DESC
            LIMIT ?
            """.formatted(placeholders);

        var mapper = rowMapperFactory.bookCardRowMapper();
        List<RecommendationCard> raw = jdbcTemplate.query(sql, ps -> {
            int index = 1;
            for (UUID id : candidateSourceIds) {
                ps.setObject(index++, id);
            }
            ps.setObject(index++, sourceBookId);
            ps.setInt(index, fetchLimit);
        }, (rs, rowNum) -> {
            BookCard card = mapper.mapRow(rs, rowNum);
            Double score = resultSetSupport.getDoubleOrNull(rs, "score");
            String reason = rs.getString("reason");
            String source = rs.getString("source");
            return new RecommendationCard(card, score, reason, source);
        });
        return mergeRecommendations(raw, limit);
    }

    /**
     * Checks whether any non-expired recommendation rows exist for the source book cluster.
     *
     * @param sourceBookId canonical source book identifier
     * @return true when at least one active recommendation row exists
     */
    boolean hasActiveRecommendations(UUID sourceBookId) {
        if (sourceBookId == null) {
            return false;
        }

        List<UUID> resolvedSourceIds = resolveClusterSourceIds(sourceBookId);
        List<UUID> candidateSourceIds = resolvedSourceIds.isEmpty() ? List.of(sourceBookId) : resolvedSourceIds;
        String placeholders = candidateSourceIds.stream()
            .map(id -> "?")
            .collect(Collectors.joining(", "));

        String sql = """
            SELECT 1
            FROM book_recommendations br
            WHERE br.source_book_id IN (%s)
              AND (br.expires_at IS NULL OR br.expires_at > NOW())
            LIMIT 1
            """.formatted(placeholders);

        try {
            List<Integer> rows = jdbcTemplate.query(sql, ps -> {
                int index = 1;
                for (UUID id : candidateSourceIds) {
                    ps.setObject(index++, id);
                }
            }, (rs, rowNum) -> rs.getInt(1));
            return !rows.isEmpty();
        } catch (DataAccessException ex) {
            log.error("Failed to check active recommendation rows for {}: {}", sourceBookId, ex.getMessage(), ex);
            throw new IllegalStateException("Active recommendation check failed for " + sourceBookId, ex);
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
            log.error("Failed to resolve cluster member IDs for {}: {}", sourceBookId, ex.getMessage(), ex);
            throw new IllegalStateException("Cluster source ID resolution failed for " + sourceBookId, ex);
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
            switch (RecommendationSource.fromDatabase(card.source())) {
                case RECOMMENDATION_PIPELINE -> pipeline.add(card);
                case SAME_AUTHOR -> sameAuthor.add(card);
                case SAME_CATEGORY -> sameCategory.add(card);
                case OTHER -> others.add(card);
            }
        }

        var acc = new MergeAccumulator(limit);
        acc.append(pipeline, pipeline.size());
        acc.append(sameAuthor, Math.min(MAX_PER_SOURCE_PRIORITY, acc.remaining));
        acc.append(sameCategory, Math.min(MAX_PER_SOURCE_PRIORITY, acc.remaining));
        acc.append(others, acc.remaining);
        return acc.toList();
    }

    /** Encapsulates the mutable accumulator state for source-prioritized recommendation merging. */
    private static final class MergeAccumulator {
        private final List<RecommendationCard> result;
        private final Set<String> seen;
        int remaining;

        MergeAccumulator(int capacity) {
            this.result = new ArrayList<>(capacity);
            this.seen = new LinkedHashSet<>();
            this.remaining = capacity;
        }

        void append(List<RecommendationCard> source, int maxFromSource) {
            if (remaining <= 0 || source.isEmpty() || maxFromSource <= 0) {
                return;
            }
            int added = 0;
            for (RecommendationCard card : source) {
                if (remaining <= 0 || added >= maxFromSource) {
                    break;
                }
                if (card == null || card.card() == null || !StringUtils.hasText(card.card().id())) {
                    continue;
                }
                if (seen.add(card.card().id())) {
                    result.add(card);
                    remaining--;
                    added++;
                }
            }
        }

        List<RecommendationCard> toList() {
            return result;
        }
    }
}
