import type { Book } from "$lib/validation/schemas";
import { isDegenerateText } from "$lib/validation/textQuality";

/**
 * Canonical production environment identifier used by backend and frontend.
 */
export const PRODUCTION_ENVIRONMENT_MODE = "production";

/**
 * Normalizes optional environment mode strings to a deterministic lower-case value.
 */
export function normalizeEnvironmentMode(mode: string | null | undefined): string {
  if (!mode) {
    return PRODUCTION_ENVIRONMENT_MODE;
  }
  const normalized = mode.trim().toLowerCase();
  return normalized.length > 0 ? normalized : PRODUCTION_ENVIRONMENT_MODE;
}

/**
 * Determines whether a book has AI content that is safe to render in the panel.
 */
export function hasRenderableAiContent(book: Book | null | undefined): boolean {
  const summary = book?.aiContent?.summary;
  return !!summary && !isDegenerateText(summary);
}

/**
 * Keeps the Reader's Guide visible for cached content and terminal stream results.
 */
export function shouldRenderPanel(
  aiServiceAvailable: boolean,
  hasTerminalFailure: boolean,
  book: Book | null | undefined,
): boolean {
  return aiServiceAvailable || hasTerminalFailure || hasRenderableAiContent(book);
}
