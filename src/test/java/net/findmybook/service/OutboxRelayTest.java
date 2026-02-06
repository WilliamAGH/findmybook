package net.findmybook.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        outboxRelay = new OutboxRelay(jdbcTemplate, messagingTemplate);
    }

    @Test
    void relayEvents_shouldIncrementRetryCount_WhenMessagingPublishFails() {
        UUID eventId = UUID.randomUUID();
        stubSingleUnsentEvent(eventId, "/topic/book." + eventId, "{\"bookId\":\"" + eventId + "\"}");

        doThrow(new MessagingException("websocket offline"))
            .when(messagingTemplate)
            .convertAndSend(anyString(), anyString());

        when(jdbcTemplate.update(
            "UPDATE events_outbox SET retry_count = retry_count + 1 WHERE event_id = ?",
            eventId
        )).thenReturn(1);

        outboxRelay.relayEvents();

        verify(jdbcTemplate).update(
            "UPDATE events_outbox SET retry_count = retry_count + 1 WHERE event_id = ?",
            eventId
        );
        verify(jdbcTemplate, never()).update(
            "UPDATE events_outbox SET sent_at = NOW() WHERE event_id = ?",
            eventId
        );
    }

    @Test
    void relayEvents_shouldStopCycle_WhenMarkSentPersistenceFails() {
        UUID eventId = UUID.randomUUID();
        stubSingleUnsentEvent(eventId, "/topic/book." + eventId, "{\"bookId\":\"" + eventId + "\"}");

        when(jdbcTemplate.update(
            "UPDATE events_outbox SET sent_at = NOW() WHERE event_id = ?",
            eventId
        )).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> outboxRelay.relayEvents());

        verify(jdbcTemplate, never()).update(
            "UPDATE events_outbox SET retry_count = retry_count + 1 WHERE event_id = ?",
            eventId
        );
    }

    private void stubSingleUnsentEvent(UUID eventId, String topic, String payload) {
        when(jdbcTemplate.query(
            contains("FROM events_outbox"),
            any(RowMapper.class),
            anyInt()
        )).thenAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getObject(eq("event_id"))).thenReturn(eventId);
            when(resultSet.getString(eq("topic"))).thenReturn(topic);
            when(resultSet.getString(eq("payload"))).thenReturn(payload);
            when(resultSet.getInt(eq("retry_count"))).thenReturn(0);
            return List.of(mapRow(rowMapper, resultSet));
        });
    }

    private Object mapRow(RowMapper<?> rowMapper, ResultSet resultSet) {
        try {
            return rowMapper.mapRow(resultSet, 0);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to map outbox test row", exception);
        }
    }
}
