package net.findmybook.domain.seo;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted SEO metadata version for a book.
 *
 * <p>Captures the current title/description candidate plus model provenance so
 * metadata rendering can prefer canonical SEO rows while preserving fallback logic.
 *
 * @param bookId canonical book UUID
 * @param version version number for the persisted snapshot
 * @param generatedAt timestamp when the snapshot was created
 * @param model model used to generate the snapshot
 * @param provider provider label for the generation call
 * @param seoTitle generated SEO title candidate
 * @param seoDescription generated SEO description candidate
 * @param promptHash SHA-256 hash of the generation prompt context
 */
public record BookSeoMetadataSnapshot(
    UUID bookId,
    int version,
    Instant generatedAt,
    String model,
    String provider,
    String seoTitle,
    String seoDescription,
    String promptHash
) {
}
