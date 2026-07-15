package net.findmybook.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant OUTAGE_STARTED_AT = Instant.parse("2026-07-15T00:00:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private Clock clock;

    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(OUTAGE_STARTED_AT);
        outboxRelay = new OutboxRelay(jdbcTemplate, messagingTemplate, clock);
    }

    @Test
    void should_IncrementRetryCount_When_MessagingPublishFails() {
        UUID eventId = UUID.randomUUID();
        stubSingleUnsentEvent(eventId, "/topic/book." + eventId, "{\"bookId\":\"" + eventId + "\"}");

        doThrow(new MessagingException("websocket offline"))
            .when(messagingTemplate)
            .convertAndSend(anyString(), anyString());

        when(jdbcTemplate.update(
            "UPDATE events_outbox SET retry_count = retry_count + 1 WHERE event_id = ?",
            eventId
        )).thenReturn(1);

        List<ILoggingEvent> logEvents = captureRelayLogs(outboxRelay::relayEvents);

        verify(jdbcTemplate).update(
            "UPDATE events_outbox SET retry_count = retry_count + 1 WHERE event_id = ?",
            eventId
        );
        verify(jdbcTemplate, never()).update(
            "UPDATE events_outbox SET sent_at = NOW() WHERE event_id = ?",
            eventId
        );
        assertThat(logEvents)
            .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .extracting(ILoggingEvent::getLevel)
            .containsExactly(Level.WARN);
    }

    @Test
    void should_BackOffWithoutEscapingScheduledBoundary_When_MarkSentPersistenceFails() {
        UUID eventId = UUID.randomUUID();
        stubSingleUnsentEvent(eventId, "/topic/book." + eventId, "{\"bookId\":\"" + eventId + "\"}");

        when(jdbcTemplate.update(
            "UPDATE events_outbox SET sent_at = NOW() WHERE event_id = ?",
            eventId
        )).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        List<ILoggingEvent> logEvents = captureRelayLogs(() -> assertDoesNotThrow(outboxRelay::relayEvents));

        verify(jdbcTemplate, never()).update(
            "UPDATE events_outbox SET retry_count = retry_count + 1 WHERE event_id = ?",
            eventId
        );
        assertThat(logEvents)
            .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .extracting(ILoggingEvent::getLevel)
            .containsExactly(Level.ERROR);
        ILoggingEvent errorEvent = logEvents.stream()
            .filter(event -> event.getLevel() == Level.ERROR)
            .findFirst()
            .orElseThrow();
        assertThat(errorEvent.getThrowableProxy().getMessage()).contains(eventId.toString());
    }

    @Test
    void should_LogOneErrorThenBackOffToBoundAndRecover_When_DatabaseRemainsUnavailable() {
        DataAccessResourceFailureException outage =
            new DataAccessResourceFailureException("database unavailable");
        when(jdbcTemplate.query(
            ArgumentMatchers.contains("FROM events_outbox"),
            ArgumentMatchers.<RowMapper<Object>>any(),
            anyInt()
        )).thenThrow(outage)
            .thenThrow(outage)
            .thenThrow(outage)
            .thenThrow(outage)
            .thenThrow(outage)
            .thenThrow(outage)
            .thenReturn(List.of());

        List<ILoggingEvent> logEvents = captureRelayLogs(() -> {
            assertDoesNotThrow(outboxRelay::relayEvents);
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(5));
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(14));
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(15));
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(35));
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(75));
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(135));
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(194));
            assertDoesNotThrow(outboxRelay::relayEvents);

            when(clock.instant()).thenReturn(OUTAGE_STARTED_AT.plusSeconds(195));
            assertDoesNotThrow(outboxRelay::relayEvents);
        });

        verify(jdbcTemplate, times(7)).query(
            ArgumentMatchers.contains("FROM events_outbox"),
            ArgumentMatchers.<RowMapper<Object>>any(),
            anyInt()
        );
        assertThat(logEvents).extracting(ILoggingEvent::getLevel)
            .containsExactly(
                Level.ERROR,
                Level.WARN,
                Level.WARN,
                Level.WARN,
                Level.ERROR,
                Level.ERROR,
                Level.INFO
            );
        assertThat(logEvents.get(0).getFormattedMessage()).contains("pausing polls for 5000 ms");
        assertThat(logEvents.get(1).getFormattedMessage()).contains("next retry in 10000 ms");
        assertThat(logEvents.get(4).getFormattedMessage()).contains("next retry in 60000 ms");
        assertThat(logEvents.get(5).getFormattedMessage()).contains("next retry in 60000 ms");
        assertThat(logEvents.get(6).getFormattedMessage()).contains("recovered after 6 failed attempts");
    }

    private void stubSingleUnsentEvent(UUID eventId, String topic, String payload) {
        when(jdbcTemplate.query(
            ArgumentMatchers.contains("FROM events_outbox"),
            ArgumentMatchers.<RowMapper<Object>>any(),
            anyInt()
        )).thenAnswer(invocation -> {
            RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getObject("event_id")).thenReturn(eventId);
            when(resultSet.getString("topic")).thenReturn(topic);
            when(resultSet.getString("payload")).thenReturn(payload);
            when(resultSet.getInt("retry_count")).thenReturn(0);
            return List.of(mapRow(rowMapper, resultSet));
        });
    }

    private Object mapRow(RowMapper<Object> rowMapper, ResultSet resultSet) {
        try {
            return rowMapper.mapRow(resultSet, 0);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to map outbox test row", exception);
        }
    }

    private List<ILoggingEvent> captureRelayLogs(Runnable operation) {
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
        boolean originalAdditivity = relayLogger.isAdditive();
        Level originalLevel = relayLogger.getLevel();
        relayLogger.setAdditive(false);
        relayLogger.setLevel(Level.DEBUG);
        logEvents.start();
        relayLogger.addAppender(logEvents);

        try {
            operation.run();
            return List.copyOf(logEvents.list);
        } finally {
            relayLogger.detachAppender(logEvents);
            logEvents.stop();
            relayLogger.setAdditive(originalAdditivity);
            relayLogger.setLevel(originalLevel);
        }
    }
}
