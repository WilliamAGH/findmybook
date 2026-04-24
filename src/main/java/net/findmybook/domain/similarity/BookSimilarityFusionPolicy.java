package net.findmybook.domain.similarity;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validated section-fusion policy for book similarity embeddings.
 *
 * @param activeProfileId selected profile used by runtime vector generation
 * @param sectionOrder stable section traversal order
 * @param profiles available weight profiles
 * @param profileHash SHA-256 hash of the policy resource
 */
public record BookSimilarityFusionPolicy(
    String activeProfileId,
    List<BookSimilaritySectionKey> sectionOrder,
    List<BookSimilarityFusionProfile> profiles,
    String profileHash
) {
    public static final String SECTION_FUSION_REGIME = "section_fusion";

    /**
     * Validates profile references and active profile selection.
     */
    public BookSimilarityFusionPolicy {
        if (activeProfileId == null || activeProfileId.isBlank()) {
            throw new IllegalArgumentException("activeProfileId is required");
        }
        if (sectionOrder == null || sectionOrder.isEmpty()) {
            throw new IllegalArgumentException("sectionOrder is required");
        }
        if (sectionOrder.stream().distinct().count() != sectionOrder.size()) {
            throw new IllegalArgumentException("sectionOrder must be unique");
        }
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("book similarity profiles are required");
        }
        if (profileHash == null || profileHash.isBlank()) {
            throw new IllegalArgumentException("profileHash is required");
        }
        sectionOrder = List.copyOf(sectionOrder);
        profiles = List.copyOf(profiles);
        Map<String, BookSimilarityFusionProfile> profilesById = profilesById(profiles);
        if (!profilesById.containsKey(activeProfileId)) {
            throw new IllegalArgumentException("activeProfileId missing from book similarity profiles: " + activeProfileId);
        }
        for (BookSimilarityFusionProfile profile : profiles) {
            validateProfile(profile, sectionOrder);
        }
    }

    /**
     * Returns the active fusion profile.
     *
     * @return active profile
     */
    public BookSimilarityFusionProfile activeProfile() {
        return profilesById(profiles).get(activeProfileId);
    }

    /**
     * Returns the persisted model version for the active section-fusion profile.
     *
     * @param baseModel configured embeddings model
     * @return model/profile/regime version string
     */
    public String modelVersion(String baseModel) {
        if (baseModel == null || baseModel.isBlank()) {
            throw new IllegalArgumentException("baseModel is required");
        }
        return baseModel.trim() + ":" + activeProfileId + ":" + SECTION_FUSION_REGIME;
    }

    /**
     * Renormalizes active-profile weights across sections that have source text.
     *
     * @param presentSections sections available for the current book
     * @return ordered normalized weights
     */
    public LinkedHashMap<BookSimilaritySectionKey, Double> normalizedWeightsFor(
        Collection<BookSimilaritySectionKey> presentSections
    ) {
        if (presentSections == null || presentSections.isEmpty()) {
            throw new IllegalArgumentException("book similarity fusion requires at least one present section");
        }
        EnumMap<BookSimilaritySectionKey, Boolean> present = new EnumMap<>(BookSimilaritySectionKey.class);
        presentSections.forEach(sectionKey -> present.put(sectionKey, Boolean.TRUE));
        BookSimilarityFusionProfile activeProfile = activeProfile();
        LinkedHashMap<BookSimilaritySectionKey, Double> weightedSections = new LinkedHashMap<>();
        double totalWeight = 0.0d;
        for (BookSimilaritySectionKey sectionKey : sectionOrder) {
            double weight = activeProfile.weightFor(sectionKey);
            if (present.containsKey(sectionKey) && weight > 0.0d) {
                weightedSections.put(sectionKey, weight);
                totalWeight += weight;
            }
        }
        if (totalWeight <= 0.0d) {
            throw new IllegalArgumentException("book similarity fusion requires at least one weighted section");
        }
        LinkedHashMap<BookSimilaritySectionKey, Double> normalizedWeights = new LinkedHashMap<>();
        for (Map.Entry<BookSimilaritySectionKey, Double> entry : weightedSections.entrySet()) {
            normalizedWeights.put(entry.getKey(), entry.getValue() / totalWeight);
        }
        return normalizedWeights;
    }

    private static Map<String, BookSimilarityFusionProfile> profilesById(List<BookSimilarityFusionProfile> profiles) {
        return profiles.stream().collect(Collectors.toMap(
            BookSimilarityFusionProfile::id,
            Function.identity(),
            (left, right) -> {
                throw new IllegalArgumentException("Duplicate book similarity profile id: " + left.id());
            }
        ));
    }

    private static void validateProfile(BookSimilarityFusionProfile profile, List<BookSimilaritySectionKey> sectionOrder) {
        if (!sectionOrder.containsAll(profile.weights().keySet())) {
            throw new IllegalArgumentException("Book similarity profile references unknown sections: " + profile.id());
        }
        double totalWeight = profile.weights().values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(totalWeight - 1.0d) > 0.000001d) {
            throw new IllegalArgumentException("Book similarity profile weights must sum to 1.0: " + profile.id());
        }
    }
}
