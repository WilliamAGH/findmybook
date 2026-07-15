package net.findmybook.service;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Relays committed transactional-outbox events to WebSocket clients and marks
 * successful deliveries as sent.
 *
 * <p>Publish failures remain pending for retry. Database failures pause polling
 * with bounded exponential backoff so one outage remains visible without making
 * Spring's scheduler log the same exception again.</p>
 */
@Service
@Slf4j
public class OutboxRelay {

    private final JdbcTemplate jdbcTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    private static final int BATCH_SIZE = 100;
    private static final long PROCESS_INTERVAL_MS = 1000;
    private static final long INITIAL_DATABASE_RETRY_DELAY_MS = 5_000;
    private static final long MAX_DATABASE_RETRY_DELAY_MS = 60_000;
    private static final int MAX_DATABASE_RETRY_SHIFT = 4;

    private final Clock clock;
    private int consecutiveDatabaseFailures;
    private Instant nextDatabaseAttemptAt = Instant.MIN;

    /**
     * Creates the scheduled relay with a system UTC clock for database retry timing.
     *
     * @param jdbcTemplate transactional outbox database access
     * @param messagingTemplate WebSocket publisher
     */
    @Autowired
    public OutboxRelay(JdbcTemplate jdbcTemplate, SimpMessagingTemplate messagingTemplate) {
        this(jdbcTemplate, messagingTemplate, Clock.systemUTC());
    }

