import { describe, expect, it } from "vitest";
import { mergeSearchHits } from "$lib/services/searchHitNormalization";
import { type SearchHit, buildCover } from "$lib/validation/schemas";

function createSearchHit(id: string, overrides: Partial<SearchHit> = {}): SearchHit {
  const base: SearchHit = {
    id,
    slug: id,
    title: `Title ${id}`,
    source: "POSTGRES",
    description: null,
    publication: {
      publishedDate: null,
      language: null,
      pageCount: null,
      publisher: null,
    },
    authors: [{ id: null, name: `Author ${id}` }],
    categories: [],
    collections: [],
    tags: [],
    cover: buildCover({}),
    editions: [],
    recommendationIds: [],
    extras: {},
    matchType: null,
    relevanceScore: 0,
  };

  let mergedCover: SearchHit["cover"];
  if (overrides.cover === undefined) {
    mergedCover = base.cover;
  } else if (overrides.cover === null) {
    mergedCover = null;
  } else {
    const baseCoverProps = base.cover ?? {};
    mergedCover = buildCover({ ...baseCoverProps, ...overrides.cover });
  }

  return {
    ...base,
    ...overrides,
    cover: mergedCover,
  };
}

describe("mergeSearchHits", () => {
  it("should_PreserveExistingHit_When_IncomingHitSharesIdentifier", () => {
    const existing = createSearchHit("book-1", {
      title: "Backend Title",
      relevanceScore: 5,
      cover: buildCover({ preferredUrl: "https://cdn.example.com/backend.jpg" }),
    });
    const incoming = createSearchHit("book-1", {
      title: "WebSocket Title",
      relevanceScore: 9,
      cover: buildCover({ preferredUrl: "https://cdn.example.com/ws.jpg" }),
    });

    const merged = mergeSearchHits([existing], [incoming], "relevance");

    expect(merged).toHaveLength(1);
    expect(merged[0].title).toBe("Backend Title");
    expect(merged[0].relevanceScore).toBe(5);
    expect(merged[0].cover?.preferredUrl).toBe("https://cdn.example.com/backend.jpg");
  });

  it("should_AppendDistinctHits_When_IncomingIdentifierIsNew", () => {
    const existing = createSearchHit("book-1", { title: "A Title" });
    const incoming = createSearchHit("book-2", { title: "B Title" });

    const merged = mergeSearchHits([existing], [incoming], "title");

    expect(merged).toHaveLength(2);
    expect(merged.map((hit) => hit.id)).toEqual(["book-1", "book-2"]);
  });

  it("should_ClassifyProviderUsingDisplayUrl_When_PreferredUrlBlank", () => {
    const openLibrary = createSearchHit("book-1", {
      title: "Same Title",
      source: null,
      cover: buildCover({ preferredUrl: "https://covers.openlibrary.org/b/id/1-L.jpg" }),
    });
    const google = createSearchHit("book-2", {
      title: "Same Title",
      source: null,
      cover: buildCover({ preferredUrl: "", externalImageUrl: "https://books.google.com/books/content?id=1" }),
    });

    const merged = mergeSearchHits([], [google, openLibrary], "title");

    // Open Library hits should rank ahead of Google hits when all other keys tie.
    expect(merged.map((hit) => hit.id)).toEqual(["book-1", "book-2"]);
  });
});
