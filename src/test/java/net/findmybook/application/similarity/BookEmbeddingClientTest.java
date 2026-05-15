package net.findmybook.application.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.findmybook.adapters.persistence.BookEmbeddingSectionRepository;
import net.findmybook.adapters.persistence.BookSimilarityEmbeddingRepository;
import net.findmybook.boot.BookSimilarityEmbeddingProperties;
import net.findmybook.domain.similarity.BookSimilarityFusionPolicy;
import net.findmybook.domain.similarity.BookSimilarityFusionProfile;
import net.findmybook.domain.similarity.BookSimilaritySectionKey;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.llm.LlmGatewayTier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
    void should_IncludeEffectiveChunkingContract_When_ConfiguredLimitIsNonDefault() {
        BookEmbeddingClient client = clientWithRequester(4_096, 32, (batchTexts, ignoredOptions) -> List.of());

        assertThat(client.cacheModel()).isEqualTo("qwen/qwen3-embedding-4b:chunked_4096_v1");
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

    @Test
    void should_NormalizeChunkEmbedding_When_InputHasSingleChunk() {
        List<Float> fusedEmbedding = BookEmbeddingClient.fuseInputChunks(
            List.of(embeddingWith(3.0f, 4.0f)),
            List.of(new BookEmbeddingClient.EmbeddingChunk("section text", 12))
        );

        assertThat(fusedEmbedding).hasSize(EMBEDDING_DIMENSION);
        assertThat(fusedEmbedding.get(0)).isCloseTo(0.6f, withinTolerance());
        assertThat(fusedEmbedding.get(1)).isCloseTo(0.8f, withinTolerance());
    }

    @Test
    void should_ReadPreviousVectorContract_When_CurrentContractCannotFillLimit() {
        UUID sourceBookId = UUID.fromString("019da3e5-3838-703e-9112-bad4a489239e");
        UUID currentMatchId = UUID.fromString("019c3b68-3ee9-7ef0-917c-c37b663d97c1");
        UUID legacyMatchId = UUID.fromString("019c175b-8cf5-7455-90af-6787b965e5a2");
        String currentModelVersion =
            "qwen/qwen3-embedding-4b:chunked_8192_v1:source_chars_15000_v1:test:section_fusion";
        String previousModelVersion = "qwen/qwen3-embedding-4b:chunked_8192_v1:test:section_fusion";
        BookSimilarityEmbeddingRepository repository = mock(BookSimilarityEmbeddingRepository.class);
        when(repository.findNearestBooks(sourceBookId, currentModelVersion, "profile-hash", 2))
            .thenReturn(List.of(new BookSimilarityEmbeddingRepository.NearestBookRow(currentMatchId, 0.91d)));
        when(repository.findNearestBooks(sourceBookId, previousModelVersion, "profile-hash", 2))
            .thenReturn(List.of(new BookSimilarityEmbeddingRepository.NearestBookRow(legacyMatchId, 0.89d)));
        BookSimilarityEmbeddingService service = similarityService(repository);

        List<BookSimilarityEmbeddingService.SimilarBookMatch> matches = service.findNearestBooks(sourceBookId, 2);

        assertThat(matches)
            .extracting(BookSimilarityEmbeddingService.SimilarBookMatch::bookId)
            .containsExactly(legacyMatchId);
    }

    @Test
    void should_QueryRefreshCandidatesWithSourceTextContract_When_SchedulerRuns() {
        String modelVersion =
            "qwen/qwen3-embedding-4b:chunked_8192_v1:source_chars_15000_v1:test:section_fusion";
        BookSimilarityEmbeddingRepository repository = mock(BookSimilarityEmbeddingRepository.class);
        BookAiContentRequestQueue requestQueue = mock(BookAiContentRequestQueue.class);
        when(requestQueue.snapshot()).thenReturn(new BookAiContentRequestQueue.QueueSnapshot(0, 0, 1));
        when(repository.findRefreshCandidates(modelVersion, "profile-hash", 25)).thenReturn(List.of());
        BookSimilarityEmbeddingService service = similarityService(
            repository,
            requestQueue,
            new BookSimilarityEmbeddingProperties()
        );

        int enqueued = service.enqueueRefreshCandidates(25, 10, 100);

        assertThat(enqueued).isZero();
        verify(repository).findRefreshCandidates(modelVersion, "profile-hash", 25);
    }

    @Test
    void should_CheckDemandFreshnessWithSourceTextContract_When_DemandRefreshIsRequested() {
        UUID bookId = UUID.fromString("019da3e5-3838-703e-9112-bad4a489239e");
        String modelVersion = "qwen/qwen3-embedding-4b:chunked_8192_v1:source_full_v1:test:section_fusion";
        BookSimilarityEmbeddingRepository repository = mock(BookSimilarityEmbeddingRepository.class);
        BookSimilarityEmbeddingProperties properties = new BookSimilarityEmbeddingProperties();
        properties.setMaxSectionTextChars(0);
        when(repository.isVectorFresh(bookId, modelVersion, "profile-hash")).thenReturn(true);
        BookSimilarityEmbeddingService service = similarityService(
            repository,
            mock(BookAiContentRequestQueue.class),
            properties
        );

        boolean enqueued = service.enqueueDemandRefresh(bookId);

        assertThat(enqueued).isFalse();
        verify(repository).isVectorFresh(bookId, modelVersion, "profile-hash");
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

    private static BookSimilarityEmbeddingService similarityService(BookSimilarityEmbeddingRepository repository) {
        return similarityService(repository, mock(BookAiContentRequestQueue.class), new BookSimilarityEmbeddingProperties());
    }

    private static BookSimilarityEmbeddingService similarityService(BookSimilarityEmbeddingRepository repository,
                                                                    BookAiContentRequestQueue requestQueue,
                                                                    BookSimilarityEmbeddingProperties properties) {
        return new BookSimilarityEmbeddingService(
            repository,
            mock(BookEmbeddingSectionRepository.class),
            clientWithRequester((batchTexts, ignoredOptions) -> List.of()),
            similarityPolicy(),
            mock(BookSimilarityVectorFusion.class),
            requestQueue,
            mock(ObjectMapper.class),
            properties
        );
    }

    private static BookSimilarityFusionPolicy similarityPolicy() {
        EnumMap<BookSimilaritySectionKey, Double> weights = new EnumMap<>(BookSimilaritySectionKey.class);
        weights.put(BookSimilaritySectionKey.IDENTITY, 1.0d);
        return new BookSimilarityFusionPolicy(
            "test",
            List.of(BookSimilaritySectionKey.IDENTITY),
            List.of(new BookSimilarityFusionProfile("test", "Test profile", weights)),
            "profile-hash"
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
