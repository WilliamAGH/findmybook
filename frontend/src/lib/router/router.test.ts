import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/svelte";
import { matchRoute, searchBasePathForRoute } from "$lib/router/router";
import BookCard from "$lib/components/BookCard.svelte";
import NotFoundPage from "$lib/pages/NotFoundPage.svelte";

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
});
