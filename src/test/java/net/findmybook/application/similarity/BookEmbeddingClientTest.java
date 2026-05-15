package net.findmybook.application.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.findmybook.support.llm.LlmGatewayTier;
import org.junit.jupiter.api.Test;

class BookEmbeddingClientTest {

    private static final int EMBEDDING_DIMENSION = BookSimilarityVectorFusion.EMBEDDING_DIMENSION;

    @Test
    void should_SplitEmbeddingInputs_When_EstimatedTokensExceedComfortLimit() {
        List<BookEmbeddingClient.EmbeddingInputPlan> inputPlans = BookEmbeddingClient.planEmbeddingInputs(
            List.of("abcdefghij", "short"),
            4
        );

        assertThat(inputPlans).hasSize(2);
        assertThat(inputPlans.getFirst().chunks())
            .extracting(BookEmbeddingClient.EmbeddingChunk::text)
            .containsExactly("abcd", "efgh", "ij");
        assertThat(inputPlans.getFirst().chunks())
            .extracting(BookEmbeddingClient.EmbeddingChunk::estimatedTokens)
            .containsExactly(4, 4, 2);
        assertThat(inputPlans.get(1).chunks())
            .extracting(BookEmbeddingClient.EmbeddingChunk::text)
            .containsExactly("shor", "t");
    }

    @Test
    void should_RejectBlankEmbeddingInput_When_PlanningRequest() {
        assertThatThrownBy(() -> BookEmbeddingClient.planEmbeddingInputs(List.of("valid", " "), 8_192))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("embedding input[1] is required");
    }

    @Test
    void should_IncludeChunkingContract_When_ModelIsUsedForEmbeddingCache() {
        BookEmbeddingClient client = clientWithRequester((batchTexts, ignoredOptions) -> List.of());

        assertThat(client.cacheModel()).isEqualTo("qwen/qwen3-embedding-4b:chunked_8192_v1");
    }

    @Test
    void should_ClampChunkSizeToComfortLimit_When_ConfiguredLimitIsTooHigh() {
        List<BookEmbeddingClient.EmbeddingInputPlan> inputPlans = BookEmbeddingClient.planEmbeddingInputs(
            List.of("a".repeat(8_193)),
            100_000
        );

        assertThat(inputPlans.getFirst().chunks())
            .extracting(BookEmbeddingClient.EmbeddingChunk::estimatedTokens)
            .containsExactly(8_192, 1);
    }

    @Test
    void should_SendChunkArraysAndCollapseInOriginalSectionOrder_When_SectionsSplitAcrossBatches() {
        List<List<String>> requestBatches = new ArrayList<>();
        BookEmbeddingClient client = clientWithRequester(
            4,
            2,
            (batchTexts, ignoredOptions) -> {
                requestBatches.add(List.copyOf(batchTexts));
                return batchTexts.stream()
                    .map(text -> embeddingWith((float) text.codePointAt(0), 1.0f))
                    .toList();
            }
        );

        List<List<Float>> sectionEmbeddings = client.embedSections(
            List.of("abcdefghij", "klmnop"),
            LlmGatewayTier.BACKGROUND_BATCH
        );

        assertThat(requestBatches)
            .containsExactly(
                List.of("abcd", "efgh"),
                List.of("ij", "klmn"),
                List.of("op")
            );
        assertThat(sectionEmbeddings).hasSize(2);
        assertThat(sectionEmbeddings.getFirst().getFirst()).isLessThan(sectionEmbeddings.get(1).getFirst());
    }

    @Test
    void should_FuseChunkEmbeddingsByEstimatedTokenWeight_When_InputWasSplit() {
        List<Float> fusedEmbedding = BookEmbeddingClient.fuseInputChunks(
            List.of(embeddingWith(1.0f, 0.0f), embeddingWith(0.0f, 1.0f)),
            List.of(
                new BookEmbeddingClient.EmbeddingChunk("a", 1),
                new BookEmbeddingClient.EmbeddingChunk("bbb", 3)
            )
        );

        assertThat(fusedEmbedding).hasSize(EMBEDDING_DIMENSION);
        assertThat(fusedEmbedding.get(0)).isCloseTo(0.31622776f, withinTolerance());
        assertThat(fusedEmbedding.get(1)).isCloseTo(0.9486833f, withinTolerance());
    }

    private static org.assertj.core.data.Offset<Float> withinTolerance() {
        return org.assertj.core.data.Offset.offset(0.00001f);
    }

    private static BookEmbeddingClient clientWithRequester(BookEmbeddingClient.BatchEmbeddingRequester requester) {
        return clientWithRequester(8_192, 32, requester);
    }

    private static BookEmbeddingClient clientWithRequester(int configuredInputTokenLimit,
                                                           int configuredRequestInputBatchSize,
                                                           BookEmbeddingClient.BatchEmbeddingRequester requester) {
        return new BookEmbeddingClient(
            "qwen/qwen3-embedding-4b",
            1,
            1,
            configuredInputTokenLimit,
            configuredRequestInputBatchSize,
            Map.of(LlmGatewayTier.BACKGROUND_BATCH, requester)
        );
    }

    private static List<Float> embeddingWith(float firstComponent, float secondComponent) {
        List<Float> embedding = new ArrayList<>(EMBEDDING_DIMENSION);
        embedding.add(firstComponent);
        embedding.add(secondComponent);
        while (embedding.size() < EMBEDDING_DIMENSION) {
            embedding.add(0.0f);
        }
        return List.copyOf(embedding);
    }
}
