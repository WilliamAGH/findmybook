import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/svelte";

const {
  searchBooksMock,
  normalizeRealtimeSearchHitsMock,
  mergeSearchHitsMock,
  getCategoryFacetsMock,
  subscribeToSearchTopicsMock,
} = vi.hoisted(() => ({
  searchBooksMock: vi.fn(),
  normalizeRealtimeSearchHitsMock: vi.fn(() => []),
  mergeSearchHitsMock: vi.fn((existingResults: unknown[]) => existingResults),
  getCategoryFacetsMock: vi.fn(),
  subscribeToSearchTopicsMock: vi.fn(async () => () => {}),
}));

vi.mock("$lib/services/books", () => ({
  searchBooks: searchBooksMock,
  normalizeRealtimeSearchHits: normalizeRealtimeSearchHitsMock,
  mergeSearchHits: mergeSearchHitsMock,
}));

vi.mock("$lib/services/pages", () => ({
  getCategoryFacets: getCategoryFacetsMock,
}));

vi.mock("$lib/services/realtime", () => ({
  subscribeToSearchTopics: subscribeToSearchTopicsMock,
}));

import {
  DEFAULT_PASSTHROUGH_PREFIXES,
  initializeSpaRouting,
  matchRoute,
  navigate,
  previousSpaPath,
  searchBasePathForRoute,
} from "$lib/router/router";
import { pageFromStartIndex, startIndexFromPage } from "$lib/services/searchConfig";
import BookCard from "$lib/components/BookCard.svelte";
import NotFoundPage from "$lib/pages/NotFoundPage.svelte";
import TopNav from "$lib/components/TopNav.svelte";
import SearchPage from "$lib/pages/SearchPage.svelte";

beforeEach(() => {
  searchBooksMock.mockReset();
  normalizeRealtimeSearchHitsMock.mockReset();
  normalizeRealtimeSearchHitsMock.mockReturnValue([]);
  mergeSearchHitsMock.mockReset();
  mergeSearchHitsMock.mockImplementation((existingResults: unknown[]) => existingResults);
  getCategoryFacetsMock.mockReset();
  getCategoryFacetsMock.mockResolvedValue({ genres: [] });
  subscribeToSearchTopicsMock.mockReset();
  subscribeToSearchTopicsMock.mockResolvedValue(() => {});
  window.history.replaceState(null, "", "/");
});

function createDeferred<T>() {
  let resolvePromise!: (value: T | PromiseLike<T>) => void;
  let rejectPromise!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolve, reject) => {
    resolvePromise = resolve;
    rejectPromise = reject;
  });
  return { promise, resolvePromise, rejectPromise };
}

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

