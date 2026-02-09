package net.findmybook.controller.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import net.findmybook.domain.ai.BookAiContentSnapshot;

/**
 * API projection of AI-generated book content for a book detail page.
 *
 * <p>The payload is compact and stable so clients can render inline enrichment
 * without coupling to provider-specific response formats.</p>
 */
public record BookAiContentSnapshotDto(
    String summary,
    @Nullable String readerFit,
    List<String> keyThemes,
    @Nullable List<String> takeaways,
    @Nullable String context,
    Integer version,
    Instant generatedAt,
    String model,
    String provider
) {
    /**
     * Maps a domain snapshot into the API payload shape used by controllers.
     *
     * @param snapshot domain snapshot returned by application services
     * @return API-facing DTO for serialization
     */
    public static BookAiContentSnapshotDto fromSnapshot(BookAiContentSnapshot snapshot) {
        return new BookAiContentSnapshotDto(
            snapshot.aiContent().summary(),
            snapshot.aiContent().readerFit(),
            snapshot.aiContent().keyThemes(),
            snapshot.aiContent().takeaways(),
            snapshot.aiContent().context(),
            snapshot.version(),
            snapshot.generatedAt(),
            snapshot.model(),
            snapshot.provider()
        );
    }
}
