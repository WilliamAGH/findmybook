package net.findmybook.domain.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted AI content version for a book, including metadata used by API/UI.
 */
public record BookAiContentSnapshot(
    UUID bookId,
    int version,
    Instant generatedAt,
    String model,
    String provider,
    BookAiContent aiContent
) {
}
