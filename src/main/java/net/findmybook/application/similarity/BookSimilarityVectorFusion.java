package net.findmybook.application.similarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.findmybook.domain.similarity.BookSimilarityFusionPolicy;
import net.findmybook.domain.similarity.BookSimilaritySectionInput;
import net.findmybook.domain.similarity.BookSimilaritySectionKey;
import net.findmybook.domain.similarity.BookSimilaritySourceDocument;
import org.springframework.stereotype.Component;

/**
 * Fuses per-section embeddings into a single L2-normalized book vector.
 *
 * <p>Extracted from {@code BookSimilarityEmbeddingService} so that service code stays
 * focused on scheduling and orchestration while the pure vector math has one owner
 * driven by the active {@link BookSimilarityFusionPolicy} weights.</p>
 */
@Component
public class BookSimilarityVectorFusion {

    static final int EMBEDDING_DIMENSION = 2560;

    private final BookSimilarityFusionPolicy policy;

    public BookSimilarityVectorFusion(BookSimilarityFusionPolicy policy) {
        this.policy = policy;
    }

    /**
     * Produces the fused, L2-normalized embedding for a book from its per-section embeddings.
     *
     * @param sourceDocument canonical source document describing the active sections
     * @param sectionEmbeddings per-section embedding vectors keyed by section
     * @return fused vector of length {@link #EMBEDDING_DIMENSION}
     */
    public List<Float> fuse(BookSimilaritySourceDocument sourceDocument,
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
}
