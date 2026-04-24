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
import net.findmybook.adapters.persistence.BookEmbeddingSectionRepository;
import net.findmybook.adapters.persistence.BookSimilarityEmbeddingRepository;
import net.findmybook.adapters.persistence.BookSimilarityEmbeddingRepository.FusedEmbeddingRow;
import net.findmybook.boot.BookSimilarityEmbeddingProperties;
import net.findmybook.domain.similarity.BookSimilarityBookSource;
import net.findmybook.domain.similarity.BookSimilarityFusionPolicy;
import net.findmybook.domain.similarity.BookSimilaritySectionInput;
import net.findmybook.domain.similarity.BookSimilaritySectionKey;
import net.findmybook.domain.similarity.BookSimilaritySourceDocument;
import net.findmybook.support.ai.BookAiContentRequestQueue;
import net.findmybook.support.ai.BookAiQueueCapacityExceededException;
import net.findmybook.support.llm.LlmGatewayTier;
import net.findmybook.service.event.BookUpsertEvent;
import net.findmybook.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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
    private final BookEmbeddingSectionRepository sectionRepository;
    private final BookEmbeddingClient embeddingClient;
    private final BookSimilarityFusionPolicy policy;
    private final BookAiContentRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final BookSimilarityEmbeddingProperties properties;
    private final Cache<UUID, Boolean> recentRefreshAttempts;

    public BookSimilarityEmbeddingService(BookSimilarityEmbeddingRepository repository,
                                          BookEmbeddingSectionRepository sectionRepository,
                                          BookEmbeddingClient embeddingClient,
                                          BookSimilarityFusionPolicy policy,
                                          BookAiContentRequestQueue requestQueue,
                                          ObjectMapper objectMapper,
                                          BookSimilarityEmbeddingProperties properties) {
        this.repository = repository;
        this.sectionRepository = sectionRepository;
        this.embeddingClient = embeddingClient;
        this.policy = policy;
        this.requestQueue = requestQueue;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.recentRefreshAttempts = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofMinutes(60))
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
     * Enqueues an idempotent refresh when canonical book source data changes.
     *
     * @param event persisted book upsert notification
     */
    @EventListener
    public void handleBookUpsert(BookUpsertEvent event) {
        if (event == null || !StringUtils.hasText(event.getBookId())) {
            log.warn("Skipping similarity embedding refresh enqueue: missing bookId in BookUpsertEvent");
            return;
        }
        try {
            enqueueDemandRefresh(UUID.fromString(event.getBookId()));
        } catch (IllegalArgumentException invalidBookId) {
            log.warn("Skipping similarity embedding refresh enqueue for non-UUID bookId '{}'", event.getBookId(), invalidBookId);
        }
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
     * Refreshes every book whose vector row is missing or stale for the active contract.
     *
     * <p>Drives backfill from operator CLI or runners through the canonical refresh path
     * so \`source_hash\`, \`source_text\`, \`source_json\`, and \`qwen_4b_fp16\` are populated
     * identically to scheduled and demand refreshes. Failures on a single book are logged
     * and skipped so the run continues.</p>
     *
     * @param candidateLimit bounded number of stale books to refresh in this pass
     * @return number of books whose vector was rewritten
     */
    public int backfillStale(int candidateLimit) {
        if (!embeddingClient.isAvailable() || !properties.isEnabled()) {
            log.info("Skipping book similarity backfill: embedding client unavailable or feature disabled.");
            return 0;
        }
        String modelVersion = policy.modelVersion(embeddingClient.model());
        List<UUID> candidates = repository.findRefreshCandidates(modelVersion, policy.profileHash(), Math.max(1, candidateLimit));
        int refreshed = 0;
        for (UUID bookId : candidates) {
            try {
                if (refreshBookIfStale(bookId, "backfill")) {
                    refreshed++;
                }
            } catch (RuntimeException backfillFailure) {
                log.error("Book similarity backfill refresh failed for book {}", bookId, backfillFailure);
            }
        }
        log.info("Book similarity backfill refreshed {} of {} candidate books.", refreshed, candidates.size());
        return refreshed;
    }

    /**
     * Reads persisted vector neighbors for the active similarity contract.
     *
     * @param sourceBookId canonical source book UUID
     * @param limit maximum neighbor count
     * @return ranked neighbor IDs with cosine similarity scores
     */
    public List<SimilarBookMatch> findNearestBooks(UUID sourceBookId, int limit) {
        if (sourceBookId == null || limit <= 0 || !properties.isEnabled()) {
            return List.of();
        }
        String model = embeddingClient.model();
        if (!StringUtils.hasText(model)) {
            return List.of();
        }
        String modelVersion = policy.modelVersion(model);
        return repository.findNearestBooks(sourceBookId, modelVersion, policy.profileHash(), limit).stream()
            .map(row -> new SimilarBookMatch(row.bookId(), row.similarity()))
            .toList();
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
            properties.maxSectionTextChars(),
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
        if (bookId == null || !embeddingClient.isAvailable() || !properties.isEnabled()) {
            return false;
        }
        if (priority == DEMAND_PRIORITY && isVectorAlreadyFresh(bookId)) {
            return false;
        }
        if (recentRefreshAttempts.asMap().putIfAbsent(bookId, Boolean.TRUE) != null) {
            return false;
        }
        try {
            requestQueue.enqueueBackground(priority, () -> {
                try {
                    refreshBookIfStale(bookId, reason);
                } catch (BookEmbeddingApiException embeddingApiException) {
                    log.warn(
                        "Skipping book similarity refresh for book {} ({}): {}",
                        bookId,
                        reason,
                        embeddingApiException.getMessage()
                    );
                }
                return null;
            }).result().whenComplete((ignored, failure) -> {
                recentRefreshAttempts.invalidate(bookId);
                if (failure != null) {
                    log.error("Book similarity embedding refresh failed for book {} ({})", bookId, reason, failure);
                }
            });
            return true;
        } catch (BookAiQueueCapacityExceededException queueCapacityExceededException) {
            recentRefreshAttempts.invalidate(bookId);
            log.warn(
                "Book similarity refresh enqueue skipped for book {} because AI queue cap was reached (pending={}, max={})",
                bookId,
                queueCapacityExceededException.currentPending(),
                queueCapacityExceededException.maxPending()
            );
            return false;
        }
    }

    private boolean isVectorAlreadyFresh(UUID bookId) {
        String model = embeddingClient.model();
        if (!StringUtils.hasText(model)) {
            return false;
        }
        return repository.isVectorFresh(bookId, policy.modelVersion(model), policy.profileHash());
    }

    private EnumMap<BookSimilaritySectionKey, List<Float>> loadOrCreateSectionEmbeddings(
        BookSimilaritySourceDocument sourceDocument,
        String model
    ) {
        EnumMap<BookSimilaritySectionKey, List<Float>> sectionEmbeddings = new EnumMap<>(BookSimilaritySectionKey.class);
        List<BookSimilaritySectionInput> missingInputs = new ArrayList<>();
        for (BookSimilaritySectionInput sectionInput : sourceDocument.sectionInputs()) {
            Optional<List<Float>> cachedEmbedding = sectionRepository.fetchSectionEmbedding(
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
                missingInputs.stream().map(BookSimilaritySectionInput::text).toList(),
                LlmGatewayTier.BACKGROUND_BATCH
            );
            for (int index = 0; index < missingInputs.size(); index++) {
                BookSimilaritySectionInput sectionInput = missingInputs.get(index);
                List<Float> embedding = generatedEmbeddings.get(index);
                sectionRepository.upsertSectionEmbedding(sourceDocument.bookId(), sectionInput, model, embedding);
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

    /**
     * Ranked persisted vector neighbor for a source book.
     */
    public record SimilarBookMatch(UUID bookId, double similarity) {
    }
}
