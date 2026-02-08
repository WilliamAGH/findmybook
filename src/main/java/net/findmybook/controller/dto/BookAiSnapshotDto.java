package net.findmybook.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * API projection of AI-generated reader-fit analysis for a book detail page.
 *
 * <p>The payload is intentionally compact and stable so clients can render subtle,
 * inline enrichment without coupling to provider-specific response formats.</p>
 */
public record BookAiSnapshotDto(
    String summary,
    String readerFit,
    List<String> keyThemes,
    Integer version,
    Instant generatedAt,
    String model,
    String provider
) {
}
