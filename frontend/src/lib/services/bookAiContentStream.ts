import { validateWithSchema } from "$lib/validation/validate";
import {
  type BookAiContentModelStreamUpdate,
  type BookAiContentQueueUpdate,
  type BookAiContentSnapshot,
  BookAiContentModelStreamUpdateSchema,
  BookAiContentQueueUpdateSchema,
  BookAiContentStreamDoneSchema,
} from "$lib/validation/schemas";

export interface StreamBookAiContentOptions {
  refresh?: boolean;
  signal?: AbortSignal;
  onQueueUpdate?: (update: BookAiContentQueueUpdate) => void;
  onStreamEvent?: (event: BookAiContentModelStreamUpdate) => void;
}

export interface StreamBookAiContentResult {
  message: string;
  aiContent: BookAiContentSnapshot;
}

function parseSseMessage(raw: string): { event: string; data: string } | null {
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

  const data = dataLines.join("\n").trim();
  if (!data) {
    return null;
  }
  return { event, data };
}

function normalizeSseLineEndings(value: string): string {
  return value.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
}

function safeParseJson(data: string, eventType: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    throw new Error(`Malformed JSON in SSE '${eventType}' event`);
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
): Promise<StreamBookAiContentResult> {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("Book AI stream is not readable");
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let pendingCarriageReturn = false;

  const processMessage = (message: { event: string; data: string }): StreamBookAiContentResult | null => {
    if (message.event === "error") {
      const parsed = safeParseJson(message.data, "error");
      if (typeof parsed === "object" && parsed !== null && "error" in parsed) {
        const error = (parsed as { error?: unknown }).error;
        if (typeof error === "string" && error.trim().length > 0) {
          throw new Error(error);
        }
      }
      throw new Error("Book AI stream failed");
    }

    if (message.event === "queued" || message.event === "queue" || message.event === "started") {
      const parsed = safeParseJson(message.data, message.event);
      const queueUpdate = validateWithSchema(
        BookAiContentQueueUpdateSchema,
        { ...(parsed as Record<string, unknown>), event: message.event },
        `bookAiContentQueueUpdate:${message.event}`,
      );
      if (queueUpdate.success) {
        options.onQueueUpdate?.(queueUpdate.data);
      }
      return null;
    }

    if (message.event === "message_start" || message.event === "message_delta" || message.event === "message_done") {
      const parsed = safeParseJson(message.data, message.event);
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
      const parsed = safeParseJson(message.data, "done");
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

  await assertSseContentType(response);
  return readBookAiContentSseStream(response, options);
}
