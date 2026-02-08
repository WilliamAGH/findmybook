import { writable } from "svelte/store";
import { getRouteManifest } from "$lib/services/pages";
import { RouteManifestSchema, type RouteDefinition, type RouteManifest } from "$lib/validation/schemas";

export type RouteName = "home" | "search" | "book" | "sitemap" | "explore" | "categories" | "notFound";

export interface RouteMatch {
  name: RouteName;
  params: {
    identifier?: string;
    view?: "authors" | "books";
    letter?: string;
    page?: number;
  };
}

export type SearchRouteName = "search" | "explore" | "categories";

export const DEFAULT_PASSTHROUGH_PREFIXES: readonly string[] = [
  "/api",
  "/admin",
  "/actuator",
  "/ws",
  "/topic",
  "/sitemap.xml",
  "/sitemap-xml",
  "/r",
];

const EMPTY_ROUTE_MANIFEST: RouteManifest = {
  version: 1,
  publicRoutes: [],
  passthroughPrefixes: [...DEFAULT_PASSTHROUGH_PREFIXES],
};

const SPA_HISTORY_MARKER = "findmybook-spa-v1";

interface SpaHistoryState {
  __fmbSpa: typeof SPA_HISTORY_MARKER;
  previousPath: string | null;
}

declare global {
  interface Window {
    __FMB_ROUTE_MANIFEST__?: RouteManifest;
  }
}

const INITIAL_URL =
  typeof window !== "undefined" ? new URL(window.location.href) : new URL("http://localhost/");
let activeRouteManifest = resolveInitialRouteManifest();
let routeManifestRefreshPromise: Promise<void> | null = null;

export const currentUrl = writable<URL>(INITIAL_URL);

function browserOrigin(): string {
  return typeof window !== "undefined" ? window.location.origin : "http://localhost";
}

function pathWithQueryAndHash(url: URL): string {
  return `${url.pathname}${url.search}${url.hash}`;
}

function currentPathWithQueryAndHash(): string {
  if (typeof window === "undefined") {
    return "/";
  }
  return pathWithQueryAndHash(new URL(window.location.href));
}

function normalizeInternalPath(candidate: string | null): string | null {
  if (!candidate) {
    return null;
  }
  try {
    const parsed = new URL(candidate, browserOrigin());
    if (parsed.origin !== browserOrigin()) {
      return null;
    }
    if (!parsed.pathname.startsWith("/")) {
      return null;
    }
    return pathWithQueryAndHash(parsed);
  } catch (error) {
    console.warn("[router] Failed to normalize internal path:", candidate, error);
    return null;
  }
}

function createSpaHistoryState(previousPath: string | null): SpaHistoryState {
  return {
    __fmbSpa: SPA_HISTORY_MARKER,
    previousPath,
  };
}

function readSpaHistoryState(): SpaHistoryState | null {
  if (typeof window === "undefined") {
    return null;
  }
  const state = window.history.state;
  if (!state || typeof state !== "object") {
    return null;
  }
  const candidate = state as { __fmbSpa?: string; previousPath?: unknown };
  if (candidate.__fmbSpa !== SPA_HISTORY_MARKER) {
    return null;
  }
  const previousPath = typeof candidate.previousPath === "string"
    ? normalizeInternalPath(candidate.previousPath)
    : null;
  return createSpaHistoryState(previousPath);
}

function seedSpaHistoryStateIfMissing(): void {
  if (typeof window === "undefined") {
    return;
  }
  if (readSpaHistoryState()) {
    return;
  }
  const currentPath = currentPathWithQueryAndHash();
  window.history.replaceState(createSpaHistoryState(null), "", currentPath);
}

export function previousSpaPath(): string | null {
  return readSpaHistoryState()?.previousPath ?? null;
}

function parsePage(value: string): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return 1;
  }
  return parsed;
}

function safeDecodeURIComponent(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch (error) {
    console.warn("[router] Failed to decode URI component:", value, error);
    return value;
  }
}

function embeddedRouteManifestFromWindow(): RouteManifest | null {
  if (typeof window === "undefined") {
    return null;
  }
  const manifestCandidate = RouteManifestSchema.safeParse(window.__FMB_ROUTE_MANIFEST__);
  return manifestCandidate.success ? manifestCandidate.data : null;
}

function resolveInitialRouteManifest(): RouteManifest {
  return embeddedRouteManifestFromWindow() ?? EMPTY_ROUTE_MANIFEST;
}

export function setRouteManifest(manifest: RouteManifest): void {
  activeRouteManifest = manifest;
  if (typeof window !== "undefined") {
    window.__FMB_ROUTE_MANIFEST__ = manifest;
  }
}

function paramsFromDefaults(defaults: Record<string, string>): RouteMatch["params"] {
  const params: RouteMatch["params"] = {};
  if (defaults.identifier) {
    params.identifier = safeDecodeURIComponent(defaults.identifier);
  }
  if (defaults.view === "authors" || defaults.view === "books") {
    params.view = defaults.view;
  }
  if (defaults.letter) {
    params.letter = safeDecodeURIComponent(defaults.letter);
  }
  if (defaults.page) {
    params.page = parsePage(defaults.page);
  }
  return params;
}

