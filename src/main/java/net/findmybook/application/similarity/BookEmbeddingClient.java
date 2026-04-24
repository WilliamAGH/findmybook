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
import java.util.Comparator;
import java.util.List;
import net.findmybook.application.ai.BookAiGenerationException;
import net.findmybook.boot.OpenAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible embeddings client for book similarity section vectors.
 */
@Component
public class BookEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(BookEmbeddingClient.class);
    private static final int EXPECTED_DIMENSION = 2560;

    private final OpenAIClient openAiClient;
    private final boolean available;
    private final String model;
    private final long requestTimeoutSeconds;
    private final long readTimeoutSeconds;

    /**
     * Creates the configured embeddings client from canonical OpenAI properties.
     *
     * @param openAiProperties provider and model settings
     */
    public BookEmbeddingClient(OpenAiProperties openAiProperties) {
        this.model = openAiProperties.embeddingsModel();
        this.requestTimeoutSeconds = openAiProperties.requestTimeoutSeconds();
        this.readTimeoutSeconds = openAiProperties.readTimeoutSeconds();
        if (openAiProperties.isEmbeddingsConfigured()) {
            this.openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(openAiProperties.apiKey())
                .baseUrl(openAiProperties.baseUrl())
                .maxRetries(0)
                .build();
            this.available = true;
            log.info("Book embedding client configured (model={}, baseUrl={})", model, openAiProperties.baseUrl());
            return;
        }
        this.openAiClient = null;
        this.available = false;
        log.warn("Book embedding client is disabled: missing OPENAI_API_KEY, OPENAI_BASE_URL, or OPENAI_EMBEDDINGS_MODEL");
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
     * Embeds a batch of section texts.
     *
     * @param inputs section texts in desired output order
     * @return embeddings in input order
     */
    public List<List<Float>> embedSections(List<String> inputs) {
        ensureAvailable();
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs are required");
        }
        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
            .model(model)
            .inputOfArrayOfStrings(inputs)
            .build();
        RequestOptions options = RequestOptions.builder()
            .timeout(Timeout.builder()
                .request(Duration.ofSeconds(requestTimeoutSeconds))
                .read(Duration.ofSeconds(readTimeoutSeconds))
                .build())
            .build();
        try {
            CreateEmbeddingResponse response = openAiClient.embeddings().create(params, options);
            List<List<Float>> embeddings = response.data().stream()
                .sorted(Comparator.comparingLong(Embedding::index))
                .map(Embedding::embedding)
                .toList();
            validateEmbeddingResponse(inputs.size(), embeddings);
            return embeddings;
        } catch (OpenAIException openAiException) {
            throw new BookEmbeddingApiException(
                "Embedding API call failed for model %s: %s".formatted(
                    model,
                    BookAiGenerationException.describeApiError(openAiException)
                ),
                openAiException
            );
        }
    }

    private void ensureAvailable() {
        if (!available || openAiClient == null) {
            throw new IllegalStateException("Book embedding client is not configured");
        }
    }

    private static void validateEmbeddingResponse(int inputCount, List<List<Float>> embeddings) {
        if (embeddings.size() != inputCount) {
            throw new IllegalStateException("Embedding response count did not match input count");
        }
        for (List<Float> embedding : embeddings) {
            if (embedding.size() != EXPECTED_DIMENSION) {
                throw new IllegalStateException(
                    "Embedding dimension mismatch: expected " + EXPECTED_DIMENSION + " but received " + embedding.size()
                );
            }
        }
    }
}
