<script lang="ts">
  /**
   * Search page — faceted book search with realtime result streaming and prefetch.
   */
  import { onMount, untrack } from "svelte";
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
  import { Search, LayoutGrid, List, Loader2 } from "@lucide/svelte";

  let { currentUrl }: { currentUrl: URL } = $props();

  const PAGE_SIZE = 12;
  const PREFETCH_WINDOW_SIZE = 5;
  const COVER_OPTIONS = ["ANY", "GOOGLE_BOOKS", "OPEN_LIBRARY", "LONGITOOD"] as const;
  const RESOLUTION_OPTIONS = ["ANY", "HIGH_ONLY", "HIGH_FIRST"] as const;
  const SORT_OPTIONS = ["relevance", "title", "author", "newest", "rating"] as const;

  const SORT_LABELS: Record<(typeof SORT_OPTIONS)[number], string> = {
    relevance: "Most Relevant",
    title: "Title A–Z",
    author: "By Author",
    newest: "Newest First",
    rating: "Highest Rated",
  };

  const searchCache = new Map<string, SearchResponse>();
  let unsubscribeRealtime: (() => void) | null = null;

  let query = $state("");
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
    ].join("::");
  }

  function parseEnumParam<T extends string>(
    params: URLSearchParams,
    key: string,
    options: readonly T[],
    fallback: T,
  ): T {
    const raw = params.get(key) ?? fallback;
    return options.includes(raw as T) ? (raw as T) : fallback;
  }

  function syncStateFromUrl(url: URL): void {
    const params = url.searchParams;
    query = params.get("query")?.trim() ?? "";
    page = parsePositiveNumber(params.get("page"), 1);

    sort = parseEnumParam(params, "sort", SORT_OPTIONS, "newest");
    coverSource = parseEnumParam(params, "coverSource", COVER_OPTIONS, "ANY");
    resolution = parseEnumParam(params, "resolution", RESOLUTION_OPTIONS, "HIGH_FIRST");

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

    for (let offset = 1; offset <= PREFETCH_WINDOW_SIZE; offset++) {
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
      } catch (error) {
        console.warn("Prefetch failed for offset", offset, error);
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
        (error) => {
          console.error("Realtime search subscription error:", error.message);
          realtimeMessage = null;
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
      description: hit.descriptionContent?.text ?? hit.description,
      coverUrl: hit.cover?.preferredUrl ?? hit.cover?.s3ImagePath ?? hit.cover?.externalImageUrl ?? null,
      fallbackCoverUrl: hit.cover?.fallbackUrl ?? "/images/placeholder-book-cover.svg",
    };
  }

  function computeTotalPages(result: SearchResponse | null, currentPage: number, pageSize: number): number {
    if (!result) {
      return 1;
    }
    const fromTotal = Math.max(1, Math.ceil(result.totalResults / pageSize));
    return result.hasMore ? Math.max(fromTotal, currentPage + 1) : fromTotal;
  }

  function paginationRange(currentPage: number, total: number): (number | "ellipsis")[] {
    if (total <= 7) {
      return Array.from({ length: total }, (_, i) => i + 1);
    }

    const pages: (number | "ellipsis")[] = [1];

    if (currentPage > 3) {
      pages.push("ellipsis");
    }

    const start = Math.max(2, currentPage - 1);
    const end = Math.min(total - 1, currentPage + 1);

    for (let i = start; i <= end; i++) {
      pages.push(i);
    }

    if (currentPage < total - 2) {
      pages.push("ellipsis");
    }

    pages.push(total);

    return pages;
  }

  let totalPages = $derived(computeTotalPages(searchResult, page, PAGE_SIZE));
  let pages = $derived(paginationRange(page, totalPages));

  $effect(() => {
    syncStateFromUrl(currentUrl);
    untrack(() => {
      void loadSearch();
    });
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
  <!-- Search Form -->
  <form
    class="rounded-xl border border-linen-200 bg-white p-4 shadow-soft dark:border-slate-700 dark:bg-slate-800"
    onsubmit={(event) => {
      event.preventDefault();
      applyFilters(1);
    }}
  >
    <!-- Search Input Row -->
    <div class="flex flex-col gap-3 sm:flex-row">
      <div class="relative flex-1">
        <input
          bind:value={query}
          class="w-full rounded-lg border border-linen-300 px-4 py-2.5 pr-12 text-sm outline-none ring-canvas-300 transition focus:ring-2 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100"
          placeholder="Search by title, author, ISBN"
          required
        />
        <button
          type="submit"
          class="absolute right-2 top-1/2 -translate-y-1/2 rounded-md bg-canvas-400 p-1.5 text-white transition hover:bg-canvas-500"
          aria-label="Search"
        >
          <Search size={16} />
        </button>
      </div>
    </div>

    <!-- Controls Row -->
    <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
      <!-- Sort -->
      <div class="flex items-center gap-2">
        <label for="sort-select" class="text-xs font-medium text-anthracite-600 dark:text-slate-400">Sort:</label>
        <select
          id="sort-select"
          bind:value={sort}
          onchange={() => applyFilters(1)}
          class="rounded-lg border border-linen-300 px-3 py-1.5 text-sm dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100"
        >
          {#each SORT_OPTIONS as option}
            <option value={option}>{SORT_LABELS[option]}</option>
          {/each}
        </select>
      </div>

      <!-- View Toggle -->
      <div class="flex items-center gap-1">
        <button
          type="button"
          class={`rounded-md p-2 transition ${viewMode === "grid" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`}
          onclick={() => { viewMode = "grid"; applyFilters(page); }}
          aria-label="Grid view"
        >
          <LayoutGrid size={16} />
        </button>
        <button
          type="button"
          class={`rounded-md p-2 transition ${viewMode === "list" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`}
          onclick={() => { viewMode = "list"; applyFilters(page); }}
          aria-label="List view"
        >
          <List size={16} />
        </button>
      </div>
    </div>
  </form>

  <!-- Status Messages -->
  {#if loading}
    <div class="flex items-center gap-2 text-sm text-anthracite-600 dark:text-slate-300">
      <Loader2 size={16} class="animate-spin" />
      Searching books...
    </div>
  {/if}
  {#if realtimeMessage}
    <div class="flex items-center gap-2 text-xs text-canvas-700 dark:text-canvas-400">
      <Loader2 size={14} class="animate-spin" />
      {realtimeMessage}
    </div>
  {/if}
  {#if errorMessage}
    <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">{errorMessage}</p>
  {/if}

  <!-- Result Count -->
  {#if !loading && searchResult && query}
    <p class="text-sm text-anthracite-600 dark:text-slate-400">
      Showing {searchResult.results.length} of {searchResult.totalResults} results for
      <span class="font-medium text-anthracite-900 dark:text-slate-100">'{query}'</span>
    </p>
  {/if}

  {#if !loading && !query}
    <div class="text-center py-12">
      <Search size={48} class="mx-auto mb-4 text-linen-400 dark:text-slate-600" />
      <p class="text-lg font-medium text-anthracite-700 dark:text-slate-300">Search for books</p>
      <p class="text-sm text-anthracite-500 dark:text-slate-400">Enter a title, author, or ISBN above to find books.</p>
    </div>
  {:else if !loading && searchResult && searchResult.results.length === 0}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">No results found.</p>
  {/if}

  <!-- Results Grid/List -->
  {#if searchResult && searchResult.results.length > 0}
    <div class={viewMode === "grid" ? "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4" : "grid grid-cols-1 gap-4"}>
      {#each searchResult.results as hit (hit.id)}
        <BookCard
          layout={viewMode}
          book={mapHitToCard(hit)}
          href={`/book/${encodeURIComponent(hit.slug ?? hit.id)}?query=${encodeURIComponent(query)}&page=${page}&sort=${encodeURIComponent(sort)}&view=${viewMode}`}
        />
      {/each}
    </div>

    <!-- Numbered Pagination -->
    <nav class="mt-4 flex items-center justify-center gap-1">
      <button
        class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800"
        disabled={page <= 1}
        onclick={() => applyFilters(page - 1)}
        aria-label="Previous page"
      >
        &laquo;
      </button>

      {#each pages as item}
        {#if item === "ellipsis"}
          <span class="px-2 text-sm text-anthracite-400 dark:text-slate-500">&hellip;</span>
        {:else}
          <button
            class={`min-w-[2.25rem] rounded-md border px-2 py-1.5 text-sm transition ${
              item === page
                ? "border-canvas-500 bg-canvas-500 text-white"
                : "border-linen-300 text-anthracite-700 hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"
            }`}
            onclick={() => applyFilters(item)}
          >
            {item}
          </button>
        {/if}
      {/each}

      <button
        class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800"
        disabled={page >= totalPages}
        onclick={() => applyFilters(page + 1)}
        aria-label="Next page"
      >
        &raquo;
      </button>
    </nav>
  {/if}
</section>
