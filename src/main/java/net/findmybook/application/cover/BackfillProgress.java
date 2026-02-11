package net.findmybook.application.cover;

import java.util.List;

public record BackfillProgress(
    int totalCandidates,
    int processed,
    int coverFound,
    int noCoverFound,
    boolean running,
    String currentBookId,
    String currentBookTitle,
    String currentBookIsbn,
    List<SourceAttemptStatus> currentBookAttempts,
    String lastCompletedBookId,
    String lastCompletedBookTitle,
    String lastCompletedBookIsbn,
    Boolean lastCompletedBookFound,
    List<SourceAttemptStatus> lastCompletedBookAttempts
) {
    public BackfillProgress {
        currentBookAttempts = currentBookAttempts == null ? List.of() : List.copyOf(currentBookAttempts);
        lastCompletedBookAttempts = lastCompletedBookAttempts == null
            ? List.of()
            : List.copyOf(lastCompletedBookAttempts);
    }
}
