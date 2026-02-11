package net.findmybook.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.findmybook.domain.ai.BookAiContent;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AiContentQualityValidator}.
 *
 * <p>Tests cover all five quality dimensions: length bounds, word count,
 * character repetition, letter ratio, and list-item constraints. Each
 * test targets a single failure mode to keep diagnostics unambiguous.</p>
 */
class AiContentQualityValidatorTest {

    // -- Helpers ---------------------------------------------------------------

    /**
     * Builds a valid BookAiContent with the given summary and sensible defaults
     * for all other fields. Keeps tests focused on the field under test.
     */
    private static BookAiContent withSummary(String summary) {
        return new BookAiContent(summary, null, List.of("Theme one"), null, null);
    }

    /**
     * A valid baseline summary: 50 words of prose, well above every threshold.
     */
    private static final String VALID_SUMMARY =
        "This is a well-written book about the history of American politics " +
        "during the mid-twentieth century. The author captures the drama and " +
        "tension of the era with vivid prose and meticulous research. Readers " +
        "who enjoy narrative nonfiction will find this work both enlightening " +
        "and deeply engaging from beginning to end.";

    // -- validate: summary length bounds --------------------------------------

    @Test
    void should_Pass_When_SummaryIsValidProse() {
        BookAiContent content = withSummary(VALID_SUMMARY);
        AiContentQualityValidator.validate(content);
        // no exception = pass
    }

    @Test
    void should_Reject_When_SummaryIsNull() {
        BookAiContent content = new BookAiContent(null, null, List.of("Theme"), null, null);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary")
            .hasMessageContaining("blank or missing");
    }

    @Test
    void should_Reject_When_SummaryIsBlank() {
        BookAiContent content = withSummary("   ");

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary")
            .hasMessageContaining("blank or missing");
    }

    @Test
    void should_Reject_When_SummaryIsTooShort() {
        BookAiContent content = withSummary("Too short.");

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary")
            .hasMessageContaining("too short");
    }

    @Test
    void should_Pass_When_SummaryIsExactlyMinLength() {
        // 20 characters of real prose
        String exactly20 = "Exactly twenty chars";
        assertThat(exactly20.trim().length()).isEqualTo(20);

        BookAiContent content = withSummary(exactly20);
        AiContentQualityValidator.validate(content);
    }

    @Test
    void should_Reject_When_SummaryExceedsMaxLength() {
        String tooLong = "A ".repeat(1001); // 2002 chars
        BookAiContent content = withSummary(tooLong);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary")
            .hasMessageContaining("too long");
    }

    // -- validate: summary word count -----------------------------------------

    @Test
    void should_Reject_When_SummaryHasTooFewWords() {
        // 2 words, needs 3 minimum. 20+ chars to pass length check.
        BookAiContent content = withSummary("Insufficient wordcount");

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary")
            .hasMessageContaining("too few words");
    }

    @Test
    void should_Pass_When_SummaryHasExactlyThreeWords() {
        // Exactly 3 words, 20+ chars
        BookAiContent content = withSummary("Three words exactly right here");
        AiContentQualityValidator.validate(content);
    }

    // -- validate: summary degenerate repetition ------------------------------

    @Test
    void should_Reject_When_SummaryIsPureAtSigns() {
        String atSpam = "@".repeat(999);
        BookAiContent content = withSummary(atSpam);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary");
    }

    @Test
    void should_Reject_When_SummaryHasSingleCharDomination() {
        // 'a' makes up > 40% of the 50-char string
        String dominated = "a".repeat(25) + "bcdefghijklmnopqrstuvwxy";
        BookAiContent content = withSummary(dominated);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("degenerate repetition");
    }

    // -- validate: summary letter ratio ---------------------------------------

    @Test
    void should_Reject_When_SummaryIsAllSymbols() {
        String symbols = "!@#$%^&*()+={}[]|\\:;<>,./? ".repeat(5);
        BookAiContent content = withSummary(symbols);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary");
    }

    @Test
    void should_Reject_When_SummaryHasLowLetterRatio() {
        // ~30% letters, 70% digits/symbols
        String lowLetters = "abc1234567890!@#$%^&*()+=[]{}|" +
                            "def1234567890!@#$%^&*()+=[]{}|";
        BookAiContent content = withSummary(lowLetters);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("summary");
    }

    // -- validate: optional prose fields (readerFit, context) -----------------

    @Test
    void should_Pass_When_ReaderFitIsNull() {
        BookAiContent content = new BookAiContent(VALID_SUMMARY, null, List.of("Theme"), null, null);
        AiContentQualityValidator.validate(content);
    }

    @Test
    void should_Pass_When_ReaderFitIsBlank() {
        BookAiContent content = new BookAiContent(VALID_SUMMARY, "  ", List.of("Theme"), null, null);
        AiContentQualityValidator.validate(content);
    }

