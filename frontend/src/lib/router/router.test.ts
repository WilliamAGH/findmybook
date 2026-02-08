import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/svelte";

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

import { matchRoute, searchBasePathForRoute } from "$lib/router/router";
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
});

describe("SearchPage loading state", () => {
  it("shouldClearLoadingWhenQueryIsRemovedDuringInFlightSearch", async () => {
    const pendingSearch = createDeferred<never>();
    searchBooksMock.mockReturnValue(pendingSearch.promise);

    const currentUrlWithQuery = new URL(
      "https://findmybook.net/search?query=alpha&page=1&sort=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );
    const { rerender } = render(SearchPage, {
      props: { currentUrl: currentUrlWithQuery, routeName: "search" },
    });

    await waitFor(() => {
      expect(screen.getByText("Searching books...")).toBeInTheDocument();
    });

    const currentUrlWithoutQuery = new URL(
      "https://findmybook.net/search?page=1&sort=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );
    await rerender({ currentUrl: currentUrlWithoutQuery, routeName: "search" });

    await waitFor(() => {
      expect(screen.queryByText("Searching books...")).not.toBeInTheDocument();
      expect(screen.getByText("Search for books")).toBeInTheDocument();
    });
  });
});
