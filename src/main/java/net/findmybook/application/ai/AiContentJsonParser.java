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
     * <p>The returned {@link BookAiContent} always has a non-null {@code summary}
     * and {@code keyThemes} list. The {@code readerFit}, {@code takeaways}, and
     * {@code context} fields are {@code @Nullable} — they are null when the LLM
     * response omits the field or provides an empty/whitespace-only value.</p>
     *
     * @param responseText raw LLM output (may include markdown fences)
     * @return parsed content with nullable optional fields per {@link BookAiContent}
     * @throws IllegalStateException if the response is empty, not valid JSON, or missing {@code summary}
     */
    BookAiContent parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            throw new IllegalStateException("AI content response was empty");
        }

        JsonNode payload;
        try {
            payload = parseJsonPayload(responseText);
        } catch (IllegalStateException parseFailure) {
            Optional<BookAiContent> plainTextFallback = parsePlainTextFallback(responseText);
            if (plainTextFallback.isPresent()) {
                log.warn("AI response was non-JSON; applied plain-text parsing fallback");
                return plainTextFallback.get();
            }
            throw parseFailure;
        }
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

    private Optional<BookAiContent> parsePlainTextFallback(String responseText) {
        String cleaned = responseText
            .replace("```json", "")
            .replace("```", "")
            .trim();
        if (!StringUtils.hasText(cleaned)) {
            return Optional.empty();
        }

        List<String> summaryLines = new ArrayList<>();
        List<String> readerFitLines = new ArrayList<>();
        List<String> themeLines = new ArrayList<>();
        List<String> takeawayLines = new ArrayList<>();
        List<String> contextLines = new ArrayList<>();
        List<String> unscopedLines = new ArrayList<>();

        Section activeSection = Section.NONE;
        for (String rawLine : cleaned.split("\\R")) {
            String line = normalizeLine(rawLine);
            if (!StringUtils.hasText(line)) {
                continue;
            }

            SectionMatch sectionMatch = matchSectionHeader(line);
            if (sectionMatch != null) {
                activeSection = sectionMatch.section();
                if (StringUtils.hasText(sectionMatch.remainder())) {
                    appendToSection(
                        sectionMatch.section(),
                        sectionMatch.remainder(),
                        summaryLines,
                        readerFitLines,
                        themeLines,
                        takeawayLines,
                        contextLines,
                        unscopedLines
                    );
                }
                continue;
            }

            appendToSection(activeSection, line, summaryLines, readerFitLines, themeLines, takeawayLines, contextLines, unscopedLines);
        }

        String summary = chooseSummary(summaryLines, unscopedLines);
        if (!StringUtils.hasText(summary)) {
            return Optional.empty();
        }

        Optional<String> readerFit = joinLines(readerFitLines);
        List<String> keyThemes = normalizeList(themeLines, MAX_KEY_THEME_COUNT);
        List<String> takeaways = normalizeList(takeawayLines, MAX_TAKEAWAY_COUNT);
        Optional<String> context = joinLines(contextLines);

        if (keyThemes.isEmpty() && takeaways.isEmpty()) {
            log.warn("Plain-text AI fallback produced no themes and no takeaways");
        }

        return Optional.of(new BookAiContent(
            summary,
            readerFit.orElse(null),
            keyThemes,
            takeaways.isEmpty() ? null : takeaways,
            context.orElse(null)
        ));
    }

    private String chooseSummary(List<String> summaryLines, List<String> unscopedLines) {
        Optional<String> explicitSummary = joinLines(summaryLines);
        if (explicitSummary.isPresent()) {
            return explicitSummary.get();
        }
        Optional<String> inferredSummary = joinLines(unscopedLines);
        if (inferredSummary.isEmpty()) {
            return null;
        }
        return clampToTwoSentences(inferredSummary.get());
    }

    private String clampToTwoSentences(String text) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length <= 2) {
            return text;
        }
        return (sentences[0] + " " + sentences[1]).trim();
    }

    private Optional<String> joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        String joined = String.join(" ", lines).replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(joined)) {
            return Optional.empty();
        }
        return Optional.of(joined);
    }

    private List<String> normalizeList(List<String> lines, int maxSize) {
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (line.contains(", ")) {
                for (String token : line.split(",\\s+")) {
                    addDistinct(values, token, maxSize);
                    if (values.size() == maxSize) {
                        break;
                    }
                }
            } else {
                addDistinct(values, line, maxSize);
            }
            if (values.size() == maxSize) {
                break;
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private void addDistinct(List<String> values, String value, int maxSize) {
        if (values.size() >= maxSize) {
            return;
        }
        String normalized = normalizeLine(value);
        if (!StringUtils.hasText(normalized) || values.contains(normalized)) {
            return;
        }
        values.add(normalized);
    }

    private String normalizeLine(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
            .replace('\t', ' ')
            .replaceAll("^[\\-*•\\d.)\\s]+", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private void appendToSection(Section section,
                                 String line,
                                 List<String> summaryLines,
                                 List<String> readerFitLines,
                                 List<String> themeLines,
                                 List<String> takeawayLines,
                                 List<String> contextLines,
                                 List<String> unscopedLines) {
        switch (section) {
            case SUMMARY -> summaryLines.add(line);
            case READER_FIT -> readerFitLines.add(line);
            case KEY_THEMES -> themeLines.add(line);
            case TAKEAWAYS -> takeawayLines.add(line);
            case CONTEXT -> contextLines.add(line);
            case NONE -> unscopedLines.add(line);
        }
    }

    private SectionMatch matchSectionHeader(String line) {
        String normalized = line.replaceAll("\\s+", " ").trim();
        return matchSection(normalized, "summary", Section.SUMMARY)
            .or(() -> matchSection(normalized, "reader fit", Section.READER_FIT))
            .or(() -> matchSection(normalized, "reader_fit", Section.READER_FIT))
            .or(() -> matchSection(normalized, "ideal reader", Section.READER_FIT))
            .or(() -> matchSection(normalized, "key themes", Section.KEY_THEMES))
            .or(() -> matchSection(normalized, "themes", Section.KEY_THEMES))
            .or(() -> matchSection(normalized, "takeaways", Section.TAKEAWAYS))
            .or(() -> matchSection(normalized, "takeaway", Section.TAKEAWAYS))
            .or(() -> matchSection(normalized, "context", Section.CONTEXT))
            .orElse(null);
    }

    private Optional<SectionMatch> matchSection(String line, String label, Section section) {
        String lowerLine = line.toLowerCase();
        String lowerLabel = label.toLowerCase();
        if (lowerLine.equals(lowerLabel)) {
            return Optional.of(new SectionMatch(section, ""));
        }
        String prefix = lowerLabel + ":";
        if (lowerLine.startsWith(prefix)) {
            String remainder = line.substring(prefix.length()).trim();
            return Optional.of(new SectionMatch(section, remainder));
        }
        return Optional.empty();
    }

    private enum Section {
        NONE,
        SUMMARY,
        READER_FIT,
        KEY_THEMES,
        TAKEAWAYS,
        CONTEXT
    }

    private record SectionMatch(Section section, String remainder) {
    }
}
