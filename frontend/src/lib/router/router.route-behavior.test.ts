import { beforeEach, describe, expect, it, vi } from "vitest";

const { getRouteManifestMock } = vi.hoisted(() => ({
  getRouteManifestMock: vi.fn(),
}));

vi.mock("$lib/services/pages", () => ({
  getRouteManifest: getRouteManifestMock,
}));

import {
  initializeSpaRouting,
  matchRoute,
  navigate,
  previousSpaPath,
  searchBasePathForRoute,
} from "$lib/router/router";
import { pageFromStartIndex, startIndexFromPage } from "$lib/services/searchConfig";

beforeEach(() => {
  getRouteManifestMock.mockReset();
  getRouteManifestMock.mockResolvedValue((window as Window & { __FMB_ROUTE_MANIFEST__?: unknown }).__FMB_ROUTE_MANIFEST__);
  window.history.replaceState(null, "", "/");
});

describe("matchRoute", () => {
  it("shouldMatchBookRouteWhenBookSlugProvided", () => {
    const matched = matchRoute("/book/the-hobbit");

    expect(matched.name).toBe("book");
    expect(matched.params.identifier).toBe("the-hobbit");
  });

  it("shouldMatchSitemapRouteWhenDynamicSegmentsProvided", () => {
    const matched = matchRoute("/sitemap/books/A/3");

    expect(matched.name).toBe("sitemap");
    expect(matched.params.view).toBe("books");
    expect(matched.params.letter).toBe("A");
    expect(matched.params.page).toBe(3);
  });

  it("shouldMatchExploreRouteWhenExplorePathProvided", () => {
    const matched = matchRoute("/explore");

    expect(matched.name).toBe("explore");
  });

  it("shouldMatchCategoriesRouteWhenCategoriesPathProvided", () => {
    const matched = matchRoute("/categories");

    expect(matched.name).toBe("categories");
  });

  it("shouldMatchErrorRouteWhenErrorPathProvided", () => {
    const matched = matchRoute("/error");

    expect(matched.name).toBe("error");
  });

  it("shouldReturnNotFoundWhenPathUnrecognized", () => {
    const matched = matchRoute("/unknown/path");

    expect(matched.name).toBe("notFound");
  });
});

describe("searchBasePathForRoute", () => {
  it("shouldReturnSearchPathWhenRouteIsSearch", () => {
    expect(searchBasePathForRoute("search")).toBe("/search");
  });

  it("shouldReturnExplorePathWhenRouteIsExplore", () => {
    expect(searchBasePathForRoute("explore")).toBe("/explore");
  });

  it("shouldReturnCategoriesPathWhenRouteIsCategories", () => {
    expect(searchBasePathForRoute("categories")).toBe("/categories");
  });
});

describe("search pagination conversion helpers", () => {
  it("shouldConvertOneBasedPageToZeroBasedStartIndex", () => {
    expect(startIndexFromPage(1, 12)).toBe(0);
    expect(startIndexFromPage(2, 12)).toBe(12);
    expect(startIndexFromPage(5, 12)).toBe(48);
  });

  it("shouldConvertZeroBasedStartIndexToOneBasedPage", () => {
    expect(pageFromStartIndex(0, 12)).toBe(1);
    expect(pageFromStartIndex(12, 12)).toBe(2);
    expect(pageFromStartIndex(47, 12)).toBe(4);
  });
});

describe("route manifest bootstrap", () => {
  it("shouldThrowWhenManifestMissingAndApiRequestFails", async () => {
    const routeManifestWindow = window as Window & { __FMB_ROUTE_MANIFEST__?: unknown };
    const originalManifest = routeManifestWindow.__FMB_ROUTE_MANIFEST__;
    routeManifestWindow.__FMB_ROUTE_MANIFEST__ = undefined;
    getRouteManifestMock.mockRejectedValueOnce(new Error("manifest unavailable"));

    try {
      vi.resetModules();
      const isolatedRouter = await import("$lib/router/router");
      await expect(isolatedRouter.initializeRouteManifest()).rejects.toThrow("Route manifest unavailable");
    } finally {
      routeManifestWindow.__FMB_ROUTE_MANIFEST__ = originalManifest;
      vi.resetModules();
    }
  });
});

describe("spa navigation history state", () => {
  it("shouldTrackPreviousPathWhenNavigatingForward", () => {
    window.history.replaceState(null, "", "/search?query=alpha&page=2&orderBy=title&view=list");

    navigate("/book/the-hobbit?query=alpha&page=2&orderBy=title&view=list");

    expect(window.location.pathname).toBe("/book/the-hobbit");
    expect(previousSpaPath()).toBe("/search?query=alpha&page=2&orderBy=title&view=list");
  });

  it("shouldPreservePreviousPathWhenReplacingCurrentRoute", () => {
    navigate("/explore");
    expect(previousSpaPath()).toBe("/");

    navigate("/explore?query=fantasy&page=1&orderBy=newest&view=grid", true);

    expect(window.location.pathname).toBe("/explore");
    expect(previousSpaPath()).toBe("/");
  });

  it("shouldSeedHistoryStateWhenSpaRoutingStarts", () => {
    window.history.replaceState(null, "", "/categories?genre=Fantasy");

    const cleanup = initializeSpaRouting();
    try {
      expect(previousSpaPath()).toBeNull();
      expect((window.history.state as { __fmbSpa?: string } | null)?.__fmbSpa).toBe("findmybook-spa-v1");
    } finally {
      cleanup();
    }
  });
});
