/**
 * Schema-driven payload validation using Zod v4.
 *
 * Provides typed validation helpers for API responses and realtime event payloads,
 * logging structured diagnostics on failure.
 */
import { z } from "zod/v4";

const MAX_REPORTED_ISSUES = 8;

export type ValidationResult<T> = { success: true; data: T } | { success: false; error: z.ZodError };

function summarizePayload(payload: unknown): string {
  if (payload === null) {
    return "null";
  }
  if (Array.isArray(payload)) {
    return `array(length=${payload.length})`;
  }
  if (typeof payload === "object") {
    const keys = Object.keys(payload as Record<string, unknown>).slice(0, 6);
    return `object(keys=${keys.join(",")})`;
  }
  return typeof payload;
}

export function validateWithSchema<T>(schema: z.ZodType<T>, payload: unknown, recordId: string): ValidationResult<T> {
  const result = schema.safeParse(payload);
  if (!result.success) {
    const issueText = result.error.issues
      .slice(0, MAX_REPORTED_ISSUES)
      .map((issue) => {
        const path = issue.path.length > 0 ? issue.path.join(".") : "(root)";
        return `${path}: ${issue.message}`;
      })
      .join("; ");

    console.error(`[zod] ${recordId} validation failed`, issueText, summarizePayload(payload));
    return { success: false, error: result.error };
  }
  return { success: true, data: result.data };
}

export async function validateFetchJson<T>(response: Response, schema: z.ZodType<T>, recordId: string): Promise<{ success: true; data: T } | { success: false; error: string }> {
  if (!response.ok) {
    return { success: false, error: `HTTP ${response.status}: ${response.statusText}` };
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch (error) {
    return {
      success: false,
      error: `Invalid JSON response: ${error instanceof Error ? error.message : String(error)}`,
    };
  }

  const validation = validateWithSchema(schema, payload, recordId);
  if (!validation.success) {
    return { success: false, error: `Schema validation failed for ${recordId}` };
  }

  return { success: true, data: validation.data };
}
