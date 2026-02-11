import { searchBasePathForRoute, type SearchRouteName } from "$lib/router/router";
import type { SearchParams } from "$lib/services/books";
import type { PopularWindow } from "$lib/services/pages";
import { EXPLORE_DEFAULT_POPULAR_WINDOW, parsePopularWindow } from "$lib/services/explorePopular";
import {
  COVER_OPTIONS,
  RESOLUTION_OPTIONS,
  SORT_OPTIONS,
  categoryQueryFromGenres,
  dedupeGenres,
  parseEnumParam,
  parsePositiveNumber,
  startIndexFromPage,
  type CoverOption,
  type ResolutionOption,
  type SortOption,
} from "$lib/services/searchConfig";
import type { BookCardDisplay } from "$lib/components/BookCard.svelte";
import type { SearchHit } from "$lib/validation/schemas";

const EXPLORE_DEFAULT_QUERY_PARAMS: ReadonlyArray<readonly [string, string]> = [
  ["page", "1"],
  ["orderBy", "newest"],
  ["view", "grid"],
  ["coverSource", "ANY"],
  ["resolution", "HIGH_FIRST"],
] as const;

export interface SearchPageRouteState {
  readonly query: string;
  readonly page: number;
  readonly orderBy: SortOption;
  readonly coverSource: CoverOption;
  readonly resolution: ResolutionOption;
  readonly viewMode: "grid" | "list";
  readonly selectedGenres: string[];
  readonly explorePopularWindow: PopularWindow;
}

interface BuildSearchRouteUrlArgs {
  readonly routeName: SearchRouteName;
  readonly origin: string;
  readonly query: string;
  readonly page: number;
  readonly orderBy: SortOption;
  readonly coverSource: CoverOption;
  readonly resolution: ResolutionOption;
  readonly viewMode: "grid" | "list";
  readonly selectedGenres: string[];
  readonly explorePopularWindow: PopularWindow;
}

interface BuildBookDetailHrefArgs {
  readonly hitId: string;
  readonly hitSlug: string | null | undefined;
  readonly query: string;
  readonly routeName: SearchRouteName;
  readonly explorePopularWindow: PopularWindow;
  readonly page: number;
  readonly orderBy: SortOption;
  readonly viewMode: "grid" | "list";
}

export function readSearchPageRouteState(routeName: SearchRouteName, url: URL): SearchPageRouteState {
  const params = url.searchParams;
  const page = parsePositiveNumber(params.get("page"), 1);
  const orderBy = parseEnumParam(params, "orderBy", SORT_OPTIONS, "newest");
  const coverSource = parseEnumParam(params, "coverSource", COVER_OPTIONS, "ANY");
  const resolution = parseEnumParam(params, "resolution", RESOLUTION_OPTIONS, "HIGH_FIRST");
  const viewMode = params.get("view") === "list" ? "list" : "grid";
  const selectedGenres = routeName === "categories" ? dedupeGenres(params.getAll("genre")) : [];
  const query = routeName === "categories" ? categoryQueryFromGenres(selectedGenres) : params.get("query")?.trim() ?? "";
  const explorePopularWindow = routeName === "explore"
    ? parsePopularWindow(params.get("popularWindow"))
    : EXPLORE_DEFAULT_POPULAR_WINDOW;

  return {
    query,
    page,
    orderBy,
    coverSource,
    resolution,
    viewMode,
    selectedGenres,
    explorePopularWindow,
  };
}

export function buildExploreDefaultUrl(routeName: SearchRouteName, url: URL): string | null {
  if (routeName !== "explore") return null;
  const currentQuery = url.searchParams.get("query")?.trim();
  if (currentQuery && currentQuery.length > 0) return null;

  const normalizedWindow = parsePopularWindow(url.searchParams.get("popularWindow"));
  const params = new URLSearchParams(url.searchParams);
  let changed = false;
  if (params.get("popularWindow") !== normalizedWindow) {
    params.set("popularWindow", normalizedWindow);
    changed = true;
  }
  for (const [key, value] of EXPLORE_DEFAULT_QUERY_PARAMS) {
    if (!params.has(key)) {
      params.set(key, value);
      changed = true;
    }
  }

  if (!changed) return null;
  return `${searchBasePathForRoute(routeName)}?${params.toString()}`;
}

export function createSearchParams(
  query: string,
  page: number,
  orderBy: SortOption,
  coverSource: CoverOption,
  resolution: ResolutionOption,
  pageSize: number,
): SearchParams {
  return {
    query,
    startIndex: startIndexFromPage(page, pageSize),
    maxResults: pageSize,
    orderBy,
    coverSource,
    resolution,
  };
}

export function searchParamsCacheKey(params: SearchParams): string {
  return JSON.stringify({
    query: params.query,
    startIndex: params.startIndex,
    maxResults: params.maxResults,
    orderBy: params.orderBy,
    coverSource: params.coverSource,
    resolution: params.resolution,
  });
}

export function buildSearchRouteUrl(args: BuildSearchRouteUrlArgs): string {
  const {
    routeName,
    origin,
    query,
    page,
    orderBy,
    coverSource,
    resolution,
    viewMode,
    selectedGenres,
    explorePopularWindow,
  } = args;

  const url = new URL(searchBasePathForRoute(routeName), origin);
  if (routeName === "categories") {
    for (const genre of selectedGenres) url.searchParams.append("genre", genre);
  } else if (routeName === "explore") {
    url.searchParams.set("popularWindow", explorePopularWindow);
    if (query.trim()) url.searchParams.set("query", query.trim());
  } else if (query.trim()) {
    url.searchParams.set("query", query.trim());
  }
  url.searchParams.set("page", String(page));
  url.searchParams.set("orderBy", orderBy);
  url.searchParams.set("view", viewMode);
  url.searchParams.set("coverSource", coverSource);
  url.searchParams.set("resolution", resolution);
  return `${url.pathname}${url.search}`;
}

export function mapSearchHitToBookCard(hit: SearchHit): BookCardDisplay {
  const authors = hit.authors.map((author) => author.name).filter((name) => name.length > 0);
  return {
    id: hit.id,
    slug: hit.slug ?? hit.id,
    title: hit.title ?? "Untitled",
    authors,
    description: hit.descriptionContent?.text ?? hit.description,
    coverUrl: hit.cover?.preferredUrl ?? hit.cover?.s3ImagePath ?? hit.cover?.externalImageUrl ?? null,
    fallbackCoverUrl: hit.cover?.fallbackUrl ?? "/images/placeholder-book-cover.svg",
  };
}

export function buildBookDetailHref(args: BuildBookDetailHrefArgs): string {
  const {
    hitId,
    hitSlug,
    query,
    routeName,
    explorePopularWindow,
    page,
    orderBy,
    viewMode,
  } = args;

  const routeIdentifier = hitSlug ?? hitId;
  const params = new URLSearchParams();
  params.set("bookId", hitId);
  if (query.trim()) {
    params.set("query", query);
  } else if (routeName === "explore") {
    params.set("popularWindow", explorePopularWindow);
  }
  params.set("page", String(page));
  params.set("orderBy", orderBy);
  params.set("view", viewMode);
  return `/book/${encodeURIComponent(routeIdentifier)}?${params.toString()}`;
}
