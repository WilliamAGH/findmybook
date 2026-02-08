package net.findmybook.domain.ai;

import java.util.List;

/**
 * Immutable AI-generated reader-fit payload for a single book.
 */
public record BookAiAnalysis(
    String summary,
    String readerFit,
    List<String> keyThemes
) {
}
