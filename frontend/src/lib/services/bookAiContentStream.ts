import { validateWithSchema } from "$lib/validation/validate";
import {
  type BookAiErrorCode,
  type BookAiContentModelStreamUpdate,
  type BookAiContentQueuedUpdate,
  type BookAiContentQueueUpdate,
  type BookAiContentSnapshot,
  BookAiContentStreamErrorSchema,
  BookAiContentModelStreamUpdateSchema,
  BookAiContentQueueUpdateSchema,
  BookAiContentStreamDoneSchema,
} from "$lib/validation/schemas";

export interface StreamBookAiContentOptions {
  refresh?: boolean;
  signal?: AbortSignal;
  onRequestId?: (requestId: string) => void;
  onQueued?: (update: BookAiContentQueuedUpdate) => void;
  onQueueUpdate?: (update: BookAiContentQueueUpdate) => void;
  onStreamEvent?: (event: BookAiContentModelStreamUpdate) => void;
}

export interface StreamBookAiContentResult {
  message: string;
  aiContent: BookAiContentSnapshot;
}

export interface BookAiContentStreamError extends Error {
  code: BookAiErrorCode;
  retryable: boolean;
}

/** Owns browser transport state for exactly one AI generation attempt. */
export class BookAiContentRequestAttempt {
  readonly abortController = new AbortController();
  private requestId: string | null = null;
  private canceled = false;
  private cancellationSent = false;
  private terminal = false;

  constructor(readonly identifier: string,
    private readonly deliverCancellation: (requestId: string) => void = cancelBookAiContentRequest,
  ) {}

  get signal(): AbortSignal { return this.abortController.signal; }

  get isCanceled(): boolean { return this.canceled; }

  captureRequestId(requestId: string): void {
    if (this.requestId !== null && this.requestId !== requestId) {
      throw new Error("Book AI request ID changed during one generation attempt");
    }
    this.requestId = requestId;
    this.sendCancellationIfNeeded();
  }

  cancel(): void {
    if (this.terminal) {
      return;
    }
    this.canceled = true;
    this.abortController.abort();
    this.sendCancellationIfNeeded();
  }

  complete(): void {
    this.requestId = null;
    this.terminal = true;
  }

  private sendCancellationIfNeeded(): void {
    if (!this.canceled || this.requestId === null || this.cancellationSent || this.terminal) {
      return;
    }
    this.cancellationSent = true;
    this.deliverCancellation(this.requestId);
  }
}

export function isBookAiContentStreamError(error: unknown): error is BookAiContentStreamError {
  return error instanceof Error
    && typeof (error as { code?: unknown }).code === "string"
    && typeof (error as { retryable?: unknown }).retryable === "boolean";
}

/** Delivers a bodyless, same-origin cancellation without blocking route teardown. */
export function cancelBookAiContentRequest(requestId: string): void {
  const cancelUrl = `/api/books/ai/content/requests/${encodeURIComponent(requestId)}/cancel`;
  if (typeof navigator !== "undefined" && typeof navigator.sendBeacon === "function") {
    try {
      if (navigator.sendBeacon(cancelUrl)) {
        return;
      }
    } catch {
      // A rejected beacon falls through to the keepalive fetch transport.
    }
  }

  void fetch(cancelUrl, {
    method: "POST",
    keepalive: true,
  }).then((response) => {
    if (!response.ok) console.warn("[BookAiContentStream] Explicit cancellation delivery failed");
  }, () => console.warn("[BookAiContentStream] Explicit cancellation delivery failed"));
}

function createBookAiContentStreamError(
  message: string,
  code: BookAiErrorCode = "generation_failed",
  retryable = true,
): BookAiContentStreamError {
  const streamError = new Error(message) as BookAiContentStreamError;
  streamError.name = "BookAiContentStreamError";
  streamError.code = code;
  streamError.retryable = retryable;
  return streamError;
}

function parseSseMessage(raw: string): { event: string; payloadText: string } | null {
  const lines = raw.split("\n");
  let event = "message";
  const dataLines: string[] = [];

  for (const line of lines) {
    if (line.startsWith("event:")) {
      event = line.slice("event:".length).trim();
      continue;
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).trimStart());
    }
  }

  const payloadText = dataLines.join("\n").trim();
  if (!payloadText) {
    return null;
  }
  return { event, payloadText };
}

function normalizeSseLineEndings(value: string): string {
  return value.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
}

function safeParseJson(payloadText: string, eventType: string): unknown {
  try {
    return JSON.parse(payloadText);
  } catch (parseError) {
    const detail = parseError instanceof Error ? parseError.message : String(parseError);
    throw new Error(`Malformed JSON in SSE '${eventType}' event: ${detail}`);
  }
}

async function assertSseContentType(response: Response): Promise<void> {
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  if (contentType.includes("text/event-stream")) {
    return;
  }

  const responseText = await response.text();
  const bodyPreview = responseText.trim();
  const preview = bodyPreview.length > 200 ? `${bodyPreview.slice(0, 200)}...` : bodyPreview;
  const headerValue = contentType || "missing";
  throw new Error(
    `Book AI stream expected text/event-stream but received '${headerValue}' (status ${response.status}). ${preview}`,
  );
}

