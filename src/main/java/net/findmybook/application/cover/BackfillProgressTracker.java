package net.findmybook.application.cover;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class BackfillProgressTracker {

    private final AtomicReference<BackfillProgress> progress = new AtomicReference<>(
        new BackfillProgress(
            0, 0, 0, 0, false,
            null, null, null, List.of(),
            null, null, null, null, List.of()
        )
    );

    public BackfillProgress getProgress() {
        return progress.get();
    }

    void reset(int totalCandidates) {
        updateProgress(totalCandidates, 0, 0, 0, true, null, List.of(), null, null, List.of());
    }

    void updateProgress(int total,
                        int processed,
                        int found,
                        int notFound,
                        boolean running,
                        BackfillCandidate currentBook,
                        List<SourceAttemptStatus> currentBookAttempts,
                        BackfillCandidate lastCompletedBook,
                        Boolean lastCompletedBookFound,
                        List<SourceAttemptStatus> lastCompletedBookAttempts) {
        progress.set(new BackfillProgress(
            total,
            processed,
            found,
            notFound,
            running,
            currentBook == null ? null : currentBook.id().toString(),
            currentBook == null ? null : currentBook.displayTitle(),
            currentBook == null ? null : currentBook.preferredIsbn(),
            currentBookAttempts,
            lastCompletedBook == null ? null : lastCompletedBook.id().toString(),
            lastCompletedBook == null ? null : lastCompletedBook.displayTitle(),
            lastCompletedBook == null ? null : lastCompletedBook.preferredIsbn(),
            lastCompletedBookFound,
            lastCompletedBookAttempts
        ));
    }
}
