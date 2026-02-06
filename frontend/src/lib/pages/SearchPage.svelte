<script lang="ts">
  import { onMount } from "svelte";
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import { navigate } from "$lib/router/router";
  import {
    searchBooks,
    normalizeRealtimeSearchHits,
    mergeSearchHits,
    type SearchParams,
  } from "$lib/services/books";
  import { subscribeToSearchTopics } from "$lib/services/realtime";
  import type { SearchHit, SearchResponse } from "$lib/validation/schemas";

  let { currentUrl }: { currentUrl: URL } = $props();

  const PAGE_SIZE = 12;
  const COVER_OPTIONS = ["ANY", "GOOGLE_BOOKS", "OPEN_LIBRARY", "LONGITOOD"] as const;
  const RESOLUTION_OPTIONS = ["ANY", "HIGH_ONLY", "HIGH_FIRST"] as const;
  const SORT_OPTIONS = ["relevance", "title", "author", "newest", "rating"] as const;

  const searchCache = new Map<string, SearchResponse>();
  let unsubscribeRealtime: (() => void) | null = null;

  let query = $state("");
  let year = $state<number | null>(null);
  let page = $state(1);
  let sort = $state<(typeof SORT_OPTIONS)[number]>("newest");
  let coverSource = $state<(typeof COVER_OPTIONS)[number]>("ANY");
  let resolution = $state<(typeof RESOLUTION_OPTIONS)[number]>("HIGH_FIRST");
  let viewMode = $state<"grid" | "list">("grid");

  let loading = $state(false);
  let errorMessage = $state<string | null>(null);
  let realtimeMessage = $state<string | null>(null);
  let searchResult = $state<SearchResponse | null>(null);

  function parsePositiveNumber(value: string | null, fallback: number): number {
    if (!value) {
      return fallback;
    }
    const parsed = Number.parseInt(value, 10);
    if (!Number.isFinite(parsed) || parsed < 1) {
      return fallback;
    }
    return parsed;
  }

  function toSearchParams(): SearchParams {
    return {
      query,
      startIndex: (page - 1) * PAGE_SIZE,
      maxResults: PAGE_SIZE,
      orderBy: sort,
      coverSource,
      resolution,
      publishedYear: year,
    };
  }

  function cacheKey(params: SearchParams): string {
    return [
      params.query,
      params.startIndex,
      params.maxResults,
      params.orderBy,
      params.coverSource,
      params.resolution,
      params.publishedYear ?? "none",
    ].join("::");
  }

  function syncStateFromUrl(url: URL): void {
    const params = url.searchParams;
    query = params.get("query")?.trim() ?? "";
    year = params.get("year") ? parsePositiveNumber(params.get("year"), 0) || null : null;
    page = parsePositiveNumber(params.get("page"), 1);

    const nextSort = params.get("sort") ?? "newest";
    sort = SORT_OPTIONS.includes(nextSort as (typeof SORT_OPTIONS)[number])
      ? (nextSort as (typeof SORT_OPTIONS)[number])
      : "newest";

    const nextCover = params.get("coverSource") ?? "ANY";
    coverSource = COVER_OPTIONS.includes(nextCover as (typeof COVER_OPTIONS)[number])
      ? (nextCover as (typeof COVER_OPTIONS)[number])
      : "ANY";

    const nextResolution = params.get("resolution") ?? "HIGH_FIRST";
    resolution = RESOLUTION_OPTIONS.includes(nextResolution as (typeof RESOLUTION_OPTIONS)[number])
      ? (nextResolution as (typeof RESOLUTION_OPTIONS)[number])
      : "HIGH_FIRST";

    const nextView = params.get("view");
    viewMode = nextView === "list" ? "list" : "grid";
  }

  function mergeRealtimeHits(incoming: unknown[]): void {
    if (!searchResult) {
      return;
    }

    const candidates = normalizeRealtimeSearchHits(incoming);
    if (candidates.length === 0) {
      return;
    }

    const mergedResults = mergeSearchHits(searchResult.results, candidates);
    searchResult = {
      ...searchResult,
      results: mergedResults,
      totalResults: Math.max(searchResult.totalResults, mergedResults.length),
    };
  }

  async function prefetchWindow(baseParams: SearchParams, response: SearchResponse): Promise<void> {
    if (!response.hasMore) {
      return;
    }

    for (let offset = 1; offset <= 5; offset++) {
      const nextStartIndex = baseParams.startIndex + offset * PAGE_SIZE;
      const nextParams: SearchParams = { ...baseParams, startIndex: nextStartIndex };
      const nextKey = cacheKey(nextParams);
      if (searchCache.has(nextKey)) {
        continue;
      }

      try {
        const nextResponse = await searchBooks(nextParams);
        searchCache.set(nextKey, nextResponse);
        if (!nextResponse.hasMore) {
          break;
        }
      } catch {
        break;
      }
    }
  }

  async function loadSearch(): Promise<void> {
    if (!query) {
      if (unsubscribeRealtime) {
        unsubscribeRealtime();
        unsubscribeRealtime = null;
      }
      searchResult = null;
      errorMessage = null;
      realtimeMessage = null;
      return;
    }

    loading = true;
    errorMessage = null;
    realtimeMessage = null;

    const params = toSearchParams();
    const key = cacheKey(params);

    try {
      const response = searchCache.get(key) ?? (await searchBooks(params));
      searchCache.set(key, response);
      searchResult = response;

      if (unsubscribeRealtime) {
        unsubscribeRealtime();
        unsubscribeRealtime = null;
      }

      unsubscribeRealtime = await subscribeToSearchTopics(
        response.queryHash,
        (message) => {
          realtimeMessage = message;
        },
        (results) => {
          mergeRealtimeHits(results);
        },
      );

      void prefetchWindow(params, response);
    } catch (error) {
      errorMessage = error instanceof Error ? error.message : "Search request failed";
      searchResult = null;
    } finally {
      loading = false;
    }
  }

  function applyFilters(nextPage = 1): void {
    const url = new URL("/search", window.location.origin);
    if (query.trim()) {
      url.searchParams.set("query", query.trim());
    }
    if (year !== null && year > 0) {
      url.searchParams.set("year", String(year));
    }
    url.searchParams.set("page", String(nextPage));
    url.searchParams.set("sort", sort);
    url.searchParams.set("view", viewMode);
    url.searchParams.set("coverSource", coverSource);
    url.searchParams.set("resolution", resolution);

    navigate(`${url.pathname}${url.search}`);
  }

  function mapHitToCard(hit: SearchHit): BookCardDisplay {
    const authors = hit.authors.map((author) => author.name).filter((name) => name && name.length > 0);
    return {
      id: hit.id,
      slug: hit.slug ?? hit.id,
      title: hit.title ?? "Untitled",
      authors,
      description: hit.description,
      coverUrl: hit.cover?.preferredUrl ?? hit.cover?.s3ImagePath ?? hit.cover?.externalImageUrl ?? null,
      fallbackCoverUrl: hit.cover?.fallbackUrl ?? "/images/placeholder-book-cover.svg",
    };
  }

  let totalPages = $derived(
    !searchResult
      ? 1
      : searchResult.hasMore
        ? Math.max(Math.max(1, Math.ceil(searchResult.totalResults / PAGE_SIZE)), page + 1)
        : Math.max(1, Math.ceil(searchResult.totalResults / PAGE_SIZE)),
  );

  $effect(() => {
    syncStateFromUrl(currentUrl);
    void loadSearch();
  });

  onMount(() => {
    return () => {
      if (unsubscribeRealtime) {
        unsubscribeRealtime();
      }
    };
  });
