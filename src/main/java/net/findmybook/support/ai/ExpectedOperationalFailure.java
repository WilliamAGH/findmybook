package net.findmybook.support.ai;

/**
 * Marker contract for operational AI and SEO failures whose cause is an expected domain,
 * content-eligibility, rate-limit, or circuit outcome rather than an unexpected defect.
 */
public interface ExpectedOperationalFailure {

    /**
     * Returns {@code true} when this failure represents an expected operational refusal
     * whose diagnostic message already explains the outcome without a multiline stack trace.
     */
    boolean isExpectedOperationalFailure();
}
