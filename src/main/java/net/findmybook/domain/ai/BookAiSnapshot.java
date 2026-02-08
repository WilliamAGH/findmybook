package net.findmybook.domain.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted AI analysis version for a book, including metadata used by API/UI.
 */
public record BookAiSnapshot(
    UUID bookId,
    int version,
    Instant generatedAt,
    String model,
    String provider,
    BookAiAnalysis analysis
) {
}