    OutboxRelay(JdbcTemplate jdbcTemplate, SimpMessagingTemplate messagingTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.messagingTemplate = messagingTemplate;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Process outbox events and relay to WebSocket.
     * <p>
     * Runs every 1 second via @Scheduled and executes sequentially.
     * Processes up to 100 events per batch.
     * <p>
     * For each event:
     * 1. Fetch from events_outbox WHERE sent_at IS NULL
     * 2. Publish to WebSocket topic
     * 3. Mark as sent (UPDATE sent_at = NOW())
     * <p>
     * If publish fails, event remains unsent and will be retried.
     */
    @Scheduled(fixedDelay = PROCESS_INTERVAL_MS)
    public void relayEvents() {
        Instant pollStartedAt = clock.instant();
        if (pollStartedAt.isBefore(nextDatabaseAttemptAt)) {
            return;
        }

        try {
            relayAvailableEvents();
            recordDatabaseRecovery();
        } catch (DataAccessException databaseFailure) {
            recordDatabaseFailure(pollStartedAt, databaseFailure);
        }
    }

    private void relayAvailableEvents() {
        List<OutboxEvent> events = fetchUnsentEvents(BATCH_SIZE);

        if (events.isEmpty()) {
            return;
        }

        log.debug("Relaying {} outbox events to WebSocket", events.size());
        int clusterEventsRelayed = 0;

        for (OutboxEvent event : events) {
            try {
                // Publish to WebSocket
                messagingTemplate.convertAndSend(event.getTopic(), event.getPayload());
            } catch (MessagingException | IllegalArgumentException | IllegalStateException ex) {
                log.warn("Failed to relay event {} to topic {}: {}",
                    event.getEventId(),
                    event.getTopic(),
                    ex.getMessage()
                );

                incrementRetryCount(event.getEventId());
                continue;
            }

            // Mark as sent
            markSent(event.getEventId());

            if (event.getTopic() != null && event.getTopic().startsWith("/topic/cluster.")) {
                clusterEventsRelayed++;
            }
            log.debug("Relayed event {} to topic {}", event.getEventId(), event.getTopic());
        }

        if (clusterEventsRelayed > 0) {
            log.info("Relayed {} work-cluster primary change event(s) this cycle", clusterEventsRelayed);
        }
    }

    private void recordDatabaseFailure(Instant pollStartedAt, DataAccessException databaseFailure) {
        consecutiveDatabaseFailures++;
        long retryDelayMillis = Math.min(
            INITIAL_DATABASE_RETRY_DELAY_MS << Math.min(consecutiveDatabaseFailures - 1, MAX_DATABASE_RETRY_SHIFT),
            MAX_DATABASE_RETRY_DELAY_MS
        );
        nextDatabaseAttemptAt = pollStartedAt.plus(Duration.ofMillis(retryDelayMillis));

        if (consecutiveDatabaseFailures == 1) {
            log.error(
                "Outbox relay database access failed; pausing polls for {} ms",
                retryDelayMillis,
                databaseFailure
            );
            return;
        }
        if (retryDelayMillis == MAX_DATABASE_RETRY_DELAY_MS) {
            log.error(
                "Outbox relay database access still unavailable after {} attempts; next retry in {} ms: {}",
                consecutiveDatabaseFailures,
                retryDelayMillis,
                databaseFailure.getMostSpecificCause().getMessage()
            );
            return;
        }
        log.warn(
            "Outbox relay database access still unavailable after {} attempts; next retry in {} ms: {}",
            consecutiveDatabaseFailures,
            retryDelayMillis,
            databaseFailure.getMostSpecificCause().getMessage()
        );
    }

    private void recordDatabaseRecovery() {
        if (consecutiveDatabaseFailures == 0) {
            return;
        }
        log.info("Outbox relay database access recovered after {} failed attempts", consecutiveDatabaseFailures);
        consecutiveDatabaseFailures = 0;
        nextDatabaseAttemptAt = Instant.MIN;
    }

    /**
     * Fetch unsent events ordered by creation time.
     */
    private List<OutboxEvent> fetchUnsentEvents(int limit) {
        try {
            return jdbcTemplate.query(
                """
                SELECT event_id, topic, payload, retry_count
                FROM events_outbox
                WHERE sent_at IS NULL
                ORDER BY retry_count ASC, created_at ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new OutboxEvent(
                    (UUID) rs.getObject("event_id"),
                    rs.getString("topic"),
                    rs.getString("payload"),
                    rs.getInt("retry_count")
                ),
                limit
            );
        } catch (DataAccessException databaseFailure) {
            throw new OutboxPersistenceException("Failed to fetch unsent outbox events", databaseFailure);
        }
    }

    /**
     * Mark event as successfully sent.
     */
    private void markSent(UUID eventId) {
        try {
            jdbcTemplate.update(
                "UPDATE events_outbox SET sent_at = NOW() WHERE event_id = ?",
                eventId
            );
        } catch (DataAccessException databaseFailure) {
            throw new OutboxPersistenceException(
                "Failed to mark outbox event " + eventId + " as sent",
                databaseFailure
            );
        }
    }

    /**
     * Increment retry count for failed event.
     * Events with retry_count > 10 might need manual intervention.
     */
    private void incrementRetryCount(UUID eventId) {
        try {
            jdbcTemplate.update(
                "UPDATE events_outbox SET retry_count = retry_count + 1 WHERE event_id = ?",
                eventId
            );
        } catch (DataAccessException databaseFailure) {
            throw new OutboxPersistenceException(
                "Failed to increment retry count for outbox event " + eventId,
                databaseFailure
            );
        }
    }

    /**
     * Get outbox statistics.
     * Useful for monitoring and alerting.
     */
    public OutboxStats getOutboxStats() {
        try {
            return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) FILTER (WHERE sent_at IS NULL) as unsent,
                    COUNT(*) FILTER (WHERE sent_at IS NOT NULL) as sent,
                    COUNT(*) FILTER (WHERE sent_at IS NULL AND retry_count > 5) as stuck,
                    COUNT(*) FILTER (WHERE sent_at IS NULL AND topic LIKE '/topic/cluster.%') as cluster_unsent,
                    COUNT(*) FILTER (WHERE sent_at IS NOT NULL AND topic LIKE '/topic/cluster.%') as cluster_sent
                FROM events_outbox
                """,
                (rs, rowNum) -> new OutboxStats(
                    rs.getInt("unsent"),
                    rs.getInt("sent"),
                    rs.getInt("stuck"),
                    rs.getInt("cluster_unsent"),
                    rs.getInt("cluster_sent")
                )
            );
        } catch (DataAccessException ex) {
            log.error("Failed to fetch outbox stats", ex);
            throw new IllegalStateException("Failed to fetch outbox stats", ex);
        }
    }

    /**
     * Clean up expired sent events.
     * Call periodically to prevent table bloat.
     * Keeps last 7 days of events for debugging.
     */
    public int cleanupExpiredEvents() {
        try {
            return jdbcTemplate.update(
                "DELETE FROM events_outbox WHERE sent_at < NOW() - INTERVAL '7 days'"
            );
        } catch (DataAccessException ex) {
            log.error("Failed to clean up outbox events", ex);
            throw new IllegalStateException("Failed to clean up outbox events", ex);
        }
    }

    /**
     * Retry stuck events (retry_count > 5).
     * Resets retry count to 0 for manual intervention.
     */
    public int retryStuckEvents() {
        try {
            return jdbcTemplate.update(
                "UPDATE events_outbox SET retry_count = 0 WHERE sent_at IS NULL AND retry_count > 5"
            );
        } catch (DataAccessException ex) {
            log.error("Failed to reset retry count for stuck outbox events", ex);
            throw new IllegalStateException("Failed to reset retry count for stuck outbox events", ex);
        }
    }

    private static final class OutboxPersistenceException extends DataAccessException {
        private OutboxPersistenceException(String message, DataAccessException cause) {
            super(message, cause);
        }
    }

    /**
     * Outbox event data.
     */
    @Value
    private static class OutboxEvent {
        UUID eventId;
        String topic;
        String payload;
        int retryCount;
    }

    /**
     * Outbox statistics for monitoring.
     */
    public record OutboxStats(
        int unsent,
        int sent,
        int stuck,
        int clusterUnsent,
        int clusterSent
    ) {}
}
