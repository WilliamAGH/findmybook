package net.findmybook.domain.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import net.findmybook.util.HashUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class BookSimilaritySourceDocumentTest {

    @Test
    void should_RenderAuditableSourceContract_When_BookHasWeightedSections() {
        UUID bookId = UUID.randomUUID();
        BookSimilaritySourceDocument document = BookSimilaritySourceDocument.create(
            source(bookId),
            policy(),
            "qwen/qwen3-embedding-4b",
            "qwen/qwen3-embedding-4b:test:section_fusion",
            BookSimilaritySourceDocumentTest::sha256Hex,
            BookSimilaritySourceDocumentTest::sourceJson
        );

        assertThat(document.sourceText()).contains("[identity]", "title: The Firm", "[classification]", "collection_categories: Fiction, legal");
        assertThat(document.sourceJson().contentType()).isEqualTo("book");
        assertThat(document.sourceJson().sourceId()).isEqualTo(bookId.toString());
        assertThat(document.sourceJson().sections()).hasSize(2);
        assertThat(document.sourceHash()).hasSize(64);
        assertThat(document.sectionHash()).hasSize(64);
    }

    @Test
    void should_ExcludeZeroWeightedSections_When_ProfileDisablesSectionByWeight() {
        UUID bookId = UUID.randomUUID();
        EnumMap<BookSimilaritySectionKey, Double> weights = new EnumMap<>(BookSimilaritySectionKey.class);
        weights.put(BookSimilaritySectionKey.IDENTITY, 1.0d);
        weights.put(BookSimilaritySectionKey.CLASSIFICATION, 0.0d);
        BookSimilarityFusionPolicy policy = new BookSimilarityFusionPolicy(
            "zero-weight",
            List.of(BookSimilaritySectionKey.IDENTITY, BookSimilaritySectionKey.CLASSIFICATION),
            List.of(new BookSimilarityFusionProfile("zero-weight", "Zero-weight classification", weights)),
            "profile-hash"
        );

        BookSimilaritySourceDocument document = BookSimilaritySourceDocument.create(
            source(bookId),
            policy,
            "qwen/qwen3-embedding-4b",
            "qwen/qwen3-embedding-4b:zero-weight:section_fusion",
            BookSimilaritySourceDocumentTest::sha256Hex,
            BookSimilaritySourceDocumentTest::sourceJson
        );

        assertThat(document.sectionInputs()).hasSize(1);
        assertThat(document.sectionInputs().get(0).sectionKey()).isEqualTo(BookSimilaritySectionKey.IDENTITY);
        assertThat(document.sourceJson().sections()).hasSize(1);
    }

    @Test
    void should_ChangeSourceHash_When_SourceFieldChanges() {
        UUID bookId = UUID.randomUUID();
        BookSimilarityFusionPolicy policy = policy();

        BookSimilaritySourceDocument first = BookSimilaritySourceDocument.create(
            source(bookId),
            policy,
            "qwen/qwen3-embedding-4b",
            "qwen/qwen3-embedding-4b:test:section_fusion",
            BookSimilaritySourceDocumentTest::sha256Hex,
            BookSimilaritySourceDocumentTest::sourceJson
        );
        BookSimilaritySourceDocument second = BookSimilaritySourceDocument.create(
            sourceWithTitle(bookId, "The Pelican Brief"),
            policy,
            "qwen/qwen3-embedding-4b",
            "qwen/qwen3-embedding-4b:test:section_fusion",
            BookSimilaritySourceDocumentTest::sha256Hex,
            BookSimilaritySourceDocumentTest::sourceJson
        );

        assertThat(second.sourceHash()).isNotEqualTo(first.sourceHash());
    }

    private static BookSimilarityBookSource source(UUID bookId) {
        return sourceWithTitle(bookId, "The Firm");
    }

    private static BookSimilarityBookSource sourceWithTitle(UUID bookId, String title) {
        return new BookSimilarityBookSource(
            bookId,
            title,
            "",
            "John Grisham",
            "",
            "Fiction, legal",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            ""
        );
    }

    private static BookSimilarityFusionPolicy policy() {
        EnumMap<BookSimilaritySectionKey, Double> weights = new EnumMap<>(BookSimilaritySectionKey.class);
        weights.put(BookSimilaritySectionKey.IDENTITY, 0.2d);
        weights.put(BookSimilaritySectionKey.CLASSIFICATION, 0.8d);
        return new BookSimilarityFusionPolicy(
            "test",
            List.of(BookSimilaritySectionKey.IDENTITY, BookSimilaritySectionKey.CLASSIFICATION),
            List.of(new BookSimilarityFusionProfile("test", "Test profile", weights)),
            "profile-hash"
        );
    }

    private static String sha256Hex(String input) {
        try {
            return HashUtils.sha256Hex(input);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 unavailable in test", noSuchAlgorithmException);
        }
    }

    private static String sourceJson(BookSimilaritySourceDocument.SourceMetadata sourceMetadata) {
        try {
            return new ObjectMapper().writeValueAsString(sourceMetadata);
        } catch (JacksonException jacksonException) {
            throw new IllegalStateException("Unable to render source JSON in test", jacksonException);
        }
    }
}
