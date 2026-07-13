/**
 * Centralizes runtime guards for Google Books API usage.
 *
 * <p>The service keeps authenticated and unauthenticated quota guards open through the current
 * Pacific Time day after a rate-limit response.</p>
 */
package net.findmybook.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Keeps each Google Books quota guard internally consistent while requests arrive concurrently.
 */
@Slf4j
@Service
public class ApiCircuitBreakerService {

    private static final ZoneId QUOTA_RESET_TIMEZONE = ZoneId.of("America/Los_Angeles");
    private static final int FAILURE_THRESHOLD = 1;

    private final AtomicReference<CircuitSnapshot> authenticatedCircuit =
            new AtomicReference<>(CircuitSnapshot.closed());
    private final AtomicReference<CircuitSnapshot> fallbackCircuit =
            new AtomicReference<>(CircuitSnapshot.closed());

    /**
     * Checks whether authenticated Google Books API calls are allowed.
     *
     * @return {@code true} when the authenticated circuit is closed
     */
    public boolean isApiCallAllowed() {
        return isApiCallAllowed(LocalDate.now(QUOTA_RESET_TIMEZONE));
    }

    boolean isApiCallAllowed(LocalDate nowPacific) {
        return isCircuitCallAllowed(
                authenticatedCircuit,
                nowPacific,
                "Circuit breaker AUTO-RESET at Pacific midnight - quota refresh detected. State: CLOSED",
                "Circuit breaker is OPEN - blocking API call until Pacific midnight"
        );
    }

    /**
     * Determines whether unauthenticated fallback calls are permitted.
     *
     * @return {@code true} when the fallback circuit is closed
     */
    public boolean isFallbackAllowed() {
        return isFallbackAllowed(LocalDate.now(QUOTA_RESET_TIMEZONE));
    }

    boolean isFallbackAllowed(LocalDate nowPacific) {
        return isCircuitCallAllowed(
                fallbackCircuit,
                nowPacific,
                "Fallback circuit AUTO-RESET at Pacific midnight - state: CLOSED",
                "Fallback circuit is OPEN - blocking unauthenticated calls until Pacific midnight"
        );
    }

    /**
     * Records a successful authenticated Google Books API call.
     */
    public void recordSuccess() {
        resetFailuresWhenClosed(authenticatedCircuit);
    }

    /**
     * Records an authenticated Google Books API rate-limit response.
     */
    public void recordRateLimitFailure() {
        recordRateLimitFailure(LocalDateTime.now(QUOTA_RESET_TIMEZONE));
    }

    void recordRateLimitFailure(LocalDateTime failureTime) {
        recordRateLimitFailure(
                authenticatedCircuit,
                failureTime,
                "Recorded rate limit failure #{} at {} PT",
                "Circuit breaker OPENED due to rate limit (429) - blocking ALL authenticated API calls until next Pacific day (quota reset). Date: {}",
                CircuitOpenLogLevel.ERROR
        );
    }

    /**
     * Records an unauthenticated Google Books fallback rate-limit response.
     */
    public void recordFallbackRateLimitFailure() {
        recordFallbackRateLimitFailure(LocalDateTime.now(QUOTA_RESET_TIMEZONE));
    }

    void recordFallbackRateLimitFailure(LocalDateTime failureTime) {
        recordRateLimitFailure(
                fallbackCircuit,
                failureTime,
                "Recorded fallback rate limit failure #{} at {} PT",
                "Fallback circuit OPENED due to rate limit (429) - blocking ALL unauthenticated API calls until next Pacific day. Date: {}",
                CircuitOpenLogLevel.WARN
        );
    }

    /**
     * Records a non-rate-limit failure without opening the authenticated circuit.
     */
    public void recordGeneralFailure() {
        LocalDateTime failureTime = LocalDateTime.now(QUOTA_RESET_TIMEZONE);
        authenticatedCircuit.updateAndGet(snapshot -> snapshot.withLastFailure(failureTime));
        log.debug("Recorded general API failure at {} PT (does not affect circuit breaker)", failureTime);
    }

    /**
     * Records a non-rate-limit failure without opening the fallback circuit.
     */
    public void recordFallbackGeneralFailure() {
        LocalDateTime failureTime = LocalDateTime.now(QUOTA_RESET_TIMEZONE);
        fallbackCircuit.updateAndGet(snapshot -> snapshot.withLastFailure(failureTime));
        log.debug("Recorded fallback general API failure at {} PT (does not affect fallback circuit breaker)", failureTime);
    }

    /**
     * Returns a human-readable snapshot for operational diagnostics.
     *
     * @return authenticated and fallback circuit state
     */
    public String getCircuitStatus() {
        CircuitSnapshot authenticated = authenticatedCircuit.get();
        CircuitSnapshot fallback = fallbackCircuit.get();
        LocalDate nowPacificDate = LocalDate.now(QUOTA_RESET_TIMEZONE);

        StringBuilder status = new StringBuilder();
        appendCircuitStatus(status, "Circuit State", "Failures", "Last Failure", "Open Since PT Date", authenticated, nowPacificDate);
        status.append(" | ");
        appendCircuitStatus(
                status,
                "Fallback State",
                "Fallback Failures",
                "Fallback Last Failure",
                "Fallback Open Since PT Date",
                fallback,
                nowPacificDate
        );
        return status.toString();
    }

