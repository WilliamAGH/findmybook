import { Client, type IFrame } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { z } from "zod/v4";
import {
  BookCoverUpdateEventSchema,
  SearchProgressEventSchema,
  SearchResultsEventSchema,
} from "$lib/validation/schemas";
import { validateWithSchema } from "$lib/validation/validate";

let sharedClient: Client | null = null;
let connectionPromise: Promise<Client> | null = null;

function buildClient(): Client {
  return new Client({
    webSocketFactory: () => new SockJS("/ws"),
    reconnectDelay: 5000,
    heartbeatIncoming: 20000,
    heartbeatOutgoing: 10000,
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
  if (sharedClient?.connected) {
    return sharedClient;
  }

  if (!connectionPromise) {
    connectionPromise = new Promise<Client>((resolve, reject) => {
      const client = buildClient();
      client.onConnect = (_frame: IFrame) => {
        sharedClient = client;
        resolve(client);
      };
      client.onStompError = (frame) => {
        reject(new Error(frame.body || "STOMP connection failed"));
      };
      client.onWebSocketError = (event) => {
        console.error("WebSocket error", event);
      };
      client.activate();
    }).finally(() => {
      connectionPromise = null;
    });
  }

  const pendingConnection = connectionPromise;
  if (!pendingConnection) {
    throw new Error("Realtime connection was not initialized");
  }

  return pendingConnection;
}

export async function subscribeToSearchTopics(
  queryHash: string,
  onProgress: (message: string) => void,
  onResults: (results: unknown[]) => void,
): Promise<() => void> {
  const client = await ensureRealtimeClient();

  const progressSub = client.subscribe(`/topic/search/${queryHash}/progress`, (message) => {
    const payload = parseMessagePayload(message.body, "searchProgressEvent", SearchProgressEventSchema);
    if (!payload) {
      onProgress("Searching...");
      return;
    }
    onProgress(payload.message ?? "Searching...");
  });

  const resultsSub = client.subscribe(`/topic/search/${queryHash}/results`, (message) => {
    const payload = parseMessagePayload(message.body, "searchResultsEvent", SearchResultsEventSchema);
    if (!payload) {
      onResults([]);
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
): Promise<() => void> {
  const client = await ensureRealtimeClient();
  const sub = client.subscribe(`/topic/book/${bookId}/coverUpdate`, (message) => {
    const payload = parseMessagePayload(message.body, "bookCoverUpdateEvent", BookCoverUpdateEventSchema);
    if (!payload) {
      return;
    }
    if (payload.newCoverUrl) {
      onCoverUpdate(payload.newCoverUrl);
    }
  });

  return () => sub.unsubscribe();
}
