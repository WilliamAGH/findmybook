/**
 * Book data access layer — search, detail, similar books, and affiliate links.
 *
 * All data flows through the backend HTTP API with typed Zod validation at the boundary.
 * Includes normalizers for realtime search hits received via WebSocket.
 */
import { getJson } from "$lib/services/http";
import { validateWithSchema } from "$lib/validation/validate";
import {
  type Book,
  type SearchHit,
  type SearchResponse,
  BookSchema,
  SearchResponseSchema,
  SimilarBooksSchema,
  AffiliateLinksSchema,
  RealtimeSearchHitCandidateSchema,
} from "$lib/validation/schemas";

const DEFAULT_SIMILAR_BOOKS_LIMIT = 6;
const inFlightSearchRequests = new Map<string, Promise<SearchResponse>>();

export interface SearchParams {
  query: string;
  startIndex: number;
  maxResults: number;
  orderBy: string;
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

export function getBook(identifier: string): Promise<Book> {
  return getJson(`/api/books/${encodeURIComponent(identifier)}`, BookSchema, `getBook:${identifier}`);
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

function normalizeAuthorNames(rawAuthors: string[]): Array<{ id: string | null; name: string }> {
  return rawAuthors.map((name) => ({ id: null, name }));
}

function hasCover(hit: SearchHit): boolean {
  return Boolean(
    hit.cover?.preferredUrl
      || hit.cover?.externalImageUrl
      || hit.cover?.s3ImagePath
      || hit.cover?.fallbackUrl,
  );
}

function normalizeProvider(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }
  return value.trim().toUpperCase();
}

function resolveProvider(hit: SearchHit): string {
  const sourceFromPayload = normalizeProvider(hit.source);
  if (sourceFromPayload) {
    return sourceFromPayload;
  }

  const sourceFromCover = normalizeProvider(hit.cover?.source);
  if (sourceFromCover) {
    return sourceFromCover;
  }

  const coverUrl =
    hit.cover?.preferredUrl
    ?? hit.cover?.externalImageUrl
    ?? hit.cover?.fallbackUrl
    ?? null;
  const normalizedUrl = coverUrl?.toLowerCase() ?? "";
  if (normalizedUrl.includes("covers.openlibrary.org")) {
    return "OPEN_LIBRARY";
  }
  if (normalizedUrl.includes("googleusercontent.com") || normalizedUrl.includes("books.google")) {
    return "GOOGLE_BOOKS";
  }
  if (hit.cover?.s3ImagePath) {
    return "POSTGRES";
  }

  return "POSTGRES";
}

function sourceRank(hit: SearchHit): number {
  const provider = resolveProvider(hit);
  if (provider === "POSTGRES") {
    return 0;
  }
  if (provider === "OPEN_LIBRARY" || provider === "OPEN_LIBRARY_API") {
    return 1;
  }
  if (provider === "GOOGLE_BOOKS" || provider === "GOOGLE_BOOKS_API" || provider === "GOOGLE_API") {
    return 2;
  }
  return 3;
}

function sortSearchHits(hits: SearchHit[]): SearchHit[] {
  return hits
    .map((hit, index) => ({ hit, index }))
    .sort((left, right) => {
      const coverRankDelta = Number(hasCover(right.hit)) - Number(hasCover(left.hit));
      if (coverRankDelta !== 0) {
        return coverRankDelta;
      }

      const sourceRankDelta = sourceRank(left.hit) - sourceRank(right.hit);
      if (sourceRankDelta !== 0) {
        return sourceRankDelta;
      }

      return left.index - right.index;
    })
    .map((entry) => entry.hit);
}

/**
 * Build a fully-typed Cover object from a loosely-typed realtime candidate cover payload.
 * Fills missing fields with null to satisfy the SearchHit cover contract.
 */
function buildNormalizedCover(raw: Record<string, unknown>): SearchHit["cover"] {
  return {
    s3ImagePath: (raw.s3ImagePath as string) ?? null,
    externalImageUrl: (raw.externalImageUrl as string) ?? null,
    width: null,
    height: null,
    highResolution: null,
    preferredUrl: (raw.preferredUrl as string) ?? null,
    fallbackUrl: (raw.fallbackUrl as string) ?? null,
    source: (raw.source as string) ?? null,
  };
}

function normalizeSearchHit(raw: unknown): SearchHit | null {
  const validation = validateWithSchema(RealtimeSearchHitCandidateSchema, raw, "realtimeSearchHit");
  if (!validation.success) {
    console.warn("Dropped unparseable realtime search hit", raw);
    return null;
  }

  const candidate = validation.data;
  const publishedDate = candidate.publishedDate ?? null;

  return {
    id: candidate.id,
    slug: candidate.slug ?? candidate.id,
    title: candidate.title ?? "Untitled",
    source: candidate.source ?? null,
    description: candidate.description ?? null,
    publication: {
      publishedDate,
      language: candidate.language ?? null,
      pageCount: candidate.pageCount ?? null,
      publisher: candidate.publisher ?? null,
    },
    authors: normalizeAuthorNames(candidate.authors),
    categories: candidate.categories,
    collections: [],
    tags: [],
    cover: buildNormalizedCover(candidate.cover ?? {}),
    editions: [],
    recommendationIds: [],
    extras: {},
    matchType: candidate.matchType ?? null,
    relevanceScore: candidate.relevanceScore ?? null,
  };
}

export function normalizeRealtimeSearchHits(incoming: unknown[]): SearchHit[] {
  return incoming
    .map(normalizeSearchHit)
    .filter((item): item is SearchHit => item !== null);
}

export function mergeSearchHits(existingHits: SearchHit[], incomingHits: SearchHit[]): SearchHit[] {
  const merged = new Map(existingHits.map((hit) => [hit.id, hit]));
  for (const candidate of incomingHits) {
    if (!merged.has(candidate.id)) {
      merged.set(candidate.id, candidate);
    }
  }
  return sortSearchHits(Array.from(merged.values()));
}
