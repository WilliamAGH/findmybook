import { validateFetchJson } from "$lib/validation/validate";
import type { z } from "zod/v4";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

function getCookie(cookieName: string): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const entry = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${cookieName}=`));

  if (!entry) {
    return null;
  }

  const token = entry.substring(cookieName.length + 1);
  try {
    return decodeURIComponent(token);
  } catch {
    return token;
  }
}

function withCsrf(init: RequestInit): RequestInit {
  const headers = new Headers(init.headers ?? {});
  const token = getCookie(CSRF_COOKIE);
  if (token && !headers.has(CSRF_HEADER)) {
    headers.set(CSRF_HEADER, token);
  }
  return { ...init, headers };
}

export async function getJson<T>(url: string, schema: z.ZodType<T>, recordId: string): Promise<T> {
  const response = await fetch(url, { method: "GET", cache: "no-store", credentials: "same-origin" });
  const result = await validateFetchJson(response, schema, recordId);
  if (!result.success) {
    throw new Error(result.error);
  }
  return result.data;
}

export async function postJson<TRequest extends object, TResponse>(
  url: string,
  payload: TRequest,
  responseSchema: z.ZodType<TResponse>,
  recordId: string,
): Promise<TResponse> {
  const response = await fetch(
    url,
    withCsrf({
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      credentials: "same-origin",
      body: JSON.stringify(payload),
    }),
  );

  const result = await validateFetchJson(response, responseSchema, recordId);
  if (!result.success) {
    throw new Error(result.error);
  }
  return result.data;
}
