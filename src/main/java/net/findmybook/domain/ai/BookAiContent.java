package net.findmybook.domain.ai;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Immutable AI-generated content payload for a single book.
 *
 * <p>Fields beyond {@code summary} may be null when the model could not
 * extract sufficient information from the book description without
 * fabricating content.</p>
 */
public record BookAiContent(
    String summary,
    @Nullable String readerFit,
    List<String> keyThemes,
    @Nullable List<String> takeaways,
    @Nullable String context
) {
}
