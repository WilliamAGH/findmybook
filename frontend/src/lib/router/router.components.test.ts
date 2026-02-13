import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import BookCard from "$lib/components/BookCard.svelte";
import NotFoundPage from "$lib/pages/NotFoundPage.svelte";
import TopNav from "$lib/components/TopNav.svelte";
import HomePage from "$lib/pages/HomePage.svelte";

const {
  getHomePagePayloadMock,
  subscribeToBookCoverUpdatesMock,
} = vi.hoisted(() => ({
  getHomePagePayloadMock: vi.fn(),
  subscribeToBookCoverUpdatesMock: vi.fn(async () => () => {}),
}));

vi.mock("$lib/services/pages", () => ({
  getHomePagePayload: getHomePagePayloadMock,
}));

vi.mock("$lib/services/realtime", () => ({
  subscribeToBookCoverUpdates: subscribeToBookCoverUpdatesMock,
}));

beforeEach(() => {
  getHomePagePayloadMock.mockReset();
  vi.spyOn(console, "warn").mockImplementation(() => {});
  vi.spyOn(console, "error").mockImplementation(() => {});
  vi.spyOn(console, "info").mockImplementation(() => {});
  getHomePagePayloadMock.mockResolvedValue({
    currentBestsellers: [],
    recentBooks: [],
    popularBooks: [],
    popularWindow: "90d",
  });
  subscribeToBookCoverUpdatesMock.mockReset();
  subscribeToBookCoverUpdatesMock.mockResolvedValue(() => {});
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

    const cover = screen.getByAltText("Fallback Case cover");
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

    const cover = screen.getByAltText("Fallback Placeholder Case cover");
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

  it("shouldRenderErrorVariantWhenErrorPageRequested", () => {
    render(NotFoundPage, {
      props: {
        variant: "error",
      },
    });

    expect(screen.getByRole("heading", { name: "Something went wrong" })).toBeInTheDocument();
    expect(screen.getByText("500")).toBeInTheDocument();
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

  it("shouldPointHomeExploreMoreLinkToNinetyDayPopularExploreView", async () => {
    render(HomePage);

    const exploreMore = await screen.findByRole("link", { name: "Explore More" });
    expect(exploreMore).toHaveAttribute("href", "/explore?popularWindow=90d");
  });
});
