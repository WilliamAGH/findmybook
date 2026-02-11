import type { Book } from "$lib/validation/schemas";

/**
 * Shared queue threshold for automatic AI generation triggering.
 */
export const AI_AUTO_TRIGGER_QUEUE_THRESHOLD = 5;

/**
 * Minimum plain-text description length required for faithful AI generation.
 */
export const AI_MINIMUM_DESCRIPTION_LENGTH = 50;

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
 * Resolves the best available plain-text description length for AI eligibility checks.
 */
export function resolvedDescriptionLength(book: Book | null | undefined): number {
  const plainTextDescription = book?.descriptionContent?.text;
  if (plainTextDescription && plainTextDescription.trim().length > 0) {
    return plainTextDescription.trim().length;
  }
  if (book?.description && book.description.trim().length > 0) {
    return book.description.trim().length;
  }
  return 0;
}

/**
 * Determines whether production UI must suppress the Reader's Guide panel due to
 * insufficient source material and no cached AI content.
 */
export function shouldSuppressPanelForShortDescriptionInProduction(
  aiFailureDiagnosticsEnabled: boolean,
  book: Book | null | undefined,
): boolean {
  return !aiFailureDiagnosticsEnabled
    && !book?.aiContent
    && resolvedDescriptionLength(book) < AI_MINIMUM_DESCRIPTION_LENGTH;
}

/**
 * Determines whether the Reader's Guide panel should be rendered at all.
 */
export function shouldRenderPanel(
  aiFailureDiagnosticsEnabled: boolean,
  aiServiceAvailable: boolean,
  book: Book | null | undefined,
): boolean {
  return !shouldSuppressPanelForShortDescriptionInProduction(aiFailureDiagnosticsEnabled, book)
    && (aiServiceAvailable || !!book?.aiContent);
}
