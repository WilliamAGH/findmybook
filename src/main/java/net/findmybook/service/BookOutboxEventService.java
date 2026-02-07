package net.findmybook.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.findmybook.service.event.BookUpsertEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Persists transactional outbox payloads and emits in-process upsert events for books.
 */
@Service
@Slf4j
public class BookOutboxEventService {

    private static final String OUTBOX_TOPIC_PREFIX = "/topic/book.";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public BookOutboxEventService(JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Captures all fields needed to emit a book upsert outbox event and in-process notification.
     */
    public record BookUpsertRequest(
        UUID bookId,
        String slug,
        String title,
        boolean isNew,
        @Nullable String context,
        @Nullable String canonicalImageUrl,
        @Nullable Map<String, String> imageLinks,
        @Nullable String source
    ) {}

    /**
     * Writes the outbox message and publishes an application event for the same payload.
     * This method is fail-fast to avoid silent event loss.
     */
    public void emitBookUpsert(BookUpsertRequest request) {
        String payloadJson = toPayloadJson(request);
        jdbcTemplate.update(
            "INSERT INTO events_outbox (topic, payload, created_at) VALUES (?, ?::jsonb, NOW())",
            OUTBOX_TOPIC_PREFIX + request.bookId(),
            payloadJson
        );

        eventPublisher.publishEvent(new BookUpsertEvent(
            request.bookId().toString(),
            request.slug(),
            request.title(),
            request.isNew(),
            request.context(),
            request.imageLinks(),
            request.canonicalImageUrl(),
            request.source()
        ));

        log.debug("Persisted and published book upsert events for {}", request.bookId());
    }

    /**
     * Typed representation of the outbox JSON payload, replacing untyped Map construction.
     * Null fields are omitted from the serialized JSON via {@link JsonInclude}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OutboxPayload(
        String bookId,
        String slug,
        String title,
        boolean isNew,
        long timestamp,
        @Nullable String context,
        @Nullable String canonicalImageUrl,
        @Nullable Map<String, String> imageLinks,
        @Nullable String source
    ) {}

    private String toPayloadJson(BookUpsertRequest request) {
        var payload = new OutboxPayload(
            request.bookId().toString(),
            request.slug() != null ? request.slug() : "",
            request.title() != null ? request.title() : "",
            request.isNew(),
            System.currentTimeMillis(),
            nonBlankOrNull(request.context()),
            nonBlankOrNull(request.canonicalImageUrl()),
            (request.imageLinks() != null && !request.imageLinks().isEmpty()) ? request.imageLinks() : null,
            nonBlankOrNull(request.source())
        );

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            log.error("Failed to serialize outbox payload for book {}: {}", request.bookId(), exception.getMessage(), exception);
            throw new IllegalStateException("Unable to serialize book upsert outbox payload for " + request.bookId(), exception);
        }
    }

    private static String nonBlankOrNull(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }
}
