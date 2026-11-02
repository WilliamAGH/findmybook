import { describe, expect, it } from "vitest";
import { mergeSearchHits, normalizeRealtimeSearchHits } from "$lib/services/searchHitNormalization";
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
    expect(merged[0]).toBe(existing);
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

  it("should_PreserveBackendRankingAndReceivedOrder_When_RelevanceHitsHaveHigherScoresAndNewerDates", () => {
    const exactTitle = createSearchHit("snow-crash", {
      title: "Snow Crash",
      relevanceScore: 1,
      publication: { publishedDate: "1992-06-01" },
    });
    const backendNoise = createSearchHit("backend-noise", {
      relevanceScore: 100,
      publication: { publishedDate: "2025-01-01" },
      cover: buildCover({ preferredUrl: "https://cdn.example.com/noise.jpg" }),
    });
    const firstIncoming = createSearchHit("first-incoming", { relevanceScore: 2 });
    const newerNoise = createSearchHit("newer-noise", {
      relevanceScore: 1000,
      publication: { publishedDate: "2026-01-01" },
    });
    const duplicateExactTitle = createSearchHit(exactTitle.id, { title: "Incomplete realtime title" });

    const merged = mergeSearchHits(
      [exactTitle, backendNoise],
      [firstIncoming, newerNoise, duplicateExactTitle, firstIncoming],
      "relevance",
    );

    expect(merged.map((hit) => hit.id)).toEqual([
      "snow-crash", "backend-noise", "first-incoming", "newer-noise",
    ]);
    expect(merged[0]).toBe(exactTitle);
  });

  it.each(["newest", "title", "author"] as const)(
    "should_ApplyExplicitSort_When_OrderByIs_%s",
    (orderBy) => {
      const existing = createSearchHit("book-1", {
        title: "Z Title",
        authors: [{ id: null, name: "Z Author" }],
        publication: { publishedDate: "1992-06-01" },
        relevanceScore: 100,
      });
      const incoming = createSearchHit("book-2", {
        title: "A Title",
        authors: [{ id: null, name: "A Author" }],
        publication: { publishedDate: "2026-01-01" },
        relevanceScore: 1,
      });

      const merged = mergeSearchHits([existing], [incoming], orderBy);

      expect(merged.map((hit) => hit.id)).toEqual(["book-2", "book-1"]);
    },
  );

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

describe("normalizeRealtimeSearchHits", () => {
  it("should_RetainOpenLibraryHit_When_OptionalWireFieldsAreExplicitlyNull", () => {
    const normalizedHits = normalizeRealtimeSearchHits([{
      id: "OL42425854W",
      slug: "OL42425854W",
      title: "Taste of Home Vintage Recipes Made Easy",
      source: "OPEN_LIBRARY",
      description: null,
      authors: null,
      categories: null,
      publishedDate: null,
      language: null,
      pageCount: null,
      publisher: null,
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
      matchType: null,
      relevanceScore: null,
    }]);

    expect(normalizedHits).toHaveLength(1);
    expect(normalizedHits[0]).toMatchObject({
      id: "OL42425854W",
      authors: [],
      categories: [],
      relevanceScore: null,
      publication: {
        publishedDate: null,
        language: null,
        pageCount: null,
        publisher: null,
      },
    });
    expect(normalizedHits[0].cover?.displayUrl).toBeNull();
  });
});
