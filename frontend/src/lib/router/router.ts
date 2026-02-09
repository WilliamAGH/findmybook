import { writable } from "svelte/store";
import { getRouteManifest } from "$lib/services/pages";
import { RouteManifestSchema, type RouteDefinition, type RouteManifest } from "$lib/validation/schemas";
import {
  browserOrigin,
  buildSpaHistoryState,
  currentPathWithQueryAndHash,
  normalizeInternalPath,
  pathWithQueryAndHash,
  readSpaHistoryState,
  seedSpaHistoryStateIfMissing,
} from "$lib/router/routerHistoryState";

export type RouteName = "home" | "search" | "book" | "sitemap" | "explore" | "categories" | "notFound" | "error";
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

const ROUTE_MANIFEST_UNAVAILABLE_MESSAGE =
  "[router] Route manifest unavailable. Backend must embed window.__FMB_ROUTE_MANIFEST__ or expose /api/pages/routes.";

declare global {
  interface Window {
    __FMB_ROUTE_MANIFEST__?: RouteManifest;
  }
}

const INITIAL_URL =
  typeof window !== "undefined" ? new URL(window.location.href) : new URL("http://localhost/");
let activeRouteManifest: RouteManifest | null = embeddedRouteManifestFromWindow();
let routeManifestRefreshPromise: Promise<void> | null = null;

export const currentUrl = writable<URL>(INITIAL_URL);

export function previousSpaPath(): string | null {
  return readSpaHistoryState()?.previousPath ?? null;
}

function parsePage(value: string): number {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed >= 1 ? parsed : 1;
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

function requireRouteManifest(): RouteManifest {
  if (!activeRouteManifest) {
    throw new Error(ROUTE_MANIFEST_UNAVAILABLE_MESSAGE);
  }
  return activeRouteManifest;
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
        const errorMessage = error instanceof Error ? error.message : String(error);
        throw new Error(`${ROUTE_MANIFEST_UNAVAILABLE_MESSAGE} (${errorMessage})`);
      })
      .finally(() => {
        routeManifestRefreshPromise = null;
      });
  }
  await routeManifestRefreshPromise;
  if (!activeRouteManifest) {
    throw new Error(ROUTE_MANIFEST_UNAVAILABLE_MESSAGE);
  }
}

export function matchRoute(pathname: string): RouteMatch {
  const routeManifest = requireRouteManifest();
  for (const routeDefinition of routeManifest.publicRoutes) {
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
  const routeManifest = requireRouteManifest();
  const exactMatch = routeManifest.publicRoutes.find((routeDefinition) => (
    routeDefinition.name === routeName && routeDefinition.matchType === "exact"
  ));
  if (exactMatch?.pattern === "/explore" || exactMatch?.pattern === "/categories" || exactMatch?.pattern === "/search") {
    return exactMatch.pattern;
  }

  return routeName === "explore" ? "/explore" : routeName === "categories" ? "/categories" : "/search";
}

function shouldHandleAsSpaLink(anchor: HTMLAnchorElement): boolean {
  if (!activeRouteManifest) {
    return false;
  }
  const routeManifest = activeRouteManifest;

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

  for (const prefix of routeManifest.passthroughPrefixes) {
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
  const nextState = buildSpaHistoryState(previousPath);
  if (replace) {
    window.history.replaceState(nextState, "", targetPath);
  } else {
    window.history.pushState(nextState, "", targetPath);
  }
  publishUrl();
}

export function initializeSpaRouting(): () => void {
  initializeRouteManifest().catch((error) => {
    console.error("[router] Failed to initialize route manifest:", error);
  });
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
