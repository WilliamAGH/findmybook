import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/svelte";
import BookPage from "$lib/pages/BookPage.svelte";

const {
  getBookMock,
  getSimilarBooksMock,
  getAffiliateLinksMock,
  subscribeToBookCoverUpdatesMock,
} = vi.hoisted(() => ({
  getBookMock: vi.fn(),
  getSimilarBooksMock: vi.fn(),
  getAffiliateLinksMock: vi.fn(),
  subscribeToBookCoverUpdatesMock: vi.fn(async () => () => {}),
}));

vi.mock("$lib/services/books", () => ({
  getBook: getBookMock,
  getSimilarBooks: getSimilarBooksMock,
  getAffiliateLinks: getAffiliateLinksMock,
  persistRenderedCover: vi.fn(),
}));

vi.mock("$lib/services/realtime", () => ({
  subscribeToBookCoverUpdates: subscribeToBookCoverUpdatesMock,
}));

beforeEach(() => {
  getBookMock.mockReset();
  getBookMock.mockResolvedValue({
    id: "book-1",
    slug: "book-1",
    title: "Book",
    description: null,
    descriptionContent: { raw: null, format: "UNKNOWN", html: "", text: "" },
    publication: { publishedDate: null, language: null, pageCount: null, publisher: null },
    authors: [],
    categories: [],
    collections: [],
    tags: [],
    cover: null,
    editions: [],
    recommendationIds: [],
    extras: {},
    aiContent: {
      summary: "Cached summary",
      readerFit: null,
      keyThemes: [],
      takeaways: null,
      context: null,
      version: null,
      generatedAt: null,
      model: null,
      provider: null,
    },
  });

  getSimilarBooksMock.mockReset();
  getSimilarBooksMock.mockResolvedValue([]);

  getAffiliateLinksMock.mockReset();
  getAffiliateLinksMock.mockResolvedValue({});

  subscribeToBookCoverUpdatesMock.mockReset();
  subscribeToBookCoverUpdatesMock.mockResolvedValue(() => {});
});

function setNumericGetter(
  prototype: object,
  property: "scrollHeight" | "clientHeight",
  value: number,
): () => void {
  const originalDescriptor = Object.getOwnPropertyDescriptor(prototype, property);
  Object.defineProperty(prototype, property, {
    configurable: true,
    get: () => value,
  });
  return () => {
    if (originalDescriptor) {
      Object.defineProperty(prototype, property, originalDescriptor);
      return;
    }
    Reflect.deleteProperty(prototype, property);
  };
}

function createBookPayload(overrides: Record<string, unknown>) {
  return {
    id: "book-1",
    slug: "book-1",
    title: "Book",
    description: null,
    descriptionContent: { raw: null, format: "UNKNOWN", html: "", text: "" },
    publication: { publishedDate: null, language: null, pageCount: null, publisher: null },
    authors: [{ id: null, name: "Author Name" }],
    categories: [],
    collections: [],
    tags: [],
    cover: null,
    editions: [],
    recommendationIds: [],
    extras: {},
    aiContent: {
      summary: "Cached summary",
      readerFit: null,
      keyThemes: [],
      takeaways: null,
      context: null,
      version: null,
      generatedAt: null,
      model: null,
      provider: null,
    },
    ...overrides,
  };
}

