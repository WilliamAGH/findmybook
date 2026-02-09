const SPA_HISTORY_MARKER = "findmybook-spa-v1";

interface SpaHistoryState {
  __fmbSpa: typeof SPA_HISTORY_MARKER;
  previousPath: string | null;
}

export function browserOrigin(): string {
  return typeof window === "undefined" ? "http://localhost" : window.location.origin;
}

export function pathWithQueryAndHash(url: URL): string {
  return `${url.pathname}${url.search}${url.hash}`;
}

export function currentPathWithQueryAndHash(): string {
  return typeof window === "undefined" ? "/" : pathWithQueryAndHash(new URL(window.location.href));
}

export function normalizeInternalPath(candidate: string | null): string | null {
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

export function buildSpaHistoryState(previousPath: string | null): SpaHistoryState {
  return {
    __fmbSpa: SPA_HISTORY_MARKER,
    previousPath,
  };
}

export function readSpaHistoryState(): { previousPath: string | null } | null {
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
  return buildSpaHistoryState(previousPath);
}

export function seedSpaHistoryStateIfMissing(): void {
  if (typeof window === "undefined") {
    return;
  }
  if (readSpaHistoryState()) {
    return;
  }
  const currentPath = currentPathWithQueryAndHash();
  window.history.replaceState(buildSpaHistoryState(null), "", currentPath);
}
