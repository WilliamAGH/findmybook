import { getJson } from "$lib/services/http";
import { type HomePayload, type SitemapPayload, HomePayloadSchema, SitemapPayloadSchema } from "$lib/validation/schemas";

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