describe("BookPage fallback lookup", () => {
  it("shouldRetryWithBookIdQueryParamWhenSlugLookupReturns404", async () => {
    getBookMock.mockReset();
    getBookMock
      .mockRejectedValueOnce(new Error("HTTP 404: Not Found"))
      .mockResolvedValueOnce({
        id: "OL13535055W",
        slug: "northern-algoma-dan-douglas",
        title: "Northern Algoma",
        description: "A regional history.",
        descriptionContent: { raw: "A regional history.", format: "PLAIN_TEXT", html: "<p>A regional history.</p>", text: "A regional history." },
        publication: { publishedDate: null, language: null, pageCount: null, publisher: null },
        authors: [{ id: null, name: "Dan Douglas" }],
        categories: [],
        collections: [],
        tags: [],
        cover: null,
        editions: [],
        recommendationIds: [],
        extras: {},
        aiContent: {
          summary: "Cached summary",
          readerFit: null,
          keyThemes: [],
          takeaways: null,
          context: null,
          version: null,
          generatedAt: null,
          model: null,
          provider: null,
        },
      });

    const currentUrl = new URL(
      "https://findmybook.net/book/northern-algoma-dan-douglas?query=doug%20turnbull&page=1&orderBy=newest&view=grid&bookId=OL13535055W",
    );
    render(BookPage, {
      props: {
        currentUrl,
        identifier: "northern-algoma-dan-douglas",
      },
    });

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Northern Algoma" })).toBeInTheDocument();
    });

    expect(getBookMock).toHaveBeenNthCalledWith(1, "northern-algoma-dan-douglas", "30d");
    expect(getBookMock).toHaveBeenNthCalledWith(2, "OL13535055W", "30d");
    expect(getSimilarBooksMock).toHaveBeenCalledWith("OL13535055W", 8);
    expect(getAffiliateLinksMock).toHaveBeenCalledWith("OL13535055W");
  });

  it("shouldBuildExploreBackLinkFromPopularWindowWhenSpaHistoryIsMissing", async () => {
    const currentUrl = new URL(
      "https://findmybook.net/book/book-1?bookId=book-1&popularWindow=90d&page=2&orderBy=newest&view=grid",
    );
    window.history.replaceState(null, "", `${currentUrl.pathname}${currentUrl.search}`);

    render(BookPage, {
      props: {
        currentUrl,
        identifier: "book-1",
      },
    });

    const backLink = await screen.findByRole("link", { name: "Back to Results" });
    expect(backLink.getAttribute("href")).toBe("/explore?popularWindow=90d&page=2&orderBy=newest&view=grid");
  });

  it("shouldHideTitleAndAuthorExpandButtonsWhenContentIsNotActuallyTruncated", async () => {
    getBookMock.mockResolvedValueOnce(
      createBookPayload({
        title: "Short title",
        authors: [{ id: null, name: "Ada Lovelace" }],
      }),
    );

    const restoreGetters = [
      setNumericGetter(HTMLHeadingElement.prototype, "scrollHeight", 145),
      setNumericGetter(HTMLHeadingElement.prototype, "clientHeight", 144),
      setNumericGetter(HTMLParagraphElement.prototype, "scrollHeight", 73),
      setNumericGetter(HTMLParagraphElement.prototype, "clientHeight", 72),
    ];

    try {
      const currentUrl = new URL("https://findmybook.net/book/book-1");
      render(BookPage, {
        props: {
          currentUrl,
          identifier: "book-1",
        },
      });

      await waitFor(() => {
        expect(screen.getByRole("heading", { name: "Short title" })).toBeInTheDocument();
      });

      await waitFor(() => {
        expect(screen.queryByRole("button", { name: "Show full title" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Show full author" })).not.toBeInTheDocument();
      });
    } finally {
      for (const restoreGetter of restoreGetters) {
        restoreGetter();
      }
    }
  });

  it("shouldShowTitleAndAuthorExpandButtonsWhenContentIsTruncated", async () => {
    getBookMock.mockResolvedValueOnce(
      createBookPayload({
        title: "Extremely long title that should be considered clamped by the detail layout and therefore expose the full title control",
        authors: [{
          id: null,
          name: "Author Name With A Very Long Formatting String For Overflow Behavior",
        }],
      }),
    );

    const restoreGetters = [
      setNumericGetter(HTMLHeadingElement.prototype, "scrollHeight", 260),
      setNumericGetter(HTMLHeadingElement.prototype, "clientHeight", 144),
      setNumericGetter(HTMLParagraphElement.prototype, "scrollHeight", 120),
      setNumericGetter(HTMLParagraphElement.prototype, "clientHeight", 72),
    ];

    try {
      const currentUrl = new URL("https://findmybook.net/book/book-1");
      render(BookPage, {
        props: {
          currentUrl,
          identifier: "book-1",
        },
      });

      await waitFor(() => {
        expect(screen.getByRole("button", { name: "Show full title" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Show full author" })).toBeInTheDocument();
      });
    } finally {
      for (const restoreGetter of restoreGetters) {
        restoreGetter();
      }
    }
  });
});