</script>

<section class="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-8 md:px-6">
  <form
    class="grid gap-3 rounded-xl border border-linen-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800 md:grid-cols-[1fr_auto_auto_auto_auto]"
    onsubmit={(event) => {
      event.preventDefault();
      applyFilters(1);
    }}
  >
    <input bind:value={query} class="rounded-lg border border-linen-300 px-3 py-2 text-sm outline-none ring-canvas-300 focus:ring-2 dark:border-slate-600 dark:bg-slate-900" placeholder="Search by title, author, ISBN" required />
    <input bind:value={year} type="number" min="1800" max="2099" class="rounded-lg border border-linen-300 px-3 py-2 text-sm outline-none ring-canvas-300 focus:ring-2 dark:border-slate-600 dark:bg-slate-900" placeholder="Year" />
    <select bind:value={sort} class="rounded-lg border border-linen-300 px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-900">
      {#each SORT_OPTIONS as option}
        <option value={option}>{option}</option>
      {/each}
    </select>
    <select bind:value={coverSource} class="rounded-lg border border-linen-300 px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-900">
      {#each COVER_OPTIONS as option}
        <option value={option}>{option}</option>
      {/each}
    </select>
    <select bind:value={resolution} class="rounded-lg border border-linen-300 px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-900">
      {#each RESOLUTION_OPTIONS as option}
        <option value={option}>{option}</option>
      {/each}
    </select>
    <div class="md:col-span-5 flex items-center justify-between gap-3">
      <div class="flex items-center gap-2 text-sm">
        <button type="button" class={`rounded-md px-3 py-1.5 ${viewMode === "grid" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`} onclick={() => { viewMode = "grid"; applyFilters(page); }}>Grid</button>
        <button type="button" class={`rounded-md px-3 py-1.5 ${viewMode === "list" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`} onclick={() => { viewMode = "list"; applyFilters(page); }}>List</button>
      </div>
      <button type="submit" class="rounded-lg bg-canvas-500 px-4 py-2 text-sm font-medium text-white transition hover:bg-canvas-600">Apply</button>
    </div>
  </form>

  {#if loading}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Searching books...</p>
  {/if}
  {#if realtimeMessage}
    <p class="text-xs text-canvas-700 dark:text-canvas-400">{realtimeMessage}</p>
  {/if}
  {#if errorMessage}
    <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">{errorMessage}</p>
  {/if}

  {#if !loading && searchResult && searchResult.results.length === 0}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">No results found.</p>
  {/if}

  {#if searchResult && searchResult.results.length > 0}
    <div class={viewMode === "grid" ? "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4" : "grid grid-cols-1 gap-4"}>
      {#each searchResult.results as hit (hit.id)}
        <BookCard
          layout={viewMode}
          book={mapHitToCard(hit)}
          href={`/book/${encodeURIComponent(hit.slug ?? hit.id)}?query=${encodeURIComponent(query)}&page=${page}&sort=${encodeURIComponent(sort)}&view=${viewMode}${year ? `&year=${year}` : ""}`}
        />
      {/each}
    </div>

    <nav class="mt-4 flex items-center justify-center gap-2">
      <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm disabled:opacity-40 dark:border-slate-600" disabled={page <= 1} onclick={() => applyFilters(page - 1)}>Prev</button>
      <span class="text-sm text-anthracite-700 dark:text-slate-300">Page {page} of {totalPages}</span>
      <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm disabled:opacity-40 dark:border-slate-600" disabled={page >= totalPages} onclick={() => applyFilters(page + 1)}>Next</button>
    </nav>
  {/if}
</section>