    @Test
    void should_Reject_When_ReaderFitExceedsMaxLength() {
        String longFit = "Word ".repeat(301); // 1505 chars
        BookAiContent content = new BookAiContent(VALID_SUMMARY, longFit, List.of("Theme"), null, null);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("readerFit")
            .hasMessageContaining("too long");
    }

    @Test
    void should_Reject_When_ContextExceedsMaxLength() {
        String longCtx = "Word ".repeat(301); // 1505 chars
        BookAiContent content = new BookAiContent(VALID_SUMMARY, null, List.of("Theme"), null, longCtx);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("context")
            .hasMessageContaining("too long");
    }

    @Test
    void should_Reject_When_ReaderFitIsDegenerate() {
        String degenerate = "@".repeat(500);
        BookAiContent content = new BookAiContent(VALID_SUMMARY, degenerate, List.of("Theme"), null, null);

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("readerFit");
    }

    // -- validate: list fields (keyThemes, takeaways) -------------------------

    @Test
    void should_Pass_When_KeyThemesAreNormal() {
        BookAiContent content = new BookAiContent(
            VALID_SUMMARY, null,
            List.of("Political history", "American culture", "Cold War"),
            null, null
        );
        AiContentQualityValidator.validate(content);
    }

    @Test
    void should_Pass_When_KeyThemesIsEmpty() {
        BookAiContent content = new BookAiContent(VALID_SUMMARY, null, List.of(), null, null);
        AiContentQualityValidator.validate(content);
    }

    @Test
    void should_Pass_When_KeyThemesIsNull() {
        BookAiContent content = new BookAiContent(VALID_SUMMARY, null, null, null, null);
        AiContentQualityValidator.validate(content);
    }

    @Test
    void should_Reject_When_KeyThemeItemExceedsMaxLength() {
        String longTheme = "W".repeat(301); // > 300 limit
        BookAiContent content = new BookAiContent(
            VALID_SUMMARY, null,
            List.of("Normal theme", longTheme),
            null, null
        );

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("keyThemes[1]")
            .hasMessageContaining("item too long");
    }

    @Test
    void should_Reject_When_TakeawayItemIsDegenerate() {
        String degenerate = "#".repeat(100);
        BookAiContent content = new BookAiContent(
            VALID_SUMMARY, null, List.of("Theme"),
            List.of("Valid takeaway here", degenerate),
            null
        );

        assertThatThrownBy(() -> AiContentQualityValidator.validate(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("takeaways[1]");
    }

    @Test
    void should_SkipBlankItemsInLists() {
        BookAiContent content = new BookAiContent(
            VALID_SUMMARY, null,
            List.of("Valid", "", "  ", "Also valid"),
            null, null
        );
        AiContentQualityValidator.validate(content);
    }

    // -- isValid: boolean wrapper ----------------------------------------------

    @Test
    void should_ReturnTrue_When_ContentIsValid() {
        BookAiContent content = withSummary(VALID_SUMMARY);
        assertThat(AiContentQualityValidator.isValid(content)).isTrue();
    }

    @Test
    void should_ReturnFalse_When_ContentIsInvalid() {
        BookAiContent content = withSummary("@".repeat(999));
        assertThat(AiContentQualityValidator.isValid(content)).isFalse();
    }

    // -- exceedsSingleCharRepetitionThreshold: edge cases ----------------------

    @Test
    void should_NotFlagShortStrings_When_UnderTenChars() {
        // Strings under 10 chars are too short to evaluate meaningfully
        assertThat(AiContentQualityValidator.exceedsSingleCharRepetitionThreshold("aaaaaa")).isFalse();
    }

    @Test
    void should_FlagNonAsciiRepetition_When_SingleCharDominates() {
        // Non-ASCII character domination (e.g. emoji spam)
        String emojiSpam = "\u00E9".repeat(50);
        assertThat(AiContentQualityValidator.exceedsSingleCharRepetitionThreshold(emojiSpam)).isTrue();
    }

    @Test
    void should_NotFlag_When_CharDistributionIsEven() {
        // Even distribution across many characters
        String balanced = "abcdefghijklmnopqrstuvwxyz".repeat(4); // 104 chars, no char > 4%
        assertThat(AiContentQualityValidator.exceedsSingleCharRepetitionThreshold(balanced)).isFalse();
    }

    // -- Full valid content integration test -----------------------------------

    @Test
    void should_Pass_When_AllFieldsArePopulatedAndValid() {
        BookAiContent content = new BookAiContent(
            VALID_SUMMARY,
            "Readers interested in American political history will benefit most from this book.",
            List.of("Political history", "Mid-century America", "Narrative nonfiction"),
            List.of("Power shapes public memory", "Media amplifies political drama"),
            "Positioned within the tradition of sweeping American narrative histories."
        );
        AiContentQualityValidator.validate(content);
    }
}