async function readBookAiContentSseStream(
  response: Response,
  options: StreamBookAiContentOptions,
  initialRequestId: string | null,
): Promise<StreamBookAiContentResult> {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("Book AI stream is not readable");
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let pendingCarriageReturn = false;
  let requestId = initialRequestId;

  const processMessage = (message: { event: string; payloadText: string }): StreamBookAiContentResult | null => {
    if (message.event === "error") {
      const parsed = safeParseJson(message.payloadText, "error");
      const streamError = validateWithSchema(
        BookAiContentStreamErrorSchema,
        parsed,
        "bookAiContentStreamError",
      );
      if (streamError.success) {
        throw createBookAiContentStreamError(
          streamError.data.error,
          streamError.data.code,
          streamError.data.retryable,
        );
      }
      // Enforce the explicit backend stream error contract (`error`, `code`, `retryable`).
      // Untyped/legacy payloads are treated as invalid instead of being loosely interpreted.
      throw createBookAiContentStreamError("Book AI stream failed");
    }

    if (message.event === "queued" || message.event === "queue" || message.event === "started") {
      const parsed = safeParseJson(message.payloadText, message.event);
      const queueUpdate = validateWithSchema(
        BookAiContentQueueUpdateSchema,
        { ...(parsed as Record<string, unknown>), event: message.event },
        `bookAiContentQueueUpdate:${message.event}`,
      );
      if (queueUpdate.success) {
        if (queueUpdate.data.event === "queued") {
          if (requestId !== null && requestId !== queueUpdate.data.requestId) {
            throw new Error("Book AI request ID did not match queued stream payload");
          }
          if (requestId === null) {
            requestId = queueUpdate.data.requestId;
            options.onRequestId?.(requestId);
          }
          options.onQueued?.(queueUpdate.data);
        }
        options.onQueueUpdate?.(queueUpdate.data);
      }
      return null;
    }

    if (message.event === "message_start" || message.event === "message_delta" || message.event === "message_done") {
      const parsed = safeParseJson(message.payloadText, message.event);
      const streamUpdate = validateWithSchema(
        BookAiContentModelStreamUpdateSchema,
        { event: message.event, data: parsed },
        `bookAiContentModelStreamUpdate:${message.event}`,
      );
      if (streamUpdate.success) {
        options.onStreamEvent?.(streamUpdate.data);
      }
      return null;
    }

    if (message.event === "done") {
      const parsed = safeParseJson(message.payloadText, "done");
      const done = validateWithSchema(BookAiContentStreamDoneSchema, parsed, "bookAiContentStreamDone");
      if (!done.success) {
        throw new Error("Book AI stream done payload was invalid");
      }
      return done.data;
    }

    return null;
  };

  try {
    while (true) {
      if (options.signal?.aborted) {
        throw new DOMException("Request aborted", "AbortError");
      }

      const { value, done } = await reader.read();
      if (done) {
        break;
      }

      let chunkText = decoder.decode(value, { stream: true });
      if (pendingCarriageReturn) {
        chunkText = `\r${chunkText}`;
        pendingCarriageReturn = false;
      }
      if (chunkText.endsWith("\r")) {
        pendingCarriageReturn = true;
        chunkText = chunkText.slice(0, -1);
      }
      buffer += normalizeSseLineEndings(chunkText);
      while (true) {
        const delimiterIndex = buffer.indexOf("\n\n");
        if (delimiterIndex < 0) {
          break;
        }

        const chunk = buffer.slice(0, delimiterIndex);
        buffer = buffer.slice(delimiterIndex + 2);

        const parsedMessage = parseSseMessage(chunk);
        if (!parsedMessage) {
          continue;
        }

        const maybeDone = processMessage(parsedMessage);
        if (maybeDone) {
          return maybeDone;
        }
      }
    }

    // Chrome may resolve reader.read() with {done: true} on abort instead
    // of rejecting with AbortError. Detect this before processing trailing
    // buffer so the caller receives the expected DOMException.
    if (options.signal?.aborted) {
      throw new DOMException("Request aborted", "AbortError");
    }

    let trailingText = decoder.decode();
    if (pendingCarriageReturn) {
      trailingText = `\r${trailingText}`;
      pendingCarriageReturn = false;
    }
    buffer += normalizeSseLineEndings(trailingText);
    if (buffer.trim().length > 0) {
      const trailing = parseSseMessage(buffer);
      if (trailing) {
        const maybeDone = processMessage(trailing);
        if (maybeDone) {
          return maybeDone;
        }
      }
    }
  } finally {
    reader.releaseLock();
  }

  throw new Error("Book AI stream ended unexpectedly");
}

export async function streamBookAiContent(
  identifier: string,
  options: StreamBookAiContentOptions = {},
): Promise<StreamBookAiContentResult> {
  const refresh = options.refresh ?? false;
  const response = await fetch(
    `/api/books/${encodeURIComponent(identifier)}/ai/content/stream?refresh=${refresh ? "true" : "false"}`,
    {
      method: "POST",
      headers: {
        Accept: "text/event-stream",
      },
      signal: options.signal,
    },
  );

  if (!response.ok) {
    const responseText = await response.text();
    throw new Error(`Book AI stream failed (HTTP ${response.status}): ${responseText || response.statusText}`);
  }

  const headerRequestId = response.headers.get("X-Book-AI-Request-Id")?.trim() || null;
  if (headerRequestId !== null) {
    options.onRequestId?.(headerRequestId);
  }
  await assertSseContentType(response);
  return readBookAiContentSseStream(response, options, headerRequestId);
}
