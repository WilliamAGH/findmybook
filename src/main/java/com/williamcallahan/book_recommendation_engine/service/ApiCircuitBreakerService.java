/**
 * Centralizes runtime guards for Google Books API usage.
 *
 * <p>The service keeps track of two independent protection rails:</p>
 * <ul>
 *   <li>An <strong>authenticated</strong> circuit breaker – once a paid-key request receives a
 *   429 from Google, the breaker opens for the remainder of the UTC day so we immediately stop
 *   burning quota. Authenticated calls must check {@link #isApiCallAllowed()} before executing.</li>
 *   <li>An <strong>unauthenticated fallback</strong> guard – when the public tier also starts to
 *   rate limit (usually right after the authenticated quota is gone) we disable every
 *   unauthenticated call until the next UTC reset via {@link #isFallbackAllowed()}.</li>
 * </ul>
 *
 * <p>Both guards reset automatically at UTC midnight and can be inspected or reset manually from
 * the admin endpoints.</p>
 *
 * @author William Callahan
 */
package com.williamcallahan.book_recommendation_engine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class ApiCircuitBreakerService {

    // Circuit breaker states
    private enum CircuitState {
        CLOSED,    // Normal operation
        OPEN       // Circuit is open, blocking API calls until next day reset
    }

    /**
     * Google Books API quota resets at Pacific Time midnight, not UTC.
     * Using America/Los_Angeles handles both PST (UTC-8) and PDT (UTC-7) automatically.
     */
    private static final ZoneId QUOTA_RESET_TIMEZONE = ZoneId.of("America/Los_Angeles");

    private final AtomicReference<CircuitState> circuitState = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<LocalDateTime> lastFailureTime = new AtomicReference<>();
    private final AtomicReference<LocalDate> circuitOpenDate = new AtomicReference<>();

    private final AtomicReference<CircuitState> fallbackCircuitState = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger fallbackFailureCount = new AtomicInteger(0);
    private final AtomicReference<LocalDateTime> fallbackLastFailureTime = new AtomicReference<>();
    private final AtomicReference<LocalDate> fallbackOpenDate = new AtomicReference<>();

    // Configuration
    private static final int FAILURE_THRESHOLD = 1; // Open circuit after 1 rate limit error (429)
    // Circuit stays open until next Pacific Time day (Google Books quota resets at PT midnight)
    
    /**
     * Check if API calls are allowed based on circuit breaker state.
     * Resets automatically at Pacific Time midnight (Google Books quota reset time).
     *
     * @return true if API calls are allowed, false if circuit is open
     */
    public boolean isApiCallAllowed() {
        CircuitState currentState = circuitState.get();
        LocalDate nowPacific = LocalDate.now(QUOTA_RESET_TIMEZONE);
        LocalDate openDate = circuitOpenDate.get();

        // Auto-reset at Pacific Time midnight (quota reset)
        if (currentState == CircuitState.OPEN && openDate != null && nowPacific.isAfter(openDate)) {
            if (circuitState.compareAndSet(CircuitState.OPEN, CircuitState.CLOSED)) {
                failureCount.set(0);
                lastFailureTime.set(null);
                circuitOpenDate.set(null);
                log.info("Circuit breaker AUTO-RESET at Pacific midnight - quota refresh detected. State: CLOSED");
                return true;
            }
        }

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                log.debug("Circuit breaker is OPEN - blocking API call until Pacific midnight");
                return false;

            default:
                return true;
        }
    }

    /**
     * Determines whether unauthenticated fallback calls are permitted.
     *
     * <p>Mirrors {@link #isApiCallAllowed()} but tracks a separate state so we can independently
     * disable the public Google Books tier when it also starts rate limiting.</p>
     *
     * @return {@code true} when fallback calls are allowed, {@code false} otherwise
     */
    public boolean isFallbackAllowed() {
        CircuitState currentState = fallbackCircuitState.get();
        LocalDate nowPacific = LocalDate.now(QUOTA_RESET_TIMEZONE);
        LocalDate openDate = fallbackOpenDate.get();

        if (currentState == CircuitState.OPEN && openDate != null && nowPacific.isAfter(openDate)) {
            if (fallbackCircuitState.compareAndSet(CircuitState.OPEN, CircuitState.CLOSED)) {
                fallbackFailureCount.set(0);
                fallbackLastFailureTime.set(null);
                fallbackOpenDate.set(null);
                log.info("Fallback circuit AUTO-RESET at Pacific midnight - state: CLOSED");
                return true;
            }
        }

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                log.debug("Fallback circuit is OPEN - blocking unauthenticated calls until Pacific midnight");
                return false;

            default:
                return true;
        }
    }
    
    /**
     * Record a successful API call
     * Resets failure count when circuit is closed
     */
    public void recordSuccess() {
        CircuitState currentState = circuitState.get();
        
        if (currentState == CircuitState.CLOSED) {
            // Reset failure count on success
            failureCount.set(0);
            lastFailureTime.set(null);
        }
        // If circuit is OPEN, success doesn't matter - we wait for Pacific midnight reset
    }
    
    /**
     * Record a rate limit failure (429 error).
     * IMMEDIATELY opens circuit breaker for remainder of Pacific Time day
     * (Google Books quota resets at Pacific midnight).
     */
    public void recordRateLimitFailure() {
        LocalDateTime now = LocalDateTime.now(QUOTA_RESET_TIMEZONE);
        LocalDate nowPacificDate = LocalDate.now(QUOTA_RESET_TIMEZONE);
        lastFailureTime.set(now);

        int currentFailures = failureCount.incrementAndGet();
        log.warn("Recorded rate limit failure #{} at {} PT", currentFailures, now);

        CircuitState currentState = circuitState.get();

        if (currentState == CircuitState.CLOSED && currentFailures >= FAILURE_THRESHOLD) {
            // Immediately open the circuit for the rest of the Pacific day
            if (circuitState.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                circuitOpenDate.set(nowPacificDate);
                log.error("Circuit breaker OPENED due to rate limit (429) - blocking ALL authenticated API calls until next Pacific day (quota reset). Date: {}",
                    nowPacificDate);
            }
        }
    }
    
    /**
     * Records a rate limit failure for the unauthenticated Google Books tier.
     *
     * <p>Once triggered the fallback circuit opens for the remainder of the Pacific Time day,
     * ensuring we stop hammering the public API tier after quota exhaustion.</p>
     */
    public void recordFallbackRateLimitFailure() {
        LocalDateTime now = LocalDateTime.now(QUOTA_RESET_TIMEZONE);
        LocalDate nowPacificDate = LocalDate.now(QUOTA_RESET_TIMEZONE);
        fallbackLastFailureTime.set(now);

        int currentFailures = fallbackFailureCount.incrementAndGet();
        log.warn("Recorded fallback rate limit failure #{} at {} PT", currentFailures, now);

        CircuitState currentState = fallbackCircuitState.get();

        if (currentState == CircuitState.CLOSED && currentFailures >= FAILURE_THRESHOLD) {
            if (fallbackCircuitState.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                fallbackOpenDate.set(nowPacificDate);
                log.error("Fallback circuit OPENED due to rate limit (429) - blocking ALL unauthenticated API calls until next Pacific day. Date: {}",
                    nowPacificDate);
            }
        }
    }
    
    /**
     * Record a general API failure (non-rate-limit).
     * General failures are logged but do NOT trigger circuit breaker.
     * Only rate limit (429) errors trigger the circuit breaker.
     */
    public void recordGeneralFailure() {
        LocalDateTime now = LocalDateTime.now(QUOTA_RESET_TIMEZONE);
        lastFailureTime.set(now);

        // Log but don't open circuit - only 429 errors should trigger the breaker
        log.debug("Recorded general API failure at {} PT (does not affect circuit breaker)", now);
    }

    /**
     * Record a general failure for the unauthenticated fallback tier.
     *
     * <p>This keeps telemetry for the fallback path in sync without opening the circuit,
     * mirroring {@link #recordGeneralFailure()} for paid-key traffic.</p>
     */
    public void recordFallbackGeneralFailure() {
        LocalDateTime now = LocalDateTime.now(QUOTA_RESET_TIMEZONE);
        fallbackLastFailureTime.set(now);

        log.debug("Recorded fallback general API failure at {} PT (does not affect fallback circuit breaker)", now);
    }
    
    /**
     * Get current circuit breaker status for monitoring/debugging.
     *
     * @return human-readable status string with state, failures, and reset timing
     */
    public String getCircuitStatus() {
        CircuitState state = circuitState.get();
        int failures = failureCount.get();
        LocalDateTime lastFailure = lastFailureTime.get();
        LocalDate openDate = circuitOpenDate.get();
        LocalDate nowPacificDate = LocalDate.now(QUOTA_RESET_TIMEZONE);

        StringBuilder status = new StringBuilder();
        status.append("Circuit State: ").append(state);
        status.append(", Failures: ").append(failures);

        if (lastFailure != null) {
            status.append(", Last Failure: ").append(lastFailure).append(" PT");
        }

        if (openDate != null) {
            status.append(", Open Since PT Date: ").append(openDate);
            status.append(", Current PT Date: ").append(nowPacificDate);
            if (state == CircuitState.OPEN && !nowPacificDate.isAfter(openDate)) {
                status.append(" (Will reset at next Pacific midnight)");
            }
        }

        CircuitState fallbackState = fallbackCircuitState.get();
        int fallbackFailures = fallbackFailureCount.get();
        LocalDateTime fallbackLastFailure = fallbackLastFailureTime.get();
        LocalDate fallbackDate = fallbackOpenDate.get();

        status.append(" | Fallback State: ").append(fallbackState);
        status.append(", Fallback Failures: ").append(fallbackFailures);
        if (fallbackLastFailure != null) {
            status.append(", Fallback Last Failure: ").append(fallbackLastFailure).append(" PT");
        }
        if (fallbackDate != null) {
            status.append(", Fallback Open Since PT Date: ").append(fallbackDate);
            if (fallbackState == CircuitState.OPEN && !nowPacificDate.isAfter(fallbackDate)) {
                status.append(" (Fallback resets at next Pacific midnight)");
            }
        }

        return status.toString();
    }
    
    /**
     * Manually reset the circuit breaker (for admin/testing purposes)
     */
    public void reset() {
        circuitState.set(CircuitState.CLOSED);
        failureCount.set(0);
        lastFailureTime.set(null);
        circuitOpenDate.set(null);
        fallbackCircuitState.set(CircuitState.CLOSED);
        fallbackFailureCount.set(0);
        fallbackLastFailureTime.set(null);
        fallbackOpenDate.set(null);
        log.info("Circuit breaker manually reset to CLOSED state");
    }
}
