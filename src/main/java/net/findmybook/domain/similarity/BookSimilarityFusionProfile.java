package net.findmybook.domain.similarity;

import java.util.EnumMap;
import java.util.Map;

/**
 * Named section-weight profile for book similarity embeddings.
 *
 * @param id stable profile identifier encoded into model versions
 * @param description operator-facing explanation of the profile intent
 * @param weights canonical section weights whose values must sum to one
 */
public record BookSimilarityFusionProfile(
    String id,
    String description,
    EnumMap<BookSimilaritySectionKey, Double> weights
) {

    /**
     * Creates an immutable copy of the supplied weight map.
     */
    public BookSimilarityFusionProfile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Book similarity profile id is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Book similarity profile description is required");
        }
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("Book similarity profile weights are required");
        }
        weights = new EnumMap<>(weights);
        weights.forEach((sectionKey, weight) -> {
            if (sectionKey == null) {
                throw new IllegalArgumentException("Book similarity profile section key is required");
            }
            if (weight == null || weight < 0.0d) {
                throw new IllegalArgumentException("Book similarity profile weights must be non-negative");
            }
        });
    }

    /**
     * Returns the configured weight for a section.
     *
     * @param sectionKey section to inspect
     * @return configured weight, or zero when absent
     */
    public double weightFor(BookSimilaritySectionKey sectionKey) {
        return weights.getOrDefault(sectionKey, 0.0d);
    }

    /**
     * Exposes the profile weights as a read-only map.
     *
     * @return canonical section weights
     */
    @Override
    public EnumMap<BookSimilaritySectionKey, Double> weights() {
        return new EnumMap<>(weights);
    }

    /**
     * Returns profile weights as stable key/value pairs for diagnostics.
     *
     * @return section-key string to weight map
     */
    public Map<String, Double> diagnosticWeights() {
        Map<String, Double> diagnosticWeights = new java.util.LinkedHashMap<>();
        weights.forEach((sectionKey, weight) -> diagnosticWeights.put(sectionKey.key(), weight));
        return Map.copyOf(diagnosticWeights);
    }
}
