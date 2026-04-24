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
    /**
     * Initial progress state before a backfill run starts.
     */
    public static BackfillProgress notStarted() {
        return new BackfillProgress(
            0,
            0,
            0,
            0,
            false,
            noBookText(),
            noBookText(),
            noBookText(),
            List.of(),
            noBookText(),
            noBookText(),
            noBookText(),
            noBookFoundSignal(),
            List.of()
        );
    }

    public BackfillProgress {
        currentBookAttempts = currentBookAttempts == null ? List.of() : List.copyOf(currentBookAttempts);
        lastCompletedBookAttempts = lastCompletedBookAttempts == null
            ? List.of()
            : List.copyOf(lastCompletedBookAttempts);
    }

    private static String noBookText() {
        return null;
    }

    private static Boolean noBookFoundSignal() {
        return null;
    }
}
