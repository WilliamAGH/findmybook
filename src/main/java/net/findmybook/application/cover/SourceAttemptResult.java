package net.findmybook.application.cover;

record SourceAttemptResult(SourceAttemptOutcome outcome, String detail) {
    static SourceAttemptResult success(String detail) {
        return new SourceAttemptResult(SourceAttemptOutcome.SUCCESS, detail);
    }

    static SourceAttemptResult notFound(String detail) {
        return new SourceAttemptResult(SourceAttemptOutcome.NOT_FOUND, detail);
    }

    static SourceAttemptResult failure(String detail) {
        return new SourceAttemptResult(SourceAttemptOutcome.FAILURE, detail);
    }

    static SourceAttemptResult skipped(String detail) {
        return new SourceAttemptResult(SourceAttemptOutcome.SKIPPED, detail);
    }

    boolean success() {
        return outcome == SourceAttemptOutcome.SUCCESS;
    }
}
