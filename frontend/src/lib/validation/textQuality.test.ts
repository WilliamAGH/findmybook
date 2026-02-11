import { describe, expect, it } from "vitest";
import { isDegenerateText } from "$lib/validation/textQuality";

describe("isDegenerateText", () => {
  // -- Non-degenerate cases (should return false) --------------------------

  it("should_ReturnFalse_When_TextIsNormalProse", () => {
    const prose =
      "This is a well-written book about the history of American politics " +
      "during the mid-twentieth century.";

    expect(isDegenerateText(prose)).toBe(false);
  });

  it("should_ReturnFalse_When_TextIsEmpty", () => {
    expect(isDegenerateText("")).toBe(false);
  });

  it("should_ReturnFalse_When_TextIsShort", () => {
    // Under MIN_RENDERABLE_LENGTH (10), always returns false
    expect(isDegenerateText("short")).toBe(false);
  });

  it("should_ReturnFalse_When_TextIsExactlyMinLength", () => {
    expect(isDegenerateText("Hello worl")).toBe(false); // 10 chars, valid prose
  });

  it("should_ReturnFalse_When_TextHasBalancedCharacterDistribution", () => {
    const balanced = "abcdefghijklmnopqrstuvwxyz".repeat(4);
    expect(isDegenerateText(balanced)).toBe(false);
  });

  it("should_ReturnFalse_When_TextContainsSomeSpecialCharacters", () => {
    // Prose with punctuation and numbers mixed in (still majority letters)
    const mixed =
      "Chapter 1: The Rise of Power (1945-1960) covers 15 years of history.";
    expect(isDegenerateText(mixed)).toBe(false);
  });

  // -- Degenerate cases: single-character domination (should return true) --

  it("should_ReturnTrue_When_TextIsPureAtSigns", () => {
    const atSpam = "@".repeat(999);
    expect(isDegenerateText(atSpam)).toBe(true);
  });

  it("should_ReturnTrue_When_SingleCharacterDominates", () => {
    // 'x' is > 50% of the string
    const dominated = "x".repeat(60) + "abcdefghijklmnopqrstuv";
    expect(isDegenerateText(dominated)).toBe(true);
  });

  it("should_ReturnTrue_When_NonAsciiCharacterDominates", () => {
    const emojiSpam = "\u00E9".repeat(100);
    expect(isDegenerateText(emojiSpam)).toBe(true);
  });

  // -- Degenerate cases: low letter ratio (should return true) -------------

  it("should_ReturnTrue_When_TextIsAllSymbols", () => {
    const symbols = "!@#$%^&*()+={}[]|\\:;<>,./?".repeat(5);
    expect(isDegenerateText(symbols)).toBe(true);
  });

  it("should_ReturnTrue_When_LetterRatioIsBelowThreshold", () => {
    // ~25% letters, 75% digits/symbols
    const lowLetters =
      "abc1234567890!@#$%^&" +
      "def1234567890!@#$%^&" +
      "ghi1234567890!@#$%^&";
    expect(isDegenerateText(lowLetters)).toBe(true);
  });

  it("should_ReturnTrue_When_TextIsAllDigits", () => {
    const digits = "1234567890".repeat(10);
    expect(isDegenerateText(digits)).toBe(true);
  });

  // -- Edge cases ----------------------------------------------------------

  it("should_ReturnFalse_When_TextIsUndefined", () => {
    // TypeScript allows this at runtime through JS interop
    expect(isDegenerateText(undefined as unknown as string)).toBe(false);
  });

  it("should_ReturnFalse_When_TextContainsUnicodeLetters", () => {
    // Non-ASCII letters should count as letters (uses \p{L})
    const french = "Les Misérables est un roman français classique et célèbre.";
    expect(isDegenerateText(french)).toBe(false);
  });

  it("should_ReturnFalse_When_TextContainsCJKCharacters", () => {
    const japanese = "これは日本語のテキストです。文学について書かれています。";
    expect(isDegenerateText(japanese)).toBe(false);
  });

  // -- Threshold boundary tests --------------------------------------------

  it("should_ReturnFalse_When_CharRatioIsExactlyAtBoundary", () => {
    // Create text where max char is exactly 50% (boundary: > 50% triggers)
    // 10 'a' + 10 other distinct chars = 50% 'a', should NOT trigger
    const boundary = "a".repeat(10) + "bcdefghijk";
    expect(isDegenerateText(boundary)).toBe(false);
  });

  it("should_ReturnTrue_When_CharRatioExceedsBoundary", () => {
    // 11 'a' + 10 other chars = 52.4% 'a', should trigger
    const overBoundary = "a".repeat(11) + "bcdefghijk";
    expect(isDegenerateText(overBoundary)).toBe(true);
  });
});
