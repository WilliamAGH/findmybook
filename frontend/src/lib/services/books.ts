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

export interface SearchParams {
  query: string;
  startIndex: number;
  maxResults: number;
  orderBy: string;
  coverSource: string;
  resolution: string;
  publishedYear: number | null;
}

function buildSearchUrl(params: SearchParams): string {
  const url = new URL("/api/books/search", window.location.origin);
  url.searchParams.set("query", params.query);
  url.searchParams.set("startIndex", String(params.startIndex));
  url.searchParams.set("maxResults", String(params.maxResults));
  url.searchParams.set("orderBy", params.orderBy);
  url.searchParams.set("coverSource", params.coverSource);
  url.searchParams.set("resolution", params.resolution);

  if (params.publishedYear !== null) {
    url.searchParams.set("publishedYear", String(params.publishedYear));
  }

  return `${url.pathname}${url.search}`;
}

export function searchBooks(params: SearchParams): Promise<SearchResponse> {
  return getJson(buildSearchUrl(params), SearchResponseSchema, "searchBooks");
}

export function getBook(identifier: string): Promise<Book> {
  return getJson(`/api/books/${encodeURIComponent(identifier)}`, BookSchema, `getBook:${identifier}`);
}

export function getSimilarBooks(identifier: string, limit = 6): Promise<Book[]> {
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

function normalizeSearchHit(raw: unknown): SearchHit | null {
  const validation = validateWithSchema(RealtimeSearchHitCandidateSchema, raw, "realtimeSearchHit");
  if (!validation.success) {
    return null;
  }

  const candidate = validation.data;
  const cover = candidate.cover ?? {};
  const publishedDate = candidate.publishedDate ?? null;

  return {
    id: candidate.id,
    slug: candidate.slug ?? candidate.id,
    title: candidate.title ?? "Untitled",
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
    cover: {
      s3ImagePath: cover.s3ImagePath ?? null,
      externalImageUrl: cover.externalImageUrl ?? null,
      width: null,
      height: null,
      highResolution: null,
      preferredUrl: cover.preferredUrl ?? null,
      fallbackUrl: cover.fallbackUrl ?? null,
      source: cover.source ?? null,
    },
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
  return Array.from(merged.values());
}
