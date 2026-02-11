package net.findmybook.application.cover;

import java.time.Instant;

public record SourceAttemptStatus(
    String source,
    String outcome,
    String detail,
    Instant attemptedAt
) {}
