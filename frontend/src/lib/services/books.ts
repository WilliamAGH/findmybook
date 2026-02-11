/**
 * Book data access layer — search, detail, similar books, and affiliate links.
 *
 * All data flows through the backend HTTP API with typed Zod validation at the boundary.
 * Cover persistence and realtime search-hit normalization live in dedicated modules;
 * re-exported here for backward compatibility with existing importers.
 */
import { getJson } from "$lib/services/http";
import type { SortOption, TimeWindow } from "$lib/services/searchConfig";
import {
  type Book,
  type BookAiContentQueueStats,
  type SearchResponse,
  BookAiContentQueueStatsSchema,
  BookSchema,
  SearchResponseSchema,
  SimilarBooksSchema,
  AffiliateLinksSchema,
} from "$lib/validation/schemas";

export { persistRenderedCover, type PersistRenderedCoverRequest } from "$lib/services/coverPersistence";
export { normalizeRealtimeSearchHits, mergeSearchHits } from "$lib/services/searchHitNormalization";

const DEFAULT_SIMILAR_BOOKS_LIMIT = 8;
const inFlightSearchRequests = new Map<string, Promise<SearchResponse>>();

export interface SearchParams {
  query: string;
  /**
   * Zero-based absolute offset expected by `/api/books/search`.
   * UI routes use one-based `page`, which must be converted before calling this API.
   */
  startIndex: number;
  /**
   * Page size requested for the current offset window.
   */
  maxResults: number;
  orderBy: SortOption;
  coverSource: string;
  resolution: string;
  publishedYear?: number | null;
}

function buildSearchUrl(params: SearchParams): string {
  const url = new URL("/api/books/search", window.location.origin);
  url.searchParams.set("query", params.query);
  url.searchParams.set("startIndex", String(params.startIndex));
  url.searchParams.set("maxResults", String(params.maxResults));
  url.searchParams.set("orderBy", params.orderBy);
  url.searchParams.set("coverSource", params.coverSource);
  url.searchParams.set("resolution", params.resolution);

  if (params.publishedYear != null) {
    url.searchParams.set("publishedYear", String(params.publishedYear));
  }

  return `${url.pathname}${url.search}`;
}

export function searchBooks(params: SearchParams): Promise<SearchResponse> {
  const requestUrl = buildSearchUrl(params);
  const existingRequest = inFlightSearchRequests.get(requestUrl);
  if (existingRequest) {
    return existingRequest;
  }

  const request = getJson(requestUrl, SearchResponseSchema, "searchBooks")
    .finally(() => {
      inFlightSearchRequests.delete(requestUrl);
    });

  inFlightSearchRequests.set(requestUrl, request);
  return request;
}

export type ViewWindow = TimeWindow;

export function getBook(identifier: string, viewWindow?: ViewWindow): Promise<Book> {
  const params = viewWindow ? `?viewWindow=${viewWindow}` : "";
  return getJson(`/api/books/${encodeURIComponent(identifier)}${params}`, BookSchema, `getBook:${identifier}`);
}

export function getSimilarBooks(identifier: string, limit = DEFAULT_SIMILAR_BOOKS_LIMIT): Promise<Book[]> {
  return getJson(
    `/api/books/${encodeURIComponent(identifier)}/similar?limit=${limit}`,
    SimilarBooksSchema,
    `getSimilarBooks:${identifier}`,
  );
}

export function getAffiliateLinks(identifier: string): Promise<Record<string, string>> {
  return getJson(
    `/api/pages/book/${encodeURIComponent(identifier)}/affiliate-links`,
    AffiliateLinksSchema,
    `getAffiliateLinks:${identifier}`,
  );
}

export function getBookAiContentQueueStats(): Promise<BookAiContentQueueStats> {
  return getJson("/api/books/ai/content/queue", BookAiContentQueueStatsSchema, "getBookAiContentQueueStats");
}