describe("route manifest fallback", () => {
  it("shouldExposeBackendPassthroughPrefixesForEmptyManifestFallback", () => {
    expect(DEFAULT_PASSTHROUGH_PREFIXES).toEqual([
      "/api",
      "/admin",
      "/actuator",
      "/ws",
      "/topic",
      "/sitemap.xml",
      "/sitemap-xml",
      "/r",
    ]);
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

describe("component rendering", () => {
  it("shouldRenderBookCardWithTitleAndHrefWhenGridLayoutUsed", () => {
    render(BookCard, {
      props: {
        layout: "grid",
        href: "/book/the-hobbit",
        book: {
          id: "book-1",
          slug: "the-hobbit",
          title: "The Hobbit",
          authors: ["J.R.R. Tolkien"],
          coverUrl: null,
          fallbackCoverUrl: null,
        },
      },
    });

    expect(screen.getByRole("link", { name: "The Hobbit" })).toHaveAttribute("href", "/book/the-hobbit");
    expect(screen.getByText("J.R.R. Tolkien")).toBeInTheDocument();
  });

  it("shouldRenderUnknownAuthorWhenBookCardAuthorsMissing", () => {
    render(BookCard, {
      props: {
        layout: "list",
        href: "/book/unknown",
        book: {
          id: "book-2",
          slug: "unknown",
          title: "Unknown Book",
          authors: [],
          coverUrl: null,
          fallbackCoverUrl: null,
        },
      },
    });

    expect(screen.getByText("Unknown Author")).toBeInTheDocument();
  });

  it("shouldFallbackToSecondaryCoverWhenPreferredCoverFails", async () => {
    render(BookCard, {
      props: {
        layout: "grid",
        href: "/book/fallback-case",
        book: {
          id: "book-fallback-1",
          slug: "fallback-case",
          title: "Fallback Case",
          authors: ["Author"],
          coverUrl: "https://cdn.example.com/preferred-cover.jpg",
          fallbackCoverUrl: "https://cdn.example.com/fallback-cover.jpg",
        },
      },
    });

    const cover = screen.getByAltText("Fallback Case cover") as HTMLImageElement;
    expect(cover.getAttribute("src")).toContain("preferred-cover.jpg");

    await fireEvent.error(cover);
    await waitFor(() => {
      expect(cover.getAttribute("src")).toContain("fallback-cover.jpg");
    });
  });

  it("shouldFallbackToPlaceholderWhenPreferredAndSecondaryCoversFail", async () => {
    render(BookCard, {
      props: {
        layout: "grid",
        href: "/book/fallback-placeholder-case",
        book: {
          id: "book-fallback-2",
          slug: "fallback-placeholder-case",
          title: "Fallback Placeholder Case",
          authors: ["Author"],
          coverUrl: "https://cdn.example.com/preferred-cover.jpg",
          fallbackCoverUrl: "https://cdn.example.com/fallback-cover.jpg",
        },
      },
    });

    const cover = screen.getByAltText("Fallback Placeholder Case cover") as HTMLImageElement;
    expect(cover.getAttribute("src")).toContain("preferred-cover.jpg");

    await fireEvent.error(cover);
    await waitFor(() => {
      expect(cover.getAttribute("src")).toContain("fallback-cover.jpg");
    });

    await fireEvent.error(cover);
    await waitFor(() => {
      expect(cover.getAttribute("src")).toContain("/images/placeholder-book-cover.svg");
    });
  });

  it("shouldRenderNotFoundPageCallToAction", () => {
    render(NotFoundPage);

    expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go home" })).toHaveAttribute("href", "/");
  });

  it("shouldHideCategoriesNavigationItemFromTopNav", () => {
    render(TopNav, {
      props: {
        activeRoute: "home",
      },
    });

    expect(screen.queryByRole("link", { name: /Categories/i })).not.toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /Explore/i }).length).toBeGreaterThan(0);
  });

  it("shouldExposeThemeToggleHoverHelperTextInTopNav", () => {
    render(TopNav, {
      props: {
        activeRoute: "home",
      },
    });

    const themeButtons = screen.getAllByRole("button", { name: "Switch to dark mode" });
    expect(themeButtons.length).toBeGreaterThan(0);
    themeButtons.forEach((button) => {
      expect(button).toHaveAttribute("title", "Switch to dark mode");
      expect(button).toHaveAttribute("aria-label", "Switch to dark mode");
    });
  });
});

describe("SearchPage loading state", () => {
  it("shouldClearLoadingWhenQueryIsRemovedDuringInFlightSearch", async () => {
    const pendingSearch = createDeferred<never>();
    searchBooksMock.mockReturnValue(pendingSearch.promise);

    const currentUrlWithQuery = new URL(
      "https://findmybook.net/search?query=alpha&page=1&orderBy=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );
    const { rerender } = render(SearchPage, {
      props: { currentUrl: currentUrlWithQuery, routeName: "search" },
    });

    await waitFor(() => {
      expect(screen.getByText("Searching books...")).toBeInTheDocument();
    });

    const currentUrlWithoutQuery = new URL(
      "https://findmybook.net/search?page=1&orderBy=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );
    await rerender({ currentUrl: currentUrlWithoutQuery, routeName: "search" });

    await waitFor(() => {
      expect(screen.queryByText("Searching books...")).not.toBeInTheDocument();
      expect(screen.getByText("Search for books")).toBeInTheDocument();
    });
  });
});
