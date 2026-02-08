package net.findmybook.domain.ai;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Immutable AI-generated analysis payload for a single book.
 */
public record BookAiAnalysis(
    String summary,
    String readerFit,
    List<String> keyThemes,
    @Nullable List<String> takeaways,
    @Nullable String context
) {
}
