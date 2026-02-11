import { describe, expect, it } from "vitest";
import { mergeSearchHits } from "$lib/services/searchHitNormalization";
import type { SearchHit } from "$lib/validation/schemas";

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
    cover: {
      s3ImagePath: null,
      externalImageUrl: null,
      width: null,
      height: null,
      highResolution: null,
      preferredUrl: null,
      fallbackUrl: null,
      source: null,
    },
    editions: [],
    recommendationIds: [],
    extras: {},
    matchType: null,
    relevanceScore: 0,
  };

  const mergedCover = overrides.cover === undefined
    ? base.cover
    : overrides.cover === null
      ? null
      : { ...(base.cover ?? {}), ...overrides.cover };

  return {
    ...base,
    ...overrides,
    cover: mergedCover,
  };
}

describe("mergeSearchHits", () => {
  it("should_ReplaceExistingHit_When_IncomingHitSharesIdentifier", () => {
    const existing = createSearchHit("book-1", {
      title: "Older Title",
      relevanceScore: 1,
      cover: { preferredUrl: "https://cdn.example.com/older.jpg" },
    });
    const incoming = createSearchHit("book-1", {
      title: "Fresh Title",
      relevanceScore: 9,
      cover: { preferredUrl: "https://cdn.example.com/fresh.jpg" },
    });

    const merged = mergeSearchHits([existing], [incoming], "relevance");

    expect(merged).toHaveLength(1);
    expect(merged[0].title).toBe("Fresh Title");
    expect(merged[0].relevanceScore).toBe(9);
    expect(merged[0].cover?.preferredUrl).toBe("https://cdn.example.com/fresh.jpg");
  });

  it("should_AppendDistinctHits_When_IncomingIdentifierIsNew", () => {
    const existing = createSearchHit("book-1", { title: "A Title" });
    const incoming = createSearchHit("book-2", { title: "B Title" });

    const merged = mergeSearchHits([existing], [incoming], "title");

    expect(merged).toHaveLength(2);
    expect(merged.map((hit) => hit.id)).toEqual(["book-1", "book-2"]);
  });
});
