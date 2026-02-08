import { getJson } from "$lib/services/http";
import {
  type HomePayload,
  type SitemapPayload,
  type CategoriesFacetsPayload,
  type PageMetadata,
  type RouteManifest,
  HomePayloadSchema,
  SitemapPayloadSchema,
  CategoriesFacetsPayloadSchema,
  PageMetadataSchema,
  RouteManifestSchema,
} from "$lib/validation/schemas";

export function getHomePagePayload(): Promise<HomePayload> {
  return getJson("/api/pages/home", HomePayloadSchema, "getHomePagePayload");
}

export function getSitemapPayload(viewType: "authors" | "books", letter: string, pageNumber: number): Promise<SitemapPayload> {
  const url = new URL("/api/pages/sitemap", window.location.origin);
  url.searchParams.set("view", viewType);
  url.searchParams.set("letter", letter);
  url.searchParams.set("page", String(pageNumber));
  return getJson(`${url.pathname}${url.search}`, SitemapPayloadSchema, "getSitemapPayload");
}

export function getCategoryFacets(limit = 24, minBooks = 1): Promise<CategoriesFacetsPayload> {
  const url = new URL("/api/pages/categories/facets", window.location.origin);
  url.searchParams.set("limit", String(limit));
  url.searchParams.set("minBooks", String(minBooks));
  return getJson(`${url.pathname}${url.search}`, CategoriesFacetsPayloadSchema, "getCategoryFacets");
}

export function getPageMetadata(path: string): Promise<PageMetadata> {
  const url = new URL("/api/pages/meta", window.location.origin);
  url.searchParams.set("path", path);
  return getJson(`${url.pathname}${url.search}`, PageMetadataSchema, "getPageMetadata");
}

export function getRouteManifest(): Promise<RouteManifest> {
  return getJson("/api/pages/routes", RouteManifestSchema, "getRouteManifest");
}
