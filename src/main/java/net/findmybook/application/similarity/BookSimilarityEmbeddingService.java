package net.findmybook.application.similarity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.adapters.persistence.BookSimilarityEmbeddingRepository;
import net.findmybook.adapters.persistence.BookSimilarityEmbeddingRepository.FusedEmbeddingRow;
import net.findmybook.domain.similarity.BookSimilarityBookSource;
import net.findmybook.domain.similarity.BookSimilarityFusionPolicy;
import net.findmybook.domain.similarity.BookSimilaritySectionInput;
import net.findmybook.domain.similarity.BookSimilaritySectionKey;
import net.findmybook.domain.similarity.BookSimilaritySourceDocument;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.ai.BookAiQueueCapacityExceededException;
import net.findmybook.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Computes and refreshes section-fused book similarity embeddings.
 */
@Service
public class BookSimilarityEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(BookSimilarityEmbeddingService.class);
    private static final int EMBEDDING_DIMENSION = 2560;
    private static final int BACKGROUND_PRIORITY = -10;
    private static final int DEMAND_PRIORITY = 10;

    private final BookSimilarityEmbeddingRepository repository;
    private final BookEmbeddingClient embeddingClient;
    private final BookSimilarityFusionPolicy policy;
    private final BookAiContentRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final Cache<UUID, Boolean> inFlightRefreshes;

    public BookSimilarityEmbeddingService(BookSimilarityEmbeddingRepository repository,
                                          BookEmbeddingClient embeddingClient,
                                          BookSimilarityFusionPolicy policy,
                                          BookAiContentRequestQueue requestQueue,
                                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.policy = policy;
        this.requestQueue = requestQueue;
        this.objectMapper = objectMapper;
        this.inFlightRefreshes = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();
    }

    /**
     * Enqueues one user-demanded book refresh behind foreground AI work.
     *
     * @param bookId canonical book UUID
     * @return true when a task was queued
     */
    public boolean enqueueDemandRefresh(UUID bookId) {
        return enqueueRefresh(bookId, DEMAND_PRIORITY, "demand");
    }

    /**
     * Discovers and enqueues missing or stale embedding candidates.
     *
     * @param candidateLimit database candidate rows to inspect
     * @param enqueueLimit maximum background tasks to enqueue
     * @param queuePendingLimit central AI queue pending depth that pauses scheduling
     * @return number of tasks queued
     */
    public int enqueueRefreshCandidates(int candidateLimit, int enqueueLimit, int queuePendingLimit) {
        if (!embeddingClient.isAvailable()) {
            return 0;
        }
        BookAiContentRequestQueue.QueueSnapshot queueSnapshot = requestQueue.snapshot();
        int pendingRoom = Math.max(0, queuePendingLimit - queueSnapshot.pending());
        if (pendingRoom == 0) {
            log.debug(
                "Skipping scheduled similarity embedding enqueue because AI queue pending depth is {} (limit={}).",
                queueSnapshot.pending(),
                queuePendingLimit
            );
            return 0;
        }
        String modelVersion = policy.modelVersion(embeddingClient.model());
        List<UUID> candidates = repository.findRefreshCandidates(modelVersion, policy.profileHash(), candidateLimit);
        int enqueued = 0;
        int effectiveEnqueueLimit = Math.min(enqueueLimit, pendingRoom);
        for (UUID candidate : candidates) {
            if (enqueued >= effectiveEnqueueLimit) {
                break;
            }
            if (enqueueRefresh(candidate, BACKGROUND_PRIORITY, "scheduled")) {
                enqueued++;
            }
        }
        return enqueued;
    }

    /**
     * Refreshes one book immediately when its source hash changed.
     *
     * @param bookId canonical book UUID
     * @param reason operator-facing reason for logs
     * @return true when a fused vector was written
     */
    public boolean refreshBookIfStale(UUID bookId, String reason) {
        if (!embeddingClient.isAvailable()) {
            log.debug("Skipping book similarity refresh because embedding client is unavailable.");
            return false;
        }
        BookSimilarityBookSource source = repository.fetchBookSource(bookId)
            .orElseThrow(() -> new IllegalStateException("Book source unavailable for similarity embedding: " + bookId));
        String model = embeddingClient.model();
        String modelVersion = policy.modelVersion(model);
        BookSimilaritySourceDocument sourceDocument = BookSimilaritySourceDocument.create(
            source,
            policy,
            model,
            modelVersion,
            this::sha256Hex,
            this::renderSourceJson
        );
        Optional<String> currentHash = repository.fetchCurrentSourceHash(bookId, modelVersion, policy.profileHash());
        if (currentHash.isPresent() && currentHash.get().equals(sourceDocument.sourceHash())) {
            log.debug("Book similarity embedding is current for book {} ({})", bookId, reason);
            return false;
        }
        EnumMap<BookSimilaritySectionKey, List<Float>> sectionEmbeddings = loadOrCreateSectionEmbeddings(
            sourceDocument,
            model
        );
        List<Float> fusedEmbedding = fuseSectionEmbeddings(sourceDocument, sectionEmbeddings);
        repository.upsertFusedEmbedding(new FusedEmbeddingRow(
            sourceDocument,
            policy.activeProfileId(),
            policy.profileHash(),
            model,
            modelVersion,
            fusedEmbedding
        ));
        log.info("Refreshed book similarity embedding for book {} ({})", bookId, reason);
        return true;
    }

    private boolean enqueueRefresh(UUID bookId, int priority, String reason) {
        if (bookId == null || !embeddingClient.isAvailable()) {
            return false;
        }
        if (inFlightRefreshes.asMap().putIfAbsent(bookId, Boolean.TRUE) != null) {
            return false;
        }
        try {
            requestQueue.enqueueBackground(priority, () -> {
                refreshBookIfStale(bookId, reason);
                return null;
            }).result().whenComplete((ignored, failure) -> {
                inFlightRefreshes.invalidate(bookId);
                if (failure != null) {
                    log.error("Book similarity embedding refresh failed for book {} ({})", bookId, reason, failure);
                }
            });
            return true;
        } catch (BookAiQueueCapacityExceededException queueCapacityExceededException) {
            inFlightRefreshes.invalidate(bookId);
            log.warn(
                "Book similarity refresh enqueue skipped for book {} because AI queue cap was reached (pending={}, max={})",
                bookId,
                queueCapacityExceededException.currentPending(),
                queueCapacityExceededException.maxPending()
            );
            return false;
        }
    }

    private EnumMap<BookSimilaritySectionKey, List<Float>> loadOrCreateSectionEmbeddings(
        BookSimilaritySourceDocument sourceDocument,
        String model
    ) {
        EnumMap<BookSimilaritySectionKey, List<Float>> sectionEmbeddings = new EnumMap<>(BookSimilaritySectionKey.class);
        List<BookSimilaritySectionInput> missingInputs = new ArrayList<>();
        for (BookSimilaritySectionInput sectionInput : sourceDocument.sectionInputs()) {
            Optional<List<Float>> cachedEmbedding = repository.fetchSectionEmbedding(
                sourceDocument.bookId(),
                sectionInput.sectionKey(),
                model,
                sectionInput.inputHash()
            );
            if (cachedEmbedding.isPresent()) {
                sectionEmbeddings.put(sectionInput.sectionKey(), cachedEmbedding.get());
            } else {
                missingInputs.add(sectionInput);
            }
        }
        if (!missingInputs.isEmpty()) {
            List<List<Float>> generatedEmbeddings = embeddingClient.embedSections(
                missingInputs.stream().map(BookSimilaritySectionInput::text).toList()
            );
            for (int index = 0; index < missingInputs.size(); index++) {
                BookSimilaritySectionInput sectionInput = missingInputs.get(index);
                List<Float> embedding = generatedEmbeddings.get(index);
                repository.upsertSectionEmbedding(sourceDocument.bookId(), sectionInput, model, embedding);
                sectionEmbeddings.put(sectionInput.sectionKey(), embedding);
            }
        }
        return sectionEmbeddings;
    }

    private List<Float> fuseSectionEmbeddings(BookSimilaritySourceDocument sourceDocument,
                                              Map<BookSimilaritySectionKey, List<Float>> sectionEmbeddings) {
        Map<BookSimilaritySectionKey, Double> weights = policy.normalizedWeightsFor(sectionEmbeddings.keySet());
        double[] fused = new double[EMBEDDING_DIMENSION];
        for (BookSimilaritySectionInput sectionInput : sourceDocument.sectionInputs()) {
            List<Float> embedding = sectionEmbeddings.get(sectionInput.sectionKey());
            if (embedding == null) {
                throw new IllegalStateException("Missing section embedding for " + sectionInput.sectionKey().key());
            }
            double norm = vectorNorm(embedding);
            if (norm == 0.0d) {
                continue;
            }
            double weight = weights.get(sectionInput.sectionKey());
            for (int index = 0; index < EMBEDDING_DIMENSION; index++) {
                fused[index] += (embedding.get(index) / norm) * weight;
            }
        }
        double fusedNorm = vectorNorm(fused);
        List<Float> result = new ArrayList<>(EMBEDDING_DIMENSION);
        for (double component : fused) {
            result.add((float) (fusedNorm == 0.0d ? component : component / fusedNorm));
        }
        return List.copyOf(result);
    }

    private static double vectorNorm(List<Float> embedding) {
        if (embedding.size() != EMBEDDING_DIMENSION) {
            throw new IllegalStateException("Embedding dimension mismatch: " + embedding.size());
        }
        double sum = 0.0d;
        for (Float component : embedding) {
            sum += component * component;
        }
        return Math.sqrt(sum);
    }

    private static double vectorNorm(double[] embedding) {
        double sum = 0.0d;
        for (double component : embedding) {
            sum += component * component;
        }
        return Math.sqrt(sum);
    }

    private String renderSourceJson(BookSimilaritySourceDocument.SourceMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException jacksonException) {
            throw new IllegalStateException("Failed to render book similarity source metadata", jacksonException);
        }
    }

    private String sha256Hex(String input) {
        try {
            return HashUtils.sha256Hex(input);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 is unavailable for book similarity hashing", noSuchAlgorithmException);
        }
    }
}
