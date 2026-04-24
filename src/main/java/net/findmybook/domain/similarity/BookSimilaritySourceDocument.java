package net.findmybook.domain.similarity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Complete auditable source contract used to compute one fused book embedding.
 *
 * @param bookId canonical book identifier
 * @param sourceText rendered section text for operator diagnostics
 * @param sourceJson structured source metadata stored beside the fused vector
 * @param sourceHash SHA-256 hash over the rendered source contract
 * @param sectionHash SHA-256 hash over the section input hashes
 * @param sectionInputs rendered section inputs used for section embeddings
 */
public record BookSimilaritySourceDocument(
    UUID bookId,
    String sourceText,
    SourceMetadata sourceJson,
    String sourceHash,
    String sectionHash,
    List<BookSimilaritySectionInput> sectionInputs
) {

    /**
     * Validates the full source document.
     */
    public BookSimilaritySourceDocument {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId is required");
        }
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("sourceText is required");
        }
        if (sourceJson == null) {
            throw new IllegalArgumentException("sourceJson is required");
        }
        if (sourceHash == null || sourceHash.isBlank()) {
            throw new IllegalArgumentException("sourceHash is required");
        }
        if (sectionHash == null || sectionHash.isBlank()) {
            throw new IllegalArgumentException("sectionHash is required");
        }
        if (sectionInputs == null || sectionInputs.isEmpty()) {
            throw new IllegalArgumentException("sectionInputs are required");
        }
        sectionInputs = List.copyOf(sectionInputs);
    }

    /**
     * Creates a document from a book source and fusion policy.
     *
     * @param source canonical book source data
     * @param policy active fusion policy
     * @param model configured embeddings model
     * @param modelVersion computed model version
     * @param hashFactory deterministic hash function
     * @param sourceJsonRenderer deterministic source-json renderer
     * @return auditable source document
     */
    public static BookSimilaritySourceDocument create(
        BookSimilarityBookSource source,
        BookSimilarityFusionPolicy policy,
        String model,
        String modelVersion,
        HashFactory hashFactory,
        SourceJsonRenderer sourceJsonRenderer
    ) {
        BookSimilarityFusionProfile activeProfile = policy.activeProfile();
        List<BookSimilaritySectionInput> sectionInputs = new ArrayList<>();
        for (BookSimilaritySectionKey sectionKey : policy.sectionOrder()) {
            if (activeProfile.weightFor(sectionKey) <= 0.0d) {
                continue;
            }
            String sectionText = source.renderSection(sectionKey);
            if (sectionText == null || sectionText.isBlank()) {
                continue;
            }
            sectionInputs.add(new BookSimilaritySectionInput(
                sectionKey,
                sectionText,
                hashFactory.sha256Hex(sectionText)
            ));
        }
        LinkedHashMap<BookSimilaritySectionKey, Double> normalizedWeights =
            policy.normalizedWeightsFor(sectionInputs.stream().map(BookSimilaritySectionInput::sectionKey).toList());
        SourceMetadata metadata = SourceMetadata.from(source.bookId(), policy, model, modelVersion, normalizedWeights, sectionInputs);
        String sourceText = renderSourceText(sectionInputs);
        String sourceJson = sourceJsonRenderer.render(metadata);
        return new BookSimilaritySourceDocument(
            source.bookId(),
            sourceText,
            metadata,
            hashFactory.sha256Hex(sourceText + "\n" + sourceJson),
            hashFactory.sha256Hex(renderSectionHashInput(sectionInputs)),
            sectionInputs
        );
    }

    private static String renderSectionHashInput(List<BookSimilaritySectionInput> sectionInputs) {
        List<String> hashLines = new ArrayList<>();
        for (BookSimilaritySectionInput sectionInput : sectionInputs) {
            hashLines.add(sectionInput.sectionKey().key() + ":" + sectionInput.inputHash());
        }
        return String.join("\n", hashLines);
    }

    private static String renderSourceText(List<BookSimilaritySectionInput> sectionInputs) {
        List<String> blocks = new ArrayList<>();
        for (BookSimilaritySectionInput sectionInput : sectionInputs) {
            blocks.add("[" + sectionInput.sectionKey().key() + "]\n" + sectionInput.text());
        }
        return String.join("\n\n", blocks);
    }

    /**
     * Hash function used by source-document creation.
     */
    @FunctionalInterface
    public interface HashFactory {
        String sha256Hex(String input);
    }

    /**
     * JSON renderer used to keep source hashing deterministic.
     */
    @FunctionalInterface
    public interface SourceJsonRenderer {
        String render(SourceMetadata metadata);
    }

    /**
     * Structured metadata persisted next to the fused vector.
     *
     * @param contentType source kind
     * @param sourceId source identifier
     * @param regime embedding construction regime
     * @param profileId active profile id
     * @param model embeddings model
     * @param modelVersion model/profile/regime version
     * @param sections weighted section diagnostics
     */
    public record SourceMetadata(
        @JsonProperty("content_type") String contentType,
        @JsonProperty("source_id") String sourceId,
        String regime,
        @JsonProperty("profile_id") String profileId,
        String model,
        @JsonProperty("model_version") String modelVersion,
        List<SectionMetadata> sections
    ) {
        static SourceMetadata from(UUID bookId,
                                   BookSimilarityFusionPolicy policy,
                                   String model,
                                   String modelVersion,
                                   Map<BookSimilaritySectionKey, Double> normalizedWeights,
                                   List<BookSimilaritySectionInput> sectionInputs) {
            List<SectionMetadata> sectionMetadata = sectionInputs.stream()
                .map(sectionInput -> new SectionMetadata(
                    sectionInput.sectionKey().key(),
                    normalizedWeights.get(sectionInput.sectionKey()),
                    sectionInput.inputHash(),
                    sectionInput.text().length()
                ))
                .toList();
            return new SourceMetadata(
                "book",
                bookId.toString(),
                BookSimilarityFusionPolicy.SECTION_FUSION_REGIME,
                policy.activeProfileId(),
                model,
                modelVersion,
                sectionMetadata
            );
        }
    }

    /**
     * Section-level source metadata for diagnostics and reproducibility.
     *
     * @param key canonical section key
     * @param weight normalized weight used in fusion
     * @param inputHash SHA-256 hash of section text
     * @param length section text length
     */
    public record SectionMetadata(String key, double weight, String inputHash, int length) {
    }
}
