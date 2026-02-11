import { startIndexFromPage } from "$lib/services/searchConfig";
import type { PopularWindow } from "$lib/services/pages";
import type {
  BookCard as ApiBookCard,
  SearchHit,
  SearchResponse,
} from "$lib/validation/schemas";

export const EXPLORE_DEFAULT_POPULAR_WINDOW: PopularWindow = "90d";
export const EXPLORE_POPULAR_LIMIT = 24;

export function parsePopularWindow(rawWindow: string | null): PopularWindow {
  if (rawWindow === "30d" || rawWindow === "90d" || rawWindow === "all") {
    return rawWindow;
  }
  return EXPLORE_DEFAULT_POPULAR_WINDOW;
}

export function popularWindowLabel(window: PopularWindow): string {
  if (window === "all") return "all time";
  if (window === "30d") return "last 30 days";
  return "last 90 days";
}

function popularMatchType(window: PopularWindow): string {
  if (window === "30d") return "POPULAR_30D";
  if (window === "all") return "POPULAR_ALL";
  return "POPULAR_90D";
}

function mapPopularCardToSearchHit(card: ApiBookCard, window: PopularWindow): SearchHit {
  const authors = (card.authors ?? []).map((name) => ({ id: null, name }));
  return {
    id: card.id,
    slug: card.slug ?? card.id,
    title: card.title,
    source: "POSTGRES",
    description: null,
    publication: null,
    authors,
    categories: [],
    collections: [],
    tags: [],
    cover: {
      s3ImagePath: card.cover_s3_key ?? null,
      externalImageUrl: card.cover_url ?? null,
      width: null,
      height: null,
      highResolution: null,
      preferredUrl: card.cover_url ?? null,
      fallbackUrl: card.fallback_cover_url ?? "/images/placeholder-book-cover.svg",
      source: "POSTGRES",
    },
    editions: [],
    recommendationIds: [],
    extras: {},
    aiContent: null,
    viewMetrics: null,
    matchType: popularMatchType(window),
    relevanceScore: card.ratings_count ?? null,
  };
}

export function buildExplorePopularSearchResponse(
  popularCards: ApiBookCard[],
  window: PopularWindow,
  page: number,
  pageSize: number,
): SearchResponse {
  const allPopularHits = popularCards.map((card) => mapPopularCardToSearchHit(card, window));
  const startIndex = startIndexFromPage(page, pageSize);
  const results = allPopularHits.slice(startIndex, startIndex + pageSize);
  const hasMore = startIndex + pageSize < allPopularHits.length;

  return {
    query: `popular:${window}`,
    queryHash: `popular-${window}`,
    startIndex,
    maxResults: pageSize,
    totalResults: allPopularHits.length,
    hasMore,
    nextStartIndex: hasMore ? startIndex + pageSize : startIndex,
    prefetchedCount: 0,
    orderBy: "relevance",
    coverSource: "ANY",
    resolution: "HIGH_FIRST",
    results,
  };
}
