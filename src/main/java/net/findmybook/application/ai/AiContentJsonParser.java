package net.findmybook.application.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.findmybook.domain.ai.BookAiContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses raw LLM response text into a structured {@link BookAiContent} record.
 *
 * <p>Accepts only the canonical JSON object contract requested from the provider,
 * either raw or in one permitted whole-response Markdown fence, so malformed or
 * drifted responses are rejected and retried by the calling service.
 */
class AiContentJsonParser {

    private static final Logger log = LoggerFactory.getLogger(AiContentJsonParser.class);
    private static final int MAX_KEY_THEME_COUNT = 6;
    private static final int MAX_TAKEAWAY_COUNT = 5;
    private static final String CODE_FENCE_DELIMITER = "```";
    private static final String JSON_FENCE_LANGUAGE = "json";
    private static final Pattern WHOLE_RESPONSE_JSON_FENCE = Pattern.compile(
        "\\A" + CODE_FENCE_DELIMITER + "(?:" + JSON_FENCE_LANGUAGE + ")?\\R(?<payload>.*)\\R"
            + CODE_FENCE_DELIMITER + "\\z",
        Pattern.DOTALL
    );
    private static final Set<String> CANONICAL_TOP_LEVEL_FIELDS = Arrays.stream(BookAiContent.class.getRecordComponents())
        .map(recordComponent -> recordComponent.getName())
        .collect(Collectors.toUnmodifiableSet());
    private final ObjectMapper objectMapper;

    AiContentJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses AI response text into structured book content.
     *
     * <p>The returned {@link BookAiContent} always has a non-null {@code summary}
     * and {@code keyThemes} list. Every canonical top-level key is required in
     * the response. The optional text values {@code readerFit} and {@code context}
     * may be JSON {@code null}; the nullable {@code takeaways} domain field may
     * also be JSON {@code null}, while an empty array remains valid.</p>
     *
     * @param responseText raw LLM JSON output, optionally enclosed in one whole-response
     *                     {@code ```} or {@code ```json} fence
     * @return parsed content with nullable optional fields per {@link BookAiContent}
     * @throws IllegalStateException if the response violates the canonical JSON contract
     */
    BookAiContent parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            throw new IllegalStateException("AI content response was empty");
        }

        JsonNode payload = parseJsonPayload(responseText);
        validateExactTopLevelKeys(payload);
        String summary = requiredText(payload, "summary");
        Optional<String> readerFit = optionalText(payload, "readerFit");
        List<String> themes = requiredStringList(payload, MAX_KEY_THEME_COUNT, "keyThemes");
        List<String> takeaways = nullableStringList(payload, MAX_TAKEAWAY_COUNT, "takeaways");
        Optional<String> context = optionalText(payload, "context");

        if (themes.isEmpty() && takeaways.isEmpty()) {
            log.warn("AI generated content with no themes and no takeaways - likely insufficient source material");
        }

        // orElse(null) at record boundary: BookAiContent uses @Nullable fields for JSON serialization
        BookAiContent content = new BookAiContent(
            summary, readerFit.orElse(null), themes,
            takeaways.isEmpty() ? null : takeaways, context.orElse(null));
        AiContentQualityValidator.validate(content);
        return content;
    }

    private JsonNode parseJsonPayload(String responseText) {
        String jsonPayload = extractCanonicalJsonPayload(responseText);
        try {
            JsonNode payload = objectMapper
                .reader(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(jsonPayload);
            if (!payload.isObject()) {
                throw new IllegalStateException("AI response must be a JSON object");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new IllegalStateException("AI response did not include a valid JSON object", exception);
        }
    }

    private String extractCanonicalJsonPayload(String responseText) {
        String trimmedResponse = responseText.trim();
        if (!trimmedResponse.startsWith(CODE_FENCE_DELIMITER)) {
            return trimmedResponse;
        }

        Matcher fence = WHOLE_RESPONSE_JSON_FENCE.matcher(trimmedResponse);
        if (!fence.matches()) {
            throw new IllegalStateException(
                "AI response must be a JSON object or one whole-response ``` or ```json fenced JSON block"
            );
        }
        return fence.group("payload");
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode fieldNode = payload.path(field);
        if (!fieldNode.isString()) {
            throw invalidFieldType(field, "a nonblank JSON string");
        }
        String text = fieldNode.stringValue();
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("AI response field must be nonblank: " + field);
        }
        return text.trim();
    }

    private Optional<String> optionalText(JsonNode payload, String field) {
        JsonNode fieldNode = payload.path(field);
        if (fieldNode.isNull()) {
            return Optional.empty();
        }
        if (!fieldNode.isString()) {
            throw invalidFieldType(field, "a JSON string or null");
        }
        String text = fieldNode.stringValue();
        return StringUtils.hasText(text) ? Optional.of(text.trim()) : Optional.empty();
    }

    private List<String> requiredStringList(JsonNode payload, int maxSize, String field) {
        return stringList(payload.path(field), maxSize, field);
    }

    private List<String> nullableStringList(JsonNode payload, int maxSize, String field) {
        JsonNode fieldNode = payload.path(field);
        if (fieldNode.isNull()) {
            return List.of();
        }
        return stringList(fieldNode, maxSize, field);
    }

    private List<String> stringList(JsonNode fieldNode, int maxSize, String field) {
        if (!fieldNode.isArray()) {
            throw invalidFieldType(field, "an array of JSON strings");
        }
        List<String> values = new ArrayList<>(fieldNode.size());
        for (int index = 0; index < fieldNode.size(); index++) {
            JsonNode elementNode = fieldNode.get(index);
            if (!elementNode.isString()) {
                throw invalidFieldType("%s[%d]".formatted(field, index), "a nonblank JSON string");
            }
            String text = elementNode.stringValue();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            values.add(text.trim());
            if (values.size() > maxSize) {
                throw new IllegalStateException(
                    "AI response field exceeds maximum item count for %s: %d > %d"
                        .formatted(field, values.size(), maxSize)
                );
            }
        }
        return List.copyOf(values);
    }

    private void validateExactTopLevelKeys(JsonNode payload) {
        payload.properties().forEach(entry -> {
            if (!CANONICAL_TOP_LEVEL_FIELDS.contains(entry.getKey())) {
                throw new IllegalStateException("AI response contains unknown field: " + entry.getKey());
            }
        });
        for (String field : CANONICAL_TOP_LEVEL_FIELDS) {
            if (!payload.has(field)) {
                throw new IllegalStateException("AI response missing required field: " + field);
            }
        }
    }

    private IllegalStateException invalidFieldType(String field, String expectedType) {
        return new IllegalStateException(
            "AI response field %s must be %s".formatted(field, expectedType)
        );
    }
}
