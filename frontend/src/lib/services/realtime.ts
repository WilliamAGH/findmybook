/**
 * Realtime event subscriptions over STOMP/WebSocket.
 *
 * Manages a shared STOMP client with auto-reconnect and provides typed subscription
 * helpers for search progress, search results, and book cover update topics.
 */
import { Client, type IFrame } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { z } from "zod/v4";
import {
  BookCoverUpdateEventSchema,
  SearchProgressEventSchema,
  SearchResultsEventSchema,
} from "$lib/validation/schemas";
import { validateWithSchema } from "$lib/validation/validate";

const STOMP_RECONNECT_DELAY_MS = 5000;
const STOMP_HEARTBEAT_INCOMING_MS = 20000;
const STOMP_HEARTBEAT_OUTGOING_MS = 10000;

/**
 * Discriminated union representing the STOMP connection lifecycle.
 * Prevents temporal coupling between sharedClient and connectionPromise.
 */
type ConnectionState =
  | { status: "idle" }
  | { status: "connecting"; pending: Promise<Client> }
  | { status: "connected"; client: Client };

let connection: ConnectionState = { status: "idle" };

function buildClient(): Client {
  return new Client({
    webSocketFactory: () => new SockJS("/ws"),
    reconnectDelay: STOMP_RECONNECT_DELAY_MS,
    heartbeatIncoming: STOMP_HEARTBEAT_INCOMING_MS,
    heartbeatOutgoing: STOMP_HEARTBEAT_OUTGOING_MS,
  });
}

function parseMessagePayload<T>(messageBody: string, recordId: string, schema: z.ZodType<T>): T | null {
  let payload: unknown;
  try {
    payload = JSON.parse(messageBody);
  } catch (error) {
    console.warn(`Failed to parse ${recordId} payload`, error);
    return null;
  }

  const validation = validateWithSchema(schema, payload, recordId);
  if (!validation.success) {
    return null;
  }
  return validation.data;
}

export async function ensureRealtimeClient(): Promise<Client> {
  if (connection.status === "connected" && connection.client.connected) {
    return connection.client;
  }

  if (connection.status === "connecting") {
    return connection.pending;
  }

  const pending = new Promise<Client>((resolve, reject) => {
    const client = buildClient();
    client.onConnect = (_frame: IFrame) => {
      connection = { status: "connected", client };
      resolve(client);
    };
    client.onStompError = (frame) => {
      connection = { status: "idle" };
      reject(new Error(frame.body || "STOMP connection failed"));
    };
    client.onWebSocketError = (event) => {
      connection = { status: "idle" };
      reject(new Error("WebSocket connection failed"));
    };
    client.activate();
  }).catch((error) => {
    connection = { status: "idle" };
    throw error;
  });

  connection = { status: "connecting", pending };
  return pending;
}

export async function subscribeToSearchTopics(
  queryHash: string,
  onProgress: (message: string) => void,
  onResults: (results: unknown[]) => void,
  onError: (error: Error) => void,
): Promise<() => void> {
  const client = await ensureRealtimeClient();

  const progressSub = client.subscribe(`/topic/search/${queryHash}/progress`, (message) => {
    const payload = parseMessagePayload(message.body, "searchProgressEvent", SearchProgressEventSchema);
    if (!payload) {
      onError(new Error("Failed to parse search progress event"));
      return;
    }
    onProgress(payload.message ?? "Searching...");
  });

  const resultsSub = client.subscribe(`/topic/search/${queryHash}/results`, (message) => {
    const payload = parseMessagePayload(message.body, "searchResultsEvent", SearchResultsEventSchema);
    if (!payload) {
      onError(new Error("Failed to parse search results event"));
      return;
    }
    onResults(payload.newResults);
  });

  return () => {
    progressSub.unsubscribe();
    resultsSub.unsubscribe();
  };
}

export async function subscribeToBookCoverUpdates(
  bookId: string,
  onCoverUpdate: (coverUrl: string) => void,
  onError: (error: Error) => void,
): Promise<() => void> {
  const client = await ensureRealtimeClient();
  const sub = client.subscribe(`/topic/book/${bookId}/coverUpdate`, (message) => {
    const payload = parseMessagePayload(message.body, "bookCoverUpdateEvent", BookCoverUpdateEventSchema);
    if (!payload) {
      onError(new Error("Failed to parse book cover update event"));
      return;
    }
    if (payload.newCoverUrl) {
      onCoverUpdate(payload.newCoverUrl);
    }
  });

  return () => sub.unsubscribe();
}
