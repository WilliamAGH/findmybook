/**
 * Realtime search-hit normalization, sorting, and merging.
 *
 * Converts loosely-typed WebSocket payloads into fully-typed SearchHit objects
 * and applies multi-key stable sorting used by search result display.
 *
 * Extracted from the book data access layer to keep each service module
 * single-purpose and within the repository file-size ceiling.
 */
import { z } from "zod/v4";
import type { SortOption } from "$lib/services/searchConfig";
import { validateWithSchema } from "$lib/validation/validate";
import {
  type SearchHit,
  RealtimeSearchHitCandidateSchema,
} from "$lib/validation/schemas";

type RealtimeCoverPayload = NonNullable<
  z.infer<typeof RealtimeSearchHitCandidateSchema>["cover"]
>;

const FALLBACK_BOOK_TITLE = "Untitled";

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

/**
 * Stable multi-key sort for search hits. Primary key varies by orderBy;
 * tiebreakers are cover presence, source rank, relevance, then original index.
 */
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

      // Recency bias: prefer more recently published books as tiebreaker
      if (orderBy !== "newest") {
        const recencyDelta = publishedTimestamp(right.hit) - publishedTimestamp(left.hit);
        if (recencyDelta !== 0) {
          return recencyDelta;
        }
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
 * Build a fully-typed Cover object from a Zod-validated realtime candidate cover payload.
 * Fills missing fields with null to satisfy the SearchHit cover contract.
 */
function buildNormalizedCover(raw: RealtimeCoverPayload): SearchHit["cover"] {
  return {
    s3ImagePath: raw.s3ImagePath ?? null,
    externalImageUrl: raw.externalImageUrl ?? null,
    width: null,
    height: null,
    highResolution: null,
    preferredUrl: raw.preferredUrl ?? null,
    fallbackUrl: raw.fallbackUrl ?? null,
    source: raw.source ?? null,
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
    title: candidate.title ?? FALLBACK_BOOK_TITLE,
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
    cover: candidate.cover ? buildNormalizedCover(candidate.cover) : {
      s3ImagePath: null,
      externalImageUrl: null,
      width: null,
      height: null,
      highResolution: null,
      preferredUrl: null,
      fallbackUrl: null,
      source: null,
    },
    editions: [],
    recommendationIds: [],
    extras: {},
    matchType: candidate.matchType ?? null,
    relevanceScore: candidate.relevanceScore ?? null,
  };
}

/** Converts an array of loosely-typed WebSocket payloads into validated SearchHit objects. */
export function normalizeRealtimeSearchHits(incoming: unknown[]): SearchHit[] {
  return incoming
    .map(normalizeSearchHit)
    .filter((item): item is SearchHit => item !== null);
}

/**
 * Merges incoming realtime hits into an existing result set, deduplicating by id.
 * Existing backend results are preserved; only genuinely new hits are added.
 * This prevents structurally incomplete WebSocket payloads from overwriting
 * full backend results that contain populated collections, tags, and editions.
 */
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
