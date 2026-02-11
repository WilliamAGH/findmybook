package net.findmybook.application.cover;

import java.util.List;

record ProcessBookResult(boolean coverFound, List<SourceAttemptStatus> attempts) {
    ProcessBookResult {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
}
