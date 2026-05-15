package net.findmybook.application.similarity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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
import net.findmybook.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final int BACKGROUND_PRIORITY = -10;
    private static final int DEMAND_PRIORITY = 10;

    private final BookSimilarityEmbeddingRepository repository;
    private final BookEmbeddingSectionRepository sectionRepository;
    private final BookEmbeddingClient embeddingClient;
    private final BookSimilarityFusionPolicy policy;
    private final BookSimilarityVectorFusion vectorFusion;
    private final BookAiContentRequestQueue requestQueue;
    private final ObjectMapper objectMapper;
    private final BookSimilarityEmbeddingProperties properties;
    private final Cache<UUID, Boolean> recentRefreshAttempts;

    public BookSimilarityEmbeddingService(BookSimilarityEmbeddingRepository repository,
                                          BookEmbeddingSectionRepository sectionRepository,
                                          BookEmbeddingClient embeddingClient,
                                          BookSimilarityFusionPolicy policy,
                                          BookSimilarityVectorFusion vectorFusion,
                                          BookAiContentRequestQueue requestQueue,
                                          ObjectMapper objectMapper,
                                          BookSimilarityEmbeddingProperties properties) {
        this.repository = repository;
        this.sectionRepository = sectionRepository;
        this.embeddingClient = embeddingClient;
        this.policy = policy;
        this.vectorFusion = vectorFusion;
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
        String modelVersion = activeModelVersion(embeddingClient.cacheModel());
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
        String modelVersion = activeModelVersion(embeddingClient.cacheModel());
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
        String cacheModel = embeddingClient.cacheModel();
        if (!StringUtils.hasText(cacheModel)) {
            return List.of();
        }
        String currentModelVersion = activeModelVersion(cacheModel);
        List<SimilarBookMatch> currentMatches =
            findNearestBooksForModelVersion(sourceBookId, currentModelVersion, limit);
        if (currentMatches.size() >= limit) {
            return currentMatches;
        }

        List<SimilarBookMatch> previousMatches =
            findNearestBooksForModelVersion(sourceBookId, policy.modelVersion(cacheModel), limit);
        if (!previousMatches.isEmpty()) {
            return previousMatches;
        }
        String legacyModel = embeddingClient.model();
        if (!StringUtils.hasText(legacyModel)) {
            return currentMatches;
        }
        List<SimilarBookMatch> legacyMatches =
            findNearestBooksForModelVersion(sourceBookId, policy.modelVersion(legacyModel), limit);
        return legacyMatches.isEmpty() ? currentMatches : legacyMatches;
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
        String cacheModel = embeddingClient.cacheModel();
        String modelVersion = activeModelVersion(cacheModel);
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
            cacheModel
        );
        List<Float> fusedEmbedding = vectorFusion.fuse(sourceDocument, sectionEmbeddings);
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
        String cacheModel = embeddingClient.cacheModel();
        String modelVersion = activeModelVersion(cacheModel);
        if (priority == DEMAND_PRIORITY
            && repository.isVectorFresh(bookId, modelVersion, policy.profileHash())) {
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

    private List<SimilarBookMatch> findNearestBooksForModelVersion(UUID sourceBookId, String modelVersion, int limit) {
        return repository.findNearestBooks(sourceBookId, modelVersion, policy.profileHash(), limit).stream()
            .map(row -> new SimilarBookMatch(row.bookId(), row.similarity()))
            .toList();
    }

    private String activeModelVersion(String cacheModel) {
        return policy.modelVersion(cacheModel + ":" + properties.sourceTextContract());
    }

    private EnumMap<BookSimilaritySectionKey, List<Float>> loadOrCreateSectionEmbeddings(
        BookSimilaritySourceDocument sourceDocument,
        String cacheModel
    ) {
        EnumMap<BookSimilaritySectionKey, List<Float>> sectionEmbeddings = new EnumMap<>(BookSimilaritySectionKey.class);
        List<BookSimilaritySectionInput> missingInputs = new ArrayList<>();
        for (BookSimilaritySectionInput sectionInput : sourceDocument.sectionInputs()) {
            Optional<List<Float>> cachedEmbedding = sectionRepository.fetchSectionEmbedding(
                sourceDocument.bookId(),
                sectionInput.sectionKey(),
                cacheModel,
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
                sectionRepository.upsertSectionEmbedding(sourceDocument.bookId(), sectionInput, cacheModel, embedding);
                sectionEmbeddings.put(sectionInput.sectionKey(), embedding);
            }
        }
        return sectionEmbeddings;
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
