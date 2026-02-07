import { writable } from "svelte/store";

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

const INITIAL_URL =
  typeof window !== "undefined" ? new URL(window.location.href) : new URL("http://localhost/");

export const currentUrl = writable<URL>(INITIAL_URL);

function parsePage(value: string): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return 1;
  }
  return parsed;
}

export function matchRoute(pathname: string): RouteMatch {
  if (pathname === "/") {
    return { name: "home", params: {} };
  }

  if (pathname === "/search") {
    return { name: "search", params: {} };
  }

  if (pathname === "/explore") {
    return { name: "explore", params: {} };
  }

  if (pathname === "/categories") {
    return { name: "categories", params: {} };
  }

  const bookMatch = pathname.match(/^\/book\/([^/]+)$/);
  if (bookMatch) {
    return {
      name: "book",
      params: {
        identifier: decodeURIComponent(bookMatch[1]),
      },
    };
  }

  if (pathname === "/sitemap") {
    return {
      name: "sitemap",
      params: {
        view: "authors",
        letter: "A",
        page: 1,
      },
    };
  }

  const sitemapMatch = pathname.match(/^\/sitemap\/(authors|books)\/([^/]+)\/(\d+)$/);
  if (sitemapMatch) {
    return {
      name: "sitemap",
      params: {
        view: sitemapMatch[1] === "books" ? "books" : "authors",
        letter: decodeURIComponent(sitemapMatch[2]),
        page: parsePage(sitemapMatch[3]),
      },
    };
  }

  if (pathname === "/404") {
    return { name: "notFound", params: {} };
  }

  return { name: "notFound", params: {} };
}

export function searchBasePathForRoute(routeName: SearchRouteName): "/search" | "/explore" | "/categories" {
  if (routeName === "explore") {
    return "/explore";
  }

  if (routeName === "categories") {
    return "/categories";
  }

  return "/search";
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

  const passthroughPrefixes = ["/api", "/admin", "/actuator", "/ws", "/topic", "/sitemap.xml", "/sitemap-xml", "/r"];

  for (const prefix of passthroughPrefixes) {
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
  const target = new URL(pathWithQuery, window.location.origin);
  if (replace) {
    window.history.replaceState(null, "", `${target.pathname}${target.search}${target.hash}`);
  } else {
    window.history.pushState(null, "", `${target.pathname}${target.search}${target.hash}`);
  }
  publishUrl();
}

export function initializeSpaRouting(): () => void {
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