function paramsFromRegex(routeDefinition: RouteDefinition, match: RegExpMatchArray): RouteMatch["params"] {
  const params: RouteMatch["params"] = {};
  for (let index = 0; index < routeDefinition.paramNames.length; index += 1) {
    const paramName = routeDefinition.paramNames[index];
    const capturedValue = match[index + 1];
    if (!capturedValue) {
      continue;
    }
    if (paramName === "identifier") {
      params.identifier = safeDecodeURIComponent(capturedValue);
      continue;
    }
    if (paramName === "view") {
      params.view = capturedValue === "books" ? "books" : "authors";
      continue;
    }
    if (paramName === "letter") {
      params.letter = safeDecodeURIComponent(capturedValue);
      continue;
    }
    if (paramName === "page") {
      params.page = parsePage(capturedValue);
    }
  }
  return params;
}

export async function initializeRouteManifest(): Promise<void> {
  if (typeof window === "undefined") {
    return;
  }
  const embeddedManifest = embeddedRouteManifestFromWindow();
  if (embeddedManifest) {
    setRouteManifest(embeddedManifest);
    return;
  }
  if (!routeManifestRefreshPromise) {
    routeManifestRefreshPromise = getRouteManifest()
      .then((manifest) => {
        setRouteManifest(manifest);
      })
      .catch((error) => {
        console.warn("[router] Failed to load route manifest from /api/pages/routes", error);
      })
      .finally(() => {
        routeManifestRefreshPromise = null;
      });
  }
  await routeManifestRefreshPromise;
}

async function refreshRouteManifestFromApi(): Promise<void> {
  await initializeRouteManifest();
}

export function matchRoute(pathname: string): RouteMatch {
  for (const routeDefinition of activeRouteManifest.publicRoutes) {
    if (routeDefinition.matchType === "exact") {
      if (pathname !== routeDefinition.pattern) {
        continue;
      }
      return {
        name: routeDefinition.name,
        params: paramsFromDefaults(routeDefinition.defaults),
      };
    }

    if (routeDefinition.matchType === "regex") {
      const regex = new RegExp(routeDefinition.pattern);
      const matched = pathname.match(regex);
      if (!matched) {
        continue;
      }
      return {
        name: routeDefinition.name,
        params: paramsFromRegex(routeDefinition, matched),
      };
    }
  }

  return { name: "notFound", params: {} };
}

export function searchBasePathForRoute(routeName: SearchRouteName): "/search" | "/explore" | "/categories" {
  const exactMatch = activeRouteManifest.publicRoutes.find((routeDefinition) => (
    routeDefinition.name === routeName && routeDefinition.matchType === "exact"
  ));
  if (exactMatch?.pattern === "/explore" || exactMatch?.pattern === "/categories" || exactMatch?.pattern === "/search") {
    return exactMatch.pattern;
  }

  return routeName === "explore" ? "/explore" : routeName === "categories" ? "/categories" : "/search";
}

function shouldHandleAsSpaLink(anchor: HTMLAnchorElement): boolean {
  if (anchor.target && anchor.target !== "_self") {
    return false;
  }

  if (anchor.hasAttribute("download") || anchor.dataset.noSpa === "true") {
    return false;
  }

  const url = new URL(anchor.href, window.location.origin);
  if (url.origin !== window.location.origin) {
    return false;
  }

  for (const prefix of activeRouteManifest.passthroughPrefixes) {
    if (url.pathname === prefix || url.pathname.startsWith(`${prefix}/`)) {
      return false;
    }
  }

  return true;
}

function publishUrl(): void {
  currentUrl.set(new URL(window.location.href));
}

export function navigate(pathWithQuery: string, replace = false): void {
  if (typeof window === "undefined") {
    return;
  }
  seedSpaHistoryStateIfMissing();
  const target = new URL(pathWithQuery, browserOrigin());
  const targetPath = pathWithQueryAndHash(target);
  const previousPath = replace
    ? readSpaHistoryState()?.previousPath ?? null
    : normalizeInternalPath(currentPathWithQueryAndHash());
  const nextState = createSpaHistoryState(previousPath);
  if (replace) {
    window.history.replaceState(nextState, "", targetPath);
  } else {
    window.history.pushState(nextState, "", targetPath);
  }
  publishUrl();
}

export function initializeSpaRouting(): () => void {
  void refreshRouteManifestFromApi();
  seedSpaHistoryStateIfMissing();

  const onPopState = (): void => {
    publishUrl();
  };

  const onDocumentClick = (event: MouseEvent): void => {
    if (event.defaultPrevented || event.button !== 0) {
      return;
    }

    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }

    const target = event.target;
    if (!(target instanceof Element)) {
      return;
    }

    const anchor = target.closest("a[href]");
    if (!(anchor instanceof HTMLAnchorElement)) {
      return;
    }

    if (!shouldHandleAsSpaLink(anchor)) {
      return;
    }

    const url = new URL(anchor.href, window.location.origin);
    event.preventDefault();
    navigate(`${url.pathname}${url.search}${url.hash}`);
  };

  window.addEventListener("popstate", onPopState);
  document.addEventListener("click", onDocumentClick);

  return () => {
    window.removeEventListener("popstate", onPopState);
    document.removeEventListener("click", onDocumentClick);
  };
}
