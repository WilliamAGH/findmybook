import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/svelte";
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

const DISPLAYABLE_COVER = {
  s3ImagePath: null,
  externalImageUrl: null,
  width: 300,
  height: 450,
  highResolution: false,
  preferredUrl: "https://cdn.example.com/cover.jpg",
  fallbackUrl: null,
  source: "GOOGLE_BOOKS",
};

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

describe("BookPage similar books", () => {
  it("shouldRenderCoverGridWhenSimilarBooksHaveDisplayableCovers", async () => {
    getSimilarBooksMock.mockResolvedValueOnce([
      createBookPayload({ id: "related-1", slug: "related-1", title: "Related One", cover: DISPLAYABLE_COVER }),
      createBookPayload({ id: "related-2", slug: "related-2", title: "Related Two", cover: DISPLAYABLE_COVER }),
    ]);

    const currentUrl = new URL("https://findmybook.net/book/book-1");
    render(BookPage, {
      props: {
        currentUrl,
        identifier: "book-1",
      },
    });

    const sectionHeading = await screen.findByRole("heading", { name: "You might also like" });
    expect(sectionHeading).toBeInTheDocument();

    const recommendationSection = sectionHeading.closest("section");
    expect(recommendationSection).not.toBeNull();
    const recommendationCovers = recommendationSection?.querySelectorAll("img");
    expect(recommendationCovers).toHaveLength(2);
  });

  it("shouldHideRecommendationSectionWhenAllSimilarBooksLackCovers", async () => {
    getSimilarBooksMock.mockResolvedValueOnce([
      createBookPayload({ id: "no-cover-1", slug: "no-cover-1", title: "No Cover" }),
      createBookPayload({ id: "no-cover-2", slug: "no-cover-2", title: "Also No Cover" }),
    ]);

    const currentUrl = new URL("https://findmybook.net/book/book-1");
    render(BookPage, {
      props: {
        currentUrl,
        identifier: "book-1",
      },
    });

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Book" })).toBeInTheDocument();
    });

    expect(screen.queryByRole("heading", { name: "You might also like" })).not.toBeInTheDocument();
  });

  it("shouldExcludeCurrentBookFromSimilarBooksDisplay", async () => {
    getSimilarBooksMock.mockResolvedValueOnce([
      createBookPayload({ id: "book-1", slug: "book-1", title: "Current Book", cover: DISPLAYABLE_COVER }),
      createBookPayload({ id: "related-1", slug: "related-1", title: "Related One", cover: DISPLAYABLE_COVER }),
      createBookPayload({ id: "related-2", slug: "related-2", title: "Related Two", cover: DISPLAYABLE_COVER }),
    ]);

    const currentUrl = new URL("https://findmybook.net/book/book-1");
    render(BookPage, {
      props: {
        currentUrl,
        identifier: "book-1",
      },
    });

    const sectionHeading = await screen.findByRole("heading", { name: "You might also like" });
    expect(sectionHeading).toBeInTheDocument();

    const recommendationSection = sectionHeading.closest("section");
    expect(recommendationSection).not.toBeNull();
    const recommendationCovers = recommendationSection?.querySelectorAll("img");
    expect(recommendationCovers).toHaveLength(2);

    const linkHrefs = Array.from(recommendationSection?.querySelectorAll("a") ?? []).map(
      (a) => a.getAttribute("href"),
    );
    expect(linkHrefs).not.toContain("/book/book-1");
    expect(linkHrefs).toContain("/book/related-1");
    expect(linkHrefs).toContain("/book/related-2");
  });

  it("shouldHideRecommendationCardWhenPrimaryAndFallbackCoversFailToLoad", async () => {
    getSimilarBooksMock.mockResolvedValueOnce([
      createBookPayload({
        id: "broken-cover-1",
        slug: "broken-cover-1",
        title: "Broken Cover",
        cover: {
          s3ImagePath: null,
          externalImageUrl: null,
          width: 300,
          height: 450,
          highResolution: false,
          preferredUrl: "https://cdn.example.com/broken-primary.jpg",
          fallbackUrl: "/images/book-covers/broken-cover-fallback.jpg",
          source: "GOOGLE_BOOKS",
        },
      }),
    ]);

    const currentUrl = new URL("https://findmybook.net/book/book-1");
    render(BookPage, {
      props: {
        currentUrl,
        identifier: "book-1",
      },
    });

    const sectionHeading = await screen.findByRole("heading", { name: "You might also like" });
    expect(sectionHeading).toBeInTheDocument();

    const recommendationSection = sectionHeading.closest("section");
    expect(recommendationSection).not.toBeNull();
    const recommendationCover = recommendationSection?.querySelector("img");
    expect(recommendationCover).not.toBeNull();
    expect(recommendationCover).toBeInstanceOf(HTMLImageElement);

    const coverImage = recommendationCover as HTMLImageElement;
    await fireEvent.error(coverImage);
    expect(coverImage.src).toContain("/images/book-covers/broken-cover-fallback.jpg");

    await fireEvent.error(coverImage);
    await waitFor(() => {
      expect(screen.queryByRole("heading", { name: "You might also like" })).not.toBeInTheDocument();
    });
  });
});
