package net.findmybook.application.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.findmybook.domain.ai.BookAiContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses raw LLM response text into a structured {@link BookAiContent} record.
 *
 * <p>Handles markdown fences, brace extraction fallback, and field-alias
 * resolution so the calling service stays free of JSON-level concerns.
 */
class AiContentJsonParser {

    private static final Logger log = LoggerFactory.getLogger(AiContentJsonParser.class);
    private static final int MAX_KEY_THEME_COUNT = 6;
    private static final int MAX_TAKEAWAY_COUNT = 5;

    private final ObjectMapper objectMapper;

    AiContentJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses AI response text into structured book content.
     *
     * @param responseText raw LLM output (may include markdown fences)
     * @return parsed content record
     * @throws IllegalStateException if the response cannot be parsed as valid JSON
     */
    BookAiContent parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            throw new IllegalStateException("AI content response was empty");
        }

        JsonNode payload = parseJsonPayload(responseText);
        String summary = requiredText(payload, "summary");
        Optional<String> readerFit = optionalText(payload, "readerFit", "reader_fit", "idealReader");
        List<String> themes = stringList(payload, MAX_KEY_THEME_COUNT, "keyThemes", "key_themes", "themes");
        List<String> takeaways = stringList(payload, MAX_TAKEAWAY_COUNT, "takeaways");
        Optional<String> context = optionalText(payload, "context");

        if (themes.isEmpty() && takeaways.isEmpty()) {
            log.warn("AI generated content with no themes and no takeaways - likely insufficient source material");
        }

        // orElse(null) at record boundary: BookAiContent uses @Nullable fields for JSON serialization
        return new BookAiContent(
            summary, readerFit.orElse(null), themes,
            takeaways.isEmpty() ? null : takeaways, context.orElse(null));
    }

    private JsonNode parseJsonPayload(String responseText) {
        String cleaned = responseText.replace("```json", "").replace("```", "").trim();
        try {
            return objectMapper.readTree(cleaned);
        } catch (JacksonException initialParseException) {
            int openBrace = cleaned.indexOf('{');
            int closeBrace = cleaned.lastIndexOf('}');
            if (openBrace < 0 || closeBrace <= openBrace) {
                throw new IllegalStateException("AI response did not include a valid JSON object");
            }
            log.warn("AI response required brace extraction fallback (initial parse failed: {})", initialParseException.getMessage());
            String extracted = cleaned.substring(openBrace, closeBrace + 1);
            try {
                return objectMapper.readTree(extracted);
            } catch (JacksonException exception) {
                throw new IllegalStateException("AI response JSON parsing failed", exception);
            }
        }
    }

    private String requiredText(JsonNode payload, String field, String... aliases) {
        return optionalText(payload, field, aliases)
            .orElseThrow(() -> new IllegalStateException("AI response missing required field: " + field));
    }

    private Optional<String> optionalText(JsonNode payload, String field, String... aliases) {
        return resolveJsonNode(payload, field, aliases)
            .map(node -> node.asString(null))
            .filter(StringUtils::hasText)
            .map(String::trim);
    }

    private List<String> stringList(JsonNode payload, int maxSize, String field, String... aliases) {
        Optional<JsonNode> node = resolveJsonNode(payload, field, aliases);
        if (node.isEmpty() || !node.get().isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode elementNode : node.get()) {
            if (elementNode == null || elementNode.isNull()) {
                continue;
            }
            String text = elementNode.asString(null);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            values.add(text.trim());
            if (values.size() == maxSize) {
                break;
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private Optional<JsonNode> resolveJsonNode(JsonNode payload, String field, String... aliases) {
        JsonNode node = payload.get(field);
        if (node != null && !node.isNull()) {
            return Optional.of(node);
        }
        for (String alias : aliases) {
            JsonNode aliasNode = payload.get(alias);
            if (aliasNode != null && !aliasNode.isNull()) {
                return Optional.of(aliasNode);
            }
        }
        return Optional.empty();
    }
}
