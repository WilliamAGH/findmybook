/**
 * Frontend guard for degenerate AI-generated text.
 *
 * Detects text that passed backend validation but would still render
 * poorly on the UI (e.g. extremely long strings of repeated characters).
 * Used as a last-resort display guard, not a replacement for backend checks.
 */

const MIN_LETTER_RATIO = 0.4;
const MAX_SINGLE_CHAR_RATIO = 0.5;
const MIN_RENDERABLE_LENGTH = 10;

/**
 * Returns true when the text appears to be degenerate LLM output
 * that would render as nonsensical garbage in the UI.
 *
 * Checks:
 * - Single-character domination (any character > 50% of string)
 * - Insufficient letter characters (< 40% Unicode letters)
 */
export function isDegenerateText(text: string): boolean {
  if (!text || text.length < MIN_RENDERABLE_LENGTH) {
    return false;
  }

  const charCounts = new Map<string, number>();
  let letterCount = 0;
  for (const char of text) {
    charCounts.set(char, (charCounts.get(char) ?? 0) + 1);
    if (/\p{L}/u.test(char)) {
      letterCount++;
    }
  }

  let maxCharCount = 0;
  for (const count of charCounts.values()) {
    if (count > maxCharCount) {
      maxCharCount = count;
    }
  }

  if (maxCharCount / text.length > MAX_SINGLE_CHAR_RATIO) {
    return true;
  }

  if (letterCount / text.length < MIN_LETTER_RATIO) {
    return true;
  }

  return false;
}
