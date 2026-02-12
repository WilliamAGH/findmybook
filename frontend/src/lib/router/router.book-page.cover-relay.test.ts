import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import BookPage from "$lib/pages/BookPage.svelte";

const {
  getBookMock,
  getSimilarBooksMock,
  getAffiliateLinksMock,
  persistRenderedCoverMock,
  subscribeToBookCoverUpdatesMock,
} = vi.hoisted(() => ({
  getBookMock: vi.fn(),
  getSimilarBooksMock: vi.fn(),
  getAffiliateLinksMock: vi.fn(),
  persistRenderedCoverMock: vi.fn(),
  subscribeToBookCoverUpdatesMock: vi.fn(async () => () => {}),
}));

vi.mock("$lib/services/books", () => ({
  getBook: getBookMock,
  getSimilarBooks: getSimilarBooksMock,
  getAffiliateLinks: getAffiliateLinksMock,
  persistRenderedCover: persistRenderedCoverMock,
  DEFAULT_SIMILAR_BOOKS_LIMIT: 8,
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

  persistRenderedCoverMock.mockReset();
  persistRenderedCoverMock.mockResolvedValue({
    bookId: "book-1",
    storedCoverUrl: "https://cdn.example.com/images/book-covers/book-1-lg-google-books.jpg",
    storageKey: "images/book-covers/book-1-lg-google-books.jpg",
    source: "GOOGLE_BOOKS",
    width: 600,
    height: 900,
    highResolution: true,
  });

  subscribeToBookCoverUpdatesMock.mockReset();
  subscribeToBookCoverUpdatesMock.mockResolvedValue(() => {});
});

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

describe("BookPage cover relay persistence", () => {
  it("shouldPersistRenderedExternalCoverOnceWhenImageLoadsWithoutStoredS3Cover", async () => {
    getBookMock.mockResolvedValueOnce(
      createBookPayload({
        id: "book-1",
        title: "Browser Cover Book",
        cover: {
          preferredUrl: "https://books.google.com/books/content?id=abc&printsec=frontcover&img=1&zoom=1",
          fallbackUrl: "https://books.google.com/books/content?id=abc&printsec=frontcover&img=1&zoom=1",
          source: "GOOGLE_BOOKS",
          s3ImagePath: null,
          externalImageUrl: null,
          width: null,
          height: null,
          highResolution: null,
        },
      }),
    );

    const currentUrl = new URL("https://findmybook.net/book/book-1");
    render(BookPage, {
      props: {
        currentUrl,
        identifier: "book-1",
      },
    });

    const cover = await screen.findByAltText("Browser Cover Book cover");
    await fireEvent.load(cover);
    await fireEvent.load(cover);

    await waitFor(() => {
      expect(persistRenderedCoverMock).toHaveBeenCalledTimes(1);
    });
    expect(persistRenderedCoverMock).toHaveBeenCalledWith({
      identifier: "book-1",
      renderedCoverUrl: "https://books.google.com/books/content?id=abc&printsec=frontcover&img=1&zoom=1",
      source: "GOOGLE_BOOKS",
    });
  });

  it("shouldSkipRenderedCoverPersistenceWhenBookAlreadyHasS3Cover", async () => {
    getBookMock.mockResolvedValueOnce(
      createBookPayload({
        id: "book-1",
        title: "Stored Cover Book",
        cover: {
          preferredUrl: "https://cdn.example.com/images/book-covers/book-1-lg-google-books.jpg",
          fallbackUrl: "https://cdn.example.com/images/book-covers/book-1-lg-google-books.jpg",
          source: "S3_CACHE",
          s3ImagePath: "images/book-covers/book-1-lg-google-books.jpg",
          externalImageUrl: null,
          width: 600,
          height: 900,
          highResolution: true,
        },
      }),
    );

    const currentUrl = new URL("https://findmybook.net/book/book-1");
    render(BookPage, {
      props: {
        currentUrl,
        identifier: "book-1",
      },
    });

    const cover = await screen.findByAltText("Stored Cover Book cover");
    await fireEvent.load(cover);

    await waitFor(() => {
      expect(persistRenderedCoverMock).not.toHaveBeenCalled();
    });
  });
});
