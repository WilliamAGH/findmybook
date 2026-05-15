package net.findmybook.application.similarity;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.boot.BookSimilarityEmbeddingProperties;
import net.findmybook.boot.OpenAiProperties;
import net.findmybook.support.llm.LlmGatewayTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible embeddings client for book similarity section vectors.
 *
 * <p>The LLM gateway assigns concurrency and queue budgets per {@code X-Tier} header, so one
 * underlying SDK client is pre-configured per {@link LlmGatewayTier}. Callers route to the
 * appropriate tier explicitly — there is no implicit default — so live-render paths stay on the
 * reserved {@code production-z} queue and async/background paths stay on the deep {@code batch}
 * queue.</p>
 */
@Component
public class BookEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(BookEmbeddingClient.class);
    private static final int REQUEST_TOKEN_BUDGET = 262_144;

    private final Map<LlmGatewayTier, BatchEmbeddingRequester> requestersByTier;
    private final boolean available;
    private final String model;
    private final long requestTimeoutSeconds;
    private final long readTimeoutSeconds;
    private final int inputTokenComfortLimit;
    private final int requestInputBatchSize;
    private final String embeddingInputContract;

    /**
     * Creates one SDK client per tier so the {@code X-Tier} header is fixed per connection.
     *
     * @param openAiProperties provider and model settings
     * @param embeddingProperties book similarity embedding request limits
     */
    @Autowired
    public BookEmbeddingClient(OpenAiProperties openAiProperties, BookSimilarityEmbeddingProperties embeddingProperties) {
        this.model = openAiProperties.embeddingsModel();
        this.requestTimeoutSeconds = openAiProperties.requestTimeoutSeconds();
        this.readTimeoutSeconds = openAiProperties.readTimeoutSeconds();
        this.inputTokenComfortLimit = embeddingProperties.inputTokenComfortLimit();
        this.requestInputBatchSize = Math.min(
            embeddingProperties.requestInputBatchSize(),
            Math.max(1, REQUEST_TOKEN_BUDGET / this.inputTokenComfortLimit)
        );
        this.embeddingInputContract = embeddingProperties.embeddingInputContract();
        if (openAiProperties.isEmbeddingsConfigured()) {
            EnumMap<LlmGatewayTier, BatchEmbeddingRequester> requesters = new EnumMap<>(LlmGatewayTier.class);
            for (LlmGatewayTier tier : LlmGatewayTier.values()) {
                OpenAIClient tieredClient = OpenAIOkHttpClient.builder()
                    .apiKey(openAiProperties.apiKey())
                    .baseUrl(openAiProperties.baseUrl())
                    .maxRetries(0)
                    .putHeader(LlmGatewayTier.HEADER_NAME, tier.headerValue())
                    .build();
                requesters.put(tier, createOpenAiRequester(tieredClient));
            }
            this.requestersByTier = Map.copyOf(requesters);
            this.available = true;
            log.info(
                "Book embedding client configured (model={}, baseUrl={}, tiers={})",
                model,
                openAiProperties.baseUrl(),
                requesters.keySet()
            );
            return;
        }
        this.requestersByTier = Map.of();
        this.available = false;
        log.warn("Book embedding client is disabled: missing OPENAI_API_KEY, OPENAI_BASE_URL, or OPENAI_EMBEDDINGS_MODEL");
    }

    BookEmbeddingClient(String model,
                        long requestTimeoutSeconds,
                        long readTimeoutSeconds,
                        int configuredInputTokenLimit,
                        int configuredRequestInputBatchSize,
                        Map<LlmGatewayTier, BatchEmbeddingRequester> requestersByTier) {
        this.model = model;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.inputTokenComfortLimit = BookSimilarityEmbeddingProperties.normalizedInputTokenComfortLimit(configuredInputTokenLimit);
        this.requestInputBatchSize = Math.min(
            Math.max(1, configuredRequestInputBatchSize),
            Math.max(1, REQUEST_TOKEN_BUDGET / this.inputTokenComfortLimit)
        );
        this.embeddingInputContract = BookSimilarityEmbeddingProperties.embeddingInputContract(configuredInputTokenLimit);
        this.requestersByTier = Map.copyOf(requestersByTier);
        this.available = !this.requestersByTier.isEmpty();
    }

    /**
     * Returns whether embedding requests are configured.
     *
     * @return true when outbound calls can be made
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns the configured embeddings model.
     *
     * @return model identifier
     */
    public String model() {
        return model;
    }

    /**
     * Returns the cache identity for the active embedding input contract.
     *
     * @return model key that invalidates section caches when client chunking changes
     */
    public String cacheModel() {
        if (model == null || model.isBlank()) {
            return "";
        }
        return model.trim() + ":" + embeddingInputContract;
    }

    /**
     * Embeds a batch of section texts at the supplied gateway tier.
     *
     * @param sectionTexts section texts in desired output order
     * @param tier gateway priority tier; must be non-null
     * @return embeddings in input order
     */
    public List<List<Float>> embedSections(List<String> sectionTexts, LlmGatewayTier tier) {
        ensureAvailable();
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        if (sectionTexts == null || sectionTexts.isEmpty()) {
            throw new IllegalArgumentException("inputs are required");
        }
        BatchEmbeddingRequester requester = requestersByTier.get(tier);
        if (requester == null) {
            throw new IllegalStateException("No embedding client configured for tier " + tier);
        }
        List<EmbeddingInputPlan> inputPlans = planEmbeddingInputs(sectionTexts, inputTokenComfortLimit);
        List<EmbeddingChunk> requestChunks = flattenRequestChunks(inputPlans);
        RequestOptions options = requestOptions();
        List<List<Float>> chunkEmbeddings = new ArrayList<>(requestChunks.size());
        int fromIndex = 0;
        while (fromIndex < requestChunks.size()) {
            int toIndex = Math.min(fromIndex + requestInputBatchSize, requestChunks.size());
            List<EmbeddingChunk> batchChunks = requestChunks.subList(fromIndex, toIndex);
            chunkEmbeddings.addAll(embedChunkBatch(batchChunks, requester, options, tier));
            fromIndex = toIndex;
        }
        return collapseChunkEmbeddings(inputPlans, chunkEmbeddings);
    }

    private List<List<Float>> embedChunkBatch(List<EmbeddingChunk> batchChunks,
                                              BatchEmbeddingRequester requester,
                                              RequestOptions options,
                                              LlmGatewayTier tier) {
        List<String> batchTexts = batchChunks.stream()
            .map(EmbeddingChunk::text)
            .toList();
        try {
            List<List<Float>> embeddings = requester.embed(batchTexts, options);
            validateEmbeddingResponse(batchTexts.size(), embeddings);
            return embeddings;
        } catch (OpenAIException openAiException) {
            throw new BookEmbeddingApiException(
                "Embedding API call failed for model %s at tier %s: %s".formatted(
                    model,
                    tier.headerValue(),
                    BookAiGenerationException.describeApiError(openAiException)
                ),
                openAiException
            );
        }
    }

    private BatchEmbeddingRequester createOpenAiRequester(OpenAIClient tieredClient) {
        return (batchTexts, options) -> {
            EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(model)
                .inputOfArrayOfStrings(batchTexts)
                .build();
            CreateEmbeddingResponse response = tieredClient.embeddings().create(params, options);
            return response.data().stream()
                .sorted(Comparator.comparingLong(Embedding::index))
                .map(Embedding::embedding)
                .toList();
        };
    }

    private RequestOptions requestOptions() {
        return RequestOptions.builder()
            .timeout(Timeout.builder()
                .request(Duration.ofSeconds(requestTimeoutSeconds))
                .read(Duration.ofSeconds(readTimeoutSeconds))
                .build())
            .build();
    }

    private void ensureAvailable() {
        if (!available || requestersByTier.isEmpty()) {
            throw new IllegalStateException("Book embedding client is not configured");
        }
    }

    static List<EmbeddingInputPlan> planEmbeddingInputs(List<String> sectionTexts, int maxEstimatedTokens) {
        List<EmbeddingInputPlan> inputPlans = new ArrayList<>(sectionTexts.size());
        int effectiveTokenLimit = BookSimilarityEmbeddingProperties.normalizedInputTokenComfortLimit(maxEstimatedTokens);
        for (int inputIndex = 0; inputIndex < sectionTexts.size(); inputIndex++) {
            String sectionText = sectionTexts.get(inputIndex);
            if (sectionText == null || sectionText.isBlank()) {
                throw new IllegalArgumentException("embedding input[" + inputIndex + "] is required");
            }
            inputPlans.add(new EmbeddingInputPlan(splitIntoChunks(sectionText, effectiveTokenLimit)));
        }
        return List.copyOf(inputPlans);
    }

    private static List<EmbeddingChunk> splitIntoChunks(String sectionText, int maxEstimatedTokens) {
        List<EmbeddingChunk> chunks = new ArrayList<>();
        StringBuilder chunkBuilder = new StringBuilder(Math.min(sectionText.length(), maxEstimatedTokens));
        int chunkEstimatedTokens = 0;
        for (int offset = 0; offset < sectionText.length();) {
            int codePoint = sectionText.codePointAt(offset);
            int codePointEstimatedTokens = utf8ByteLength(codePoint);
            if (chunkEstimatedTokens > 0 && chunkEstimatedTokens + codePointEstimatedTokens > maxEstimatedTokens) {
                chunks.add(new EmbeddingChunk(chunkBuilder.toString(), chunkEstimatedTokens));
                chunkBuilder.setLength(0);
                chunkEstimatedTokens = 0;
            }
            chunkBuilder.appendCodePoint(codePoint);
            chunkEstimatedTokens += codePointEstimatedTokens;
            offset += Character.charCount(codePoint);
        }
        if (!chunkBuilder.isEmpty()) {
            chunks.add(new EmbeddingChunk(chunkBuilder.toString(), chunkEstimatedTokens));
        }
        return List.copyOf(chunks);
    }

    private static List<EmbeddingChunk> flattenRequestChunks(List<EmbeddingInputPlan> inputPlans) {
        List<EmbeddingChunk> requestChunks = new ArrayList<>();
        for (EmbeddingInputPlan inputPlan : inputPlans) {
            requestChunks.addAll(inputPlan.chunks());
        }
        return List.copyOf(requestChunks);
    }

    private static List<List<Float>> collapseChunkEmbeddings(List<EmbeddingInputPlan> inputPlans,
                                                            List<List<Float>> chunkEmbeddings) {
        List<List<Float>> sectionEmbeddings = new ArrayList<>(inputPlans.size());
        int chunkIndex = 0;
        for (EmbeddingInputPlan inputPlan : inputPlans) {
            int nextChunkIndex = chunkIndex + inputPlan.chunks().size();
            List<List<Float>> inputChunkEmbeddings = chunkEmbeddings.subList(chunkIndex, nextChunkIndex);
            sectionEmbeddings.add(fuseInputChunks(inputChunkEmbeddings, inputPlan.chunks()));
            chunkIndex = nextChunkIndex;
        }
        return List.copyOf(sectionEmbeddings);
    }

    static List<Float> fuseInputChunks(List<List<Float>> chunkEmbeddings, List<EmbeddingChunk> chunks) {
        if (chunkEmbeddings.size() != chunks.size()) {
            throw new IllegalStateException("Embedding chunk count did not match request plan");
        }
        if (chunkEmbeddings.size() == 1) {
            return BookSimilarityVectorFusion.fuseWeighted(chunkEmbeddings, List.of(1.0d));
        }
        double totalWeight = chunks.stream()
            .mapToDouble(EmbeddingChunk::estimatedTokens)
            .sum();
        List<Double> chunkWeights = chunks.stream()
            .map(chunk -> chunk.estimatedTokens() / totalWeight)
            .toList();
        return BookSimilarityVectorFusion.fuseWeighted(chunkEmbeddings, chunkWeights);
    }

    private static void validateEmbeddingResponse(int inputCount, List<List<Float>> embeddings) {
        if (embeddings.size() != inputCount) {
            throw new IllegalStateException("Embedding response count did not match input count");
        }
        for (List<Float> embedding : embeddings) {
            if (embedding.size() != BookSimilarityVectorFusion.EMBEDDING_DIMENSION) {
                throw new IllegalStateException(
                    "Embedding dimension mismatch: expected " + BookSimilarityVectorFusion.EMBEDDING_DIMENSION
                        + " but received " + embedding.size()
                );
            }
        }
    }

    private static int utf8ByteLength(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    record EmbeddingInputPlan(List<EmbeddingChunk> chunks) {
        EmbeddingInputPlan {
            if (chunks == null || chunks.isEmpty()) {
                throw new IllegalArgumentException("chunks are required");
            }
            chunks = List.copyOf(chunks);
        }
    }

    record EmbeddingChunk(String text, int estimatedTokens) {
        EmbeddingChunk {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("chunk text is required");
            }
            if (estimatedTokens <= 0) {
                throw new IllegalArgumentException("estimatedTokens must be positive");
            }
        }
    }

    @FunctionalInterface
    interface BatchEmbeddingRequester {
        List<List<Float>> embed(List<String> batchTexts, RequestOptions options);
    }
}
