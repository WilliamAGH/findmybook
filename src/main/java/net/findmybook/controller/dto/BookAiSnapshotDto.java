package net.findmybook.controller.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * API projection of AI-generated book analysis for a book detail page.
 *
 * <p>The payload is compact and stable so clients can render inline enrichment
 * without coupling to provider-specific response formats.</p>
 */
public record BookAiSnapshotDto(
    String summary,
    String readerFit,
    List<String> keyThemes,
    @Nullable List<String> takeaways,
    @Nullable String context,
    Integer version,
    Instant generatedAt,
    String model,
    String provider
) {
}
