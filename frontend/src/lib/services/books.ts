/**
 * Book data access layer — search, detail, similar books, and affiliate links.
 *
 * All data flows through the backend HTTP API with typed Zod validation at the boundary.
 * Includes normalizers for realtime search hits received via WebSocket.
 */
import { getJson, postMultipart } from "$lib/services/http";
import type { SortOption, TimeWindow } from "$lib/services/searchConfig";
import { validateWithSchema } from "$lib/validation/validate";
import {
  type Book,
  type BookAiContentQueueStats,
  type SearchHit,
  type SearchResponse,
  BookAiContentQueueStatsSchema,
  BookSchema,
  SearchResponseSchema,
  SimilarBooksSchema,
  AffiliateLinksSchema,
  RealtimeSearchHitCandidateSchema,
} from "$lib/validation/schemas";
import {
  type CoverIngestResponse,
  CoverIngestResponseSchema,
} from "$lib/validation/coverSchemas";

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

export interface PersistRenderedCoverRequest {
  identifier: string;
  renderedCoverUrl: string;
  source?: string | null;
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

function imageFileExtensionFromMimeType(contentType: string): string {
  const normalized = contentType.split(";")[0]?.trim().toLowerCase() ?? "";
  if (normalized === "image/png") {
    return ".png";
  }
  if (normalized === "image/webp") {
    return ".webp";
  }
  if (normalized === "image/gif") {
    return ".gif";
  }
  if (normalized === "image/svg+xml") {
    return ".svg";
  }
  if (normalized === "image/jpeg" || normalized === "image/jpg") {
    return ".jpg";
  }
  return ".jpg";
}

function normalizeHttpCoverUrl(rawUrl: string): string {
  const parsed = new URL(rawUrl, window.location.origin);
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(`persistRenderedCover received unsupported protocol: ${parsed.protocol}`);
  }
  return parsed.href;
}

function normalizeCoverSource(source: string | null | undefined): string | null {
  if (!source) {
    return null;
  }
  const trimmed = source.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export async function persistRenderedCover(request: PersistRenderedCoverRequest): Promise<CoverIngestResponse> {
  const normalizedCoverUrl = normalizeHttpCoverUrl(request.renderedCoverUrl);
  const imageResponse = await fetch(normalizedCoverUrl, {
    method: "GET",
    mode: "cors",
    credentials: "omit",
    cache: "force-cache",
  });

  if (!imageResponse.ok) {
    throw new Error(`persistRenderedCover failed to fetch image bytes (${imageResponse.status})`);
  }

  const contentType = imageResponse.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("image/")) {
    throw new Error(`persistRenderedCover expected image/* response but received '${contentType}'`);
  }

  const imageBlob = await imageResponse.blob();
  if (imageBlob.size <= 0) {
    throw new Error("persistRenderedCover fetched an empty image blob");
  }

  const formData = new FormData();
  formData.append("image", imageBlob, `cover${imageFileExtensionFromMimeType(contentType)}`);
  formData.append("sourceUrl", normalizedCoverUrl);
  const normalizedSource = normalizeCoverSource(request.source);
  if (normalizedSource) {
    formData.append("source", normalizedSource);
  }

  return postMultipart(
    `/api/covers/${encodeURIComponent(request.identifier)}/ingest`,
    formData,
    CoverIngestResponseSchema,
    `persistRenderedCover:${request.identifier}`,
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

function publishedTimestamp(hit: SearchHit): number {
  const publishedDate = hit.publication?.publishedDate ?? null;
  if (publishedDate == null) {
    return Number.MIN_SAFE_INTEGER;
  }
  if (typeof publishedDate === "number") {
    return Number.isFinite(publishedDate) ? publishedDate : Number.MIN_SAFE_INTEGER;
  }
  const parsedDate = new Date(publishedDate);
  return Number.isNaN(parsedDate.getTime()) ? Number.MIN_SAFE_INTEGER : parsedDate.getTime();
}

function primaryAuthorName(hit: SearchHit): string {
  const name = hit.authors[0]?.name ?? "";
  return name.trim().toLowerCase();
}

function relevanceScore(hit: SearchHit): number {
  const score = hit.relevanceScore ?? 0;
  return Number.isFinite(score) ? score : 0;
}

function sortSearchHits(hits: SearchHit[], orderBy: SortOption): SearchHit[] {
  return hits
    .map((hit, index) => ({ hit, index }))
    .sort((left, right) => {
      if (orderBy === "newest") {
        const publishedDelta = publishedTimestamp(right.hit) - publishedTimestamp(left.hit);
        if (publishedDelta !== 0) {
          return publishedDelta;
        }
      } else if (orderBy === "title") {
        const titleDelta = (left.hit.title ?? "").localeCompare(right.hit.title ?? "", undefined, { sensitivity: "base" });
        if (titleDelta !== 0) {
          return titleDelta;
        }
      } else if (orderBy === "author") {
        const authorDelta = primaryAuthorName(left.hit).localeCompare(primaryAuthorName(right.hit), undefined, { sensitivity: "base" });
        if (authorDelta !== 0) {
          return authorDelta;
        }
      } else {
        const relevanceDelta = relevanceScore(right.hit) - relevanceScore(left.hit);
        if (relevanceDelta !== 0) {
          return relevanceDelta;
        }
      }

      const coverRankDelta = Number(hasCover(right.hit)) - Number(hasCover(left.hit));
      if (coverRankDelta !== 0) {
        return coverRankDelta;
      }

      const sourceRankDelta = sourceRank(left.hit) - sourceRank(right.hit);
      if (sourceRankDelta !== 0) {
        return sourceRankDelta;
      }

      if (orderBy !== "relevance") {
        const relevanceDelta = relevanceScore(right.hit) - relevanceScore(left.hit);
        if (relevanceDelta !== 0) {
          return relevanceDelta;
        }
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

export function mergeSearchHits(
  existingHits: SearchHit[],
  incomingHits: SearchHit[],
  orderBy: SortOption,
): SearchHit[] {
  const merged = new Map(existingHits.map((hit) => [hit.id, hit]));
  for (const candidate of incomingHits) {
    if (!merged.has(candidate.id)) {
      merged.set(candidate.id, candidate);
    }
  }
  return sortSearchHits(Array.from(merged.values()), orderBy);
}
