import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/svelte";
import SearchPage from "$lib/pages/SearchPage.svelte";

const {
  searchBooksMock,
  normalizeRealtimeSearchHitsMock,
  mergeSearchHitsMock,
  getCategoryFacetsMock,
  getHomePagePayloadMock,
  subscribeToSearchTopicsMock,
} = vi.hoisted(() => ({
  searchBooksMock: vi.fn(),
  normalizeRealtimeSearchHitsMock: vi.fn(() => []),
  mergeSearchHitsMock: vi.fn((existingResults: unknown[]) => existingResults),
  getCategoryFacetsMock: vi.fn(),
  getHomePagePayloadMock: vi.fn(),
  subscribeToSearchTopicsMock: vi.fn(async () => () => {}),
}));

vi.mock("$lib/services/books", () => ({
  searchBooks: searchBooksMock,
  normalizeRealtimeSearchHits: normalizeRealtimeSearchHitsMock,
  mergeSearchHits: mergeSearchHitsMock,
}));

vi.mock("$lib/services/pages", () => ({
  getCategoryFacets: getCategoryFacetsMock,
  getHomePagePayload: getHomePagePayloadMock,
}));

vi.mock("$lib/services/realtime", () => ({
  subscribeToSearchTopics: subscribeToSearchTopicsMock,
}));

beforeEach(() => {
  searchBooksMock.mockReset();
  normalizeRealtimeSearchHitsMock.mockReset();
  normalizeRealtimeSearchHitsMock.mockReturnValue([]);
  mergeSearchHitsMock.mockReset();
  mergeSearchHitsMock.mockImplementation((existingResults: unknown[]) => existingResults);
  getCategoryFacetsMock.mockReset();
  getCategoryFacetsMock.mockResolvedValue({ genres: [] });
  getHomePagePayloadMock.mockReset();
  getHomePagePayloadMock.mockResolvedValue({
    currentBestsellers: [],
    recentBooks: [],
    popularBooks: [],
    popularWindow: "90d",
  });
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

  it("shouldLoadPopularExploreViewFromHomePayloadWhenNoQueryProvided", async () => {
    getHomePagePayloadMock.mockResolvedValue({
      currentBestsellers: [],
      recentBooks: [],
      popularBooks: [
        {
          id: "popular-book-1",
          slug: "popular-book-1",
          title: "Popular Book One",
          authors: ["Author One"],
          cover_url: "https://cdn.example.com/popular-book-1.jpg",
          cover_s3_key: null,
          fallback_cover_url: "https://cdn.example.com/popular-book-1-fallback.jpg",
          average_rating: 4.8,
          ratings_count: 1200,
          tags: {},
        },
      ],
      popularWindow: "90d",
    });

    const currentUrl = new URL(
      "https://findmybook.net/explore?popularWindow=90d&page=1&orderBy=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );

    render(SearchPage, {
      props: { currentUrl, routeName: "explore" },
    });

    const resultLink = await screen.findByRole("link", { name: "Popular Book One" });
    expect(resultLink.getAttribute("href")).toContain("/book/popular-book-1?");
    expect(resultLink.getAttribute("href")).toContain("popularWindow=90d");
    expect(searchBooksMock).not.toHaveBeenCalled();
    expect(getHomePagePayloadMock).toHaveBeenCalledWith({
      popularWindow: "90d",
      popularLimit: 24,
      recordView: false,
    });
  });

  it("shouldRedirectExplorePopularToLastPageWhenRequestedPageOvershoots", async () => {
    getHomePagePayloadMock.mockResolvedValue({
      currentBestsellers: [],
      recentBooks: [],
      popularBooks: Array.from({ length: 24 }, (_, index) => ({
        id: `popular-book-${index + 1}`,
        slug: `popular-book-${index + 1}`,
        title: `Popular Book ${index + 1}`,
        authors: [`Author ${index + 1}`],
        cover_url: null,
        cover_s3_key: null,
        fallback_cover_url: "/images/placeholder-book-cover.svg",
        average_rating: 4.2,
        ratings_count: 100 + index,
        tags: {},
      })),
      popularWindow: "90d",
    });

    const currentUrl = new URL(
      "https://findmybook.net/explore?popularWindow=90d&page=3&orderBy=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );
    window.history.replaceState({}, "", `${currentUrl.pathname}${currentUrl.search}`);

    render(SearchPage, {
      props: { currentUrl, routeName: "explore" },
    });

    await waitFor(() => {
      expect(window.location.search).toContain("page=2");
    });
  });

  it("shouldShowOnlyNoResultsMessageWhenSearchResponseIsEmpty", async () => {
    searchBooksMock.mockResolvedValue({
      query: "no-hit-query",
      queryHash: "no-hit-query",
      startIndex: 0,
      maxResults: 12,
      totalResults: 0,
      hasMore: false,
      nextStartIndex: 0,
      prefetchedCount: 0,
      orderBy: "newest",
      coverSource: "ANY",
      resolution: "HIGH_FIRST",
      results: [],
    });

    const currentUrl = new URL(
      "https://findmybook.net/search?query=no-hit-query&page=1&orderBy=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );

    render(SearchPage, {
      props: { currentUrl, routeName: "search" },
    });

    await waitFor(() => {
      expect(screen.getByText("No results found.")).toBeInTheDocument();
    });
    expect(screen.queryByText(/Showing 0 of 0 results/i)).not.toBeInTheDocument();
  });

  it("shouldIncludeFallbackBookIdOnSearchResultLinks", async () => {
    searchBooksMock.mockResolvedValue({
      query: "doug turnbull",
      queryHash: "doug_turnbull",
      startIndex: 0,
      maxResults: 12,
      totalResults: 1,
      hasMore: false,
      nextStartIndex: 12,
      prefetchedCount: 0,
      orderBy: "newest",
      coverSource: "ANY",
      resolution: "HIGH_FIRST",
      results: [
        {
          id: "OL13535055W",
          slug: "northern-algoma-dan-douglas",
          title: "Northern Algoma",
          source: null,
          description: null,
          descriptionContent: { raw: null, format: "UNKNOWN", html: "", text: "" },
          publication: { publishedDate: null, language: null, pageCount: null, publisher: null },
          authors: [{ id: null, name: "Dan Douglas" }],
          categories: [],
          collections: [],
          tags: [],
          cover: null,
          editions: [],
          recommendationIds: [],
          extras: {},
          matchType: "OPEN_LIBRARY_API",
          relevanceScore: null,
        },
      ],
    } as any);

    const currentUrl = new URL(
      "https://findmybook.net/search?query=doug%20turnbull&page=1&orderBy=newest&view=grid&coverSource=ANY&resolution=HIGH_FIRST",
    );

    render(SearchPage, {
      props: { currentUrl, routeName: "search" },
    });

    const resultLink = await screen.findByRole("link", { name: "Northern Algoma" });
    const href = resultLink.getAttribute("href");

    expect(href).toContain("/book/northern-algoma-dan-douglas?");
    expect(href).toContain("bookId=OL13535055W");
  });
});
