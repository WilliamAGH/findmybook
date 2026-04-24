package net.findmybook.domain.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookSimilarityFusionPolicyTest {

    @Test
    void should_RenormalizeWeights_When_OptionalSectionsAreMissing() {
        BookSimilarityFusionPolicy policy = policy();

        var weights = policy.normalizedWeightsFor(List.of(
            BookSimilaritySectionKey.IDENTITY,
            BookSimilaritySectionKey.DESCRIPTION
        ));

        assertThat(weights).containsEntry(BookSimilaritySectionKey.IDENTITY, 0.4d);
        assertThat(weights).containsEntry(BookSimilaritySectionKey.DESCRIPTION, 0.6d);
    }

    @Test
    void should_RejectProfile_When_WeightsDoNotSumToOne() {
        EnumMap<BookSimilaritySectionKey, Double> weights = new EnumMap<>(BookSimilaritySectionKey.class);
        weights.put(BookSimilaritySectionKey.IDENTITY, 0.7d);
        weights.put(BookSimilaritySectionKey.DESCRIPTION, 0.7d);

        assertThatThrownBy(() -> new BookSimilarityFusionPolicy(
            "test",
            List.of(BookSimilaritySectionKey.IDENTITY, BookSimilaritySectionKey.DESCRIPTION),
            List.of(new BookSimilarityFusionProfile("test", "Test profile", weights)),
            "profile-hash"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must sum to 1.0");
    }

    private static BookSimilarityFusionPolicy policy() {
        EnumMap<BookSimilaritySectionKey, Double> weights = new EnumMap<>(BookSimilaritySectionKey.class);
        weights.put(BookSimilaritySectionKey.IDENTITY, 0.4d);
        weights.put(BookSimilaritySectionKey.DESCRIPTION, 0.6d);
        return new BookSimilarityFusionPolicy(
            "test",
            List.of(BookSimilaritySectionKey.IDENTITY, BookSimilaritySectionKey.DESCRIPTION),
            List.of(new BookSimilarityFusionProfile("test", "Test profile", weights)),
            "profile-hash"
        );
    }
}