    /**
     * Manually resets both circuit guards for operational recovery.
     */
    public void reset() {
        authenticatedCircuit.set(CircuitSnapshot.closed());
        fallbackCircuit.set(CircuitSnapshot.closed());
        log.info("Circuit breaker manually reset to CLOSED state");
    }

    private boolean isCircuitCallAllowed(AtomicReference<CircuitSnapshot> circuit,
                                         LocalDate nowPacific,
                                         String resetMessage,
                                         String openMessage) {
        Objects.requireNonNull(nowPacific, "nowPacific must not be null");
        while (true) {
            CircuitSnapshot snapshot = circuit.get();
            if (snapshot.state() == CircuitState.CLOSED) {
                return true;
            }
            if (snapshot.canResetOn(nowPacific)) {
                if (circuit.compareAndSet(snapshot, CircuitSnapshot.closed())) {
                    log.info(resetMessage);
                    return true;
                }
                continue;
            }
            log.debug(openMessage);
            return false;
        }
    }

    private void resetFailuresWhenClosed(AtomicReference<CircuitSnapshot> circuit) {
        while (true) {
            CircuitSnapshot snapshot = circuit.get();
            if (snapshot.state() == CircuitState.OPEN) {
                return;
            }
            if (circuit.compareAndSet(snapshot, CircuitSnapshot.closed())) {
                return;
            }
        }
    }

    private void recordRateLimitFailure(AtomicReference<CircuitSnapshot> circuit,
                                        LocalDateTime failureTime,
                                        String failureMessage,
                                        String openedMessage,
                                        CircuitOpenLogLevel openLogLevel) {
        Objects.requireNonNull(failureTime, "failureTime must not be null");
        while (true) {
            CircuitSnapshot previous = circuit.get();
            CircuitSnapshot updated = previous.withRateLimitFailure(failureTime);
            if (!circuit.compareAndSet(previous, updated)) {
                continue;
            }
            log.warn(failureMessage, updated.failureCount(), failureTime);
            if (updated.openedOnNewDateComparedTo(previous)) {
                switch (openLogLevel) {
                    case WARN -> log.warn(openedMessage, updated.openDate());
                    case ERROR -> log.error(openedMessage, updated.openDate());
                }
            }
            return;
        }
    }

    private void appendCircuitStatus(StringBuilder status,
                                     String stateLabel,
                                     String failuresLabel,
                                     String lastFailureLabel,
                                     String openDateLabel,
                                     CircuitSnapshot snapshot,
                                     LocalDate nowPacificDate) {
        status.append(stateLabel).append(": ").append(snapshot.state());
        status.append(", ").append(failuresLabel).append(": ").append(snapshot.failureCount());
        if (snapshot.lastFailureTime() != null) {
            status.append(", ").append(lastFailureLabel).append(": ").append(snapshot.lastFailureTime()).append(" PT");
        }
        if (snapshot.openDate() != null) {
            status.append(", ").append(openDateLabel).append(": ").append(snapshot.openDate());
            status.append(", Current PT Date: ").append(nowPacificDate);
            if (snapshot.state() == CircuitState.OPEN && !nowPacificDate.isAfter(snapshot.openDate())) {
                status.append(" (Will reset at next Pacific midnight)");
            }
        }
    }

    private enum CircuitState {
        CLOSED,
        OPEN
    }

    private enum CircuitOpenLogLevel {
        WARN,
        ERROR
    }

    private record CircuitSnapshot(CircuitState state,
                                   int failureCount,
                                   LocalDateTime lastFailureTime,
                                   LocalDate openDate) {

        private static CircuitSnapshot closed() {
            return new CircuitSnapshot(CircuitState.CLOSED, 0, null, null);
        }

        private boolean canResetOn(LocalDate nowPacific) {
            return state == CircuitState.OPEN && openDate != null && nowPacific.isAfter(openDate);
        }

        private CircuitSnapshot withLastFailure(LocalDateTime failureTime) {
            return new CircuitSnapshot(state, failureCount, failureTime, openDate);
        }

        private CircuitSnapshot withRateLimitFailure(LocalDateTime failureTime) {
            LocalDate failureDate = failureTime.toLocalDate();
            int updatedFailureCount = failureCount + 1;
            boolean opensFromClosed = state == CircuitState.CLOSED && updatedFailureCount >= FAILURE_THRESHOLD;
            boolean rollsOpenDateForward = state == CircuitState.OPEN
                    && (openDate == null || failureDate.isAfter(openDate));
            if (opensFromClosed || rollsOpenDateForward) {
                int failuresForOpenDate = rollsOpenDateForward ? FAILURE_THRESHOLD : updatedFailureCount;
                return new CircuitSnapshot(CircuitState.OPEN, failuresForOpenDate, failureTime, failureDate);
            }
            return new CircuitSnapshot(state, updatedFailureCount, failureTime, openDate);
        }

        private boolean openedOnNewDateComparedTo(CircuitSnapshot previous) {
            return state == CircuitState.OPEN && !Objects.equals(openDate, previous.openDate);
        }
    }
}
