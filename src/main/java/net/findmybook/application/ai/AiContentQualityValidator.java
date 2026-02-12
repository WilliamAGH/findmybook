package net.findmybook.application.ai;

import java.util.List;
import net.findmybook.domain.ai.BookAiContent;
import org.springframework.util.StringUtils;

/**
 * Validates AI-generated content for quality before persistence.
 *
 * <p>Detects degenerate LLM output: character repetition loops, gibberish
 * without real words, and out-of-bounds field lengths. All thresholds are
 * intentionally conservative so legitimate edge cases pass while obvious
 * degeneration (e.g. 1000 `@` characters) is caught.</p>
 *
 * <p>Package-private — used only by {@link AiContentJsonParser}.</p>
 */
class AiContentQualityValidator {

    static final int SUMMARY_MIN_LENGTH = 20;
    static final int SUMMARY_MAX_LENGTH = 2000;
    static final int SUMMARY_MIN_WORD_COUNT = 3;
    static final int PROSE_FIELD_MAX_LENGTH = 1500;
    static final int LIST_ITEM_MAX_LENGTH = 300;
    static final double MAX_SINGLE_CHAR_RATIO = 0.40;
    static final double MIN_LETTER_RATIO = 0.50;

    private AiContentQualityValidator() {
    }

    /**
     * Returns true when all quality checks pass without throwing.
     *
     * <p>Used by the plain-text fallback path where rejection is silent
     * rather than exceptional.</p>
     */
    static boolean isValid(BookAiContent content) {
        try {
            validate(content);
            return true;
        } catch (IllegalStateException _) {
            return false;
        }
    }

    /**
     * Validates all fields of the given AI content for quality.
     *
     * @param content parsed AI content to validate
     * @throws IllegalStateException with a descriptive message when any field
     *         fails quality checks, triggering retry logic upstream
     */
    static void validate(BookAiContent content) {
        validateRequiredProse("summary", content.summary(),
            SUMMARY_MIN_LENGTH, SUMMARY_MAX_LENGTH, SUMMARY_MIN_WORD_COUNT);
        validateOptionalProse("readerFit", content.readerFit(), PROSE_FIELD_MAX_LENGTH);
        validateOptionalProse("context", content.context(), PROSE_FIELD_MAX_LENGTH);
        validateStringList("keyThemes", content.keyThemes(), LIST_ITEM_MAX_LENGTH);
        validateStringList("takeaways", content.takeaways(), LIST_ITEM_MAX_LENGTH);
    }

    private static void validateRequiredProse(String fieldName, String value,
                                              int minLength, int maxLength,
                                              int minWordCount) {
        if (!StringUtils.hasText(value)) {
            throw qualityFailure(fieldName, "is blank or missing");
        }
        String trimmed = value.trim();
        if (trimmed.length() < minLength) {
            throw qualityFailure(fieldName,
                "too short (%d chars, minimum %d)".formatted(trimmed.length(), minLength));
        }
        if (trimmed.length() > maxLength) {
            throw qualityFailure(fieldName,
                "too long (%d chars, maximum %d)".formatted(trimmed.length(), maxLength));
        }
        assertNotDegenerate(fieldName, trimmed, minWordCount);
    }

    private static void validateOptionalProse(String fieldName, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw qualityFailure(fieldName,
                "too long (%d chars, maximum %d)".formatted(trimmed.length(), maxLength));
        }
        assertNotDegenerate(fieldName, trimmed, 0);
    }

    private static void validateStringList(String fieldName, List<String> items, int maxItemLength) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String trimmed = item.trim();
            if (trimmed.length() > maxItemLength) {
                throw qualityFailure(fieldName + "[" + i + "]",
                    "item too long (%d chars, maximum %d)".formatted(trimmed.length(), maxItemLength));
            }
            assertNotDegenerate(fieldName + "[" + i + "]", trimmed, 0);
        }
    }

    /**
     * Detects degenerate text by checking three complementary signals:
     * <ol>
     *   <li><b>Repetition ratio</b> — any single character exceeding 40% of the
     *       string indicates a repetition loop.</li>
     *   <li><b>Letter ratio</b> — real prose is at least 50% Unicode letters;
     *       strings of symbols/punctuation fail this.</li>
     *   <li><b>Word count</b> — when required, whitespace-delimited tokens
     *       must meet a minimum to ensure coherent sentences.</li>
     * </ol>
     */
    private static void assertNotDegenerate(String fieldName, String text, int minWordCount) {
        if (text.isEmpty()) {
            return;
        }

        if (exceedsSingleCharRepetitionThreshold(text)) {
            throw qualityFailure(fieldName,
                "degenerate repetition detected (a single character dominates the content)");
        }

        if (letterRatioBelow(text, MIN_LETTER_RATIO)) {
            throw qualityFailure(fieldName,
                "content lacks sufficient letter characters (possible symbol/garbage output)");
        }

        if (minWordCount > 0) {
            int wordCount = countWhitespaceDelimitedWords(text);
            if (wordCount < minWordCount) {
                throw qualityFailure(fieldName,
                    "too few words (%d, minimum %d)".formatted(wordCount, minWordCount));
            }
        }
    }

    /**
     * Returns true when any single character makes up more than
     * {@link #MAX_SINGLE_CHAR_RATIO} of the string.
     */
    static boolean exceedsSingleCharRepetitionThreshold(String text) {
        if (text.length() < 10) {
            return false;
        }
        var charCounts = new java.util.HashMap<Character, Integer>();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            charCounts.put(ch, charCounts.getOrDefault(ch, 0) + 1);
        }
        int maxCount = 0;
        for (int count : charCounts.values()) {
            if (count > maxCount) {
                maxCount = count;
            }
        }
        double ratio = (double) maxCount / text.length();
        return ratio > MAX_SINGLE_CHAR_RATIO;
    }

    private static boolean letterRatioBelow(String text, double threshold) {
        if (text.isEmpty()) {
            return false;
        }
        long letterCount = text.codePoints()
            .filter(Character::isLetter)
            .count();
        double ratio = (double) letterCount / text.length();
        return ratio < threshold;
    }

    private static int countWhitespaceDelimitedWords(String text) {
        if (text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private static IllegalStateException qualityFailure(String fieldName, String reason) {
        return new IllegalStateException(
            "AI content quality check failed for '%s': %s".formatted(fieldName, reason));
    }
}
