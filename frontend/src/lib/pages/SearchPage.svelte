<script lang="ts">
  /**
   * Search page — faceted book search with realtime result streaming and prefetch.
   * Shared by /search, /explore, and /categories while preserving route identity.
   */
  import { onMount, untrack } from "svelte";
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import CategoryFacetPanel from "$lib/components/CategoryFacetPanel.svelte";
  import SearchPagination from "$lib/components/SearchPagination.svelte";
  import { navigate, searchBasePathForRoute, type SearchRouteName } from "$lib/router/router";
  import { searchBooks, normalizeRealtimeSearchHits, mergeSearchHits, type SearchParams } from "$lib/services/books";
  import { getCategoryFacets } from "$lib/services/pages";
  import { subscribeToSearchTopics } from "$lib/services/realtime";
  import {
    PAGE_SIZE, PREFETCH_WINDOW_SIZE, CATEGORY_FACET_LIMIT, CATEGORY_MIN_BOOKS,
    COVER_OPTIONS, RESOLUTION_OPTIONS, SORT_OPTIONS, SORT_LABELS,
    type CoverOption, type ResolutionOption, type SortOption,
    parsePositiveNumber, parseEnumParam, dedupeGenres, categoryQueryFromGenres,
    startIndexFromPage, pageFromStartIndex,
    pickRandomExploreQuery,
  } from "$lib/services/searchConfig";
  import type { SearchHit, SearchResponse, CategoryFacet } from "$lib/validation/schemas";
  import { Search, LayoutGrid, List, Loader2 } from "@lucide/svelte";

  let { currentUrl, routeName }: { currentUrl: URL; routeName: SearchRouteName } = $props();

  const searchCache = new Map<string, SearchResponse>();
  let unsubscribeRealtime: (() => void) | null = null;

  let query = $state("");
  // UI route page is one-based; API calls convert this to zero-based startIndex.
  let page = $state(1);
  let orderBy = $state<SortOption>("newest");
  let coverSource = $state<CoverOption>("ANY");
  let resolution = $state<ResolutionOption>("HIGH_FIRST");
  let viewMode = $state<"grid" | "list">("grid");
  let selectedGenres = $state<string[]>([]);
  let categoryFacets = $state<CategoryFacet[]>([]);
  let categoryFacetsLoaded = $state(false);
  let loadingCategoryFacets = $state(false);
  let categoryFacetError = $state<string | null>(null);
  let loading = $state(false);
  let errorMessage = $state<string | null>(null);
  let realtimeMessage = $state<string | null>(null);
  let searchResult = $state<SearchResponse | null>(null);
  let searchLoadSequence = 0;

  function toSearchParams(): SearchParams {
    return {
      query,
      startIndex: startIndexFromPage(page, PAGE_SIZE),
      maxResults: PAGE_SIZE,
      orderBy,
      coverSource,
      resolution,
    };
  }

  function cacheKey(params: SearchParams): string {
    return JSON.stringify({
      query: params.query,
      startIndex: params.startIndex,
      maxResults: params.maxResults,
      orderBy: params.orderBy,
      coverSource: params.coverSource,
      resolution: params.resolution,
    });
  }

  function syncStateFromUrl(url: URL): void {
    const params = url.searchParams;
    page = parsePositiveNumber(params.get("page"), 1);
    orderBy = parseEnumParam(params, "orderBy", SORT_OPTIONS, "newest");
    coverSource = parseEnumParam(params, "coverSource", COVER_OPTIONS, "ANY");
    resolution = parseEnumParam(params, "resolution", RESOLUTION_OPTIONS, "HIGH_FIRST");
    const nextView = params.get("view");
    viewMode = nextView === "list" ? "list" : "grid";
    if (routeName === "categories") {
      selectedGenres = dedupeGenres(params.getAll("genre"));
      query = categoryQueryFromGenres(selectedGenres);
      return;
    }
    selectedGenres = [];
    query = params.get("query")?.trim() ?? "";
  }

  function ensureExploreDefaultQuery(url: URL): boolean {
    if (routeName !== "explore") return false;
    const currentQuery = url.searchParams.get("query")?.trim();
    if (currentQuery && currentQuery.length > 0) return false;
    const params = new URLSearchParams(url.searchParams);
    params.set("query", pickRandomExploreQuery());
    const defaults: Record<string, string> = { page: "1", orderBy: "newest", view: "grid", coverSource: "ANY", resolution: "HIGH_FIRST" };
    for (const [key, value] of Object.entries(defaults)) {
      if (!params.has(key)) params.set(key, value);
    }
    navigate(`${searchBasePathForRoute(routeName)}?${params.toString()}`, true);
    return true;
  }

  function mergeRealtimeHits(incoming: unknown[]): void {
    if (!searchResult) return;
    const candidates = normalizeRealtimeSearchHits(incoming);
    if (candidates.length === 0) return;
    const mergedResults = mergeSearchHits(searchResult.results, candidates, orderBy);
    searchResult = {
      ...searchResult,
      results: mergedResults,
      totalResults: Math.max(searchResult.totalResults, mergedResults.length),
    };
  }

  async function prefetchWindow(baseParams: SearchParams, response: SearchResponse): Promise<void> {
    if (!response.hasMore) return;
    for (let offset = 1; offset <= PREFETCH_WINDOW_SIZE; offset++) {
      const nextStartIndex = baseParams.startIndex + offset * PAGE_SIZE;
      const nextParams: SearchParams = { ...baseParams, startIndex: nextStartIndex };
      const nextKey = cacheKey(nextParams);
      if (searchCache.has(nextKey)) continue;
      try {
        const nextResponse = await searchBooks(nextParams);
        searchCache.set(nextKey, nextResponse);
        if (!nextResponse.hasMore) break;
      } catch (error) {
        console.warn("Prefetch failed for offset", offset, error);
        break;
      }
    }
  }

  async function loadSearch(): Promise<void> {
    const sequence = ++searchLoadSequence;

    if (unsubscribeRealtime) {
      unsubscribeRealtime();
      unsubscribeRealtime = null;
    }

    if (!query) {
      if (sequence !== searchLoadSequence) return;
      loading = false;
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
      if (sequence !== searchLoadSequence) return;

      const requestedPage = pageFromStartIndex(params.startIndex, params.maxResults);
      if (response.totalResults > 0 && response.results.length === 0) {
        const lastPage = Math.max(1, Math.ceil(response.totalResults / Math.max(response.maxResults, 1)));
        if (requestedPage > lastPage) {
          applyFilters(lastPage, null, true);
          return;
        }
      }

      page = pageFromStartIndex(response.startIndex, response.maxResults);
      searchCache.set(key, response);
      searchResult = response;

      try {
        unsubscribeRealtime = await subscribeToSearchTopics(
          response.queryHash,
          (message) => { realtimeMessage = message; },
          (results) => { mergeRealtimeHits(results); },
          (error) => {
            console.error("Realtime search subscription error:", error.message);
            realtimeMessage = null;
          },
        );
      } catch (realtimeError) {
        console.error("Realtime search subscription failed:", realtimeError);
        realtimeMessage = null;
        unsubscribeRealtime = null;
      }
      if (sequence !== searchLoadSequence && unsubscribeRealtime) {
        unsubscribeRealtime();
        unsubscribeRealtime = null;
      }
      void prefetchWindow(params, response);
    } catch (error) {
      if (sequence !== searchLoadSequence) return;
      errorMessage = error instanceof Error ? error.message : "Search request failed";
      searchResult = null;
    } finally {
      if (sequence === searchLoadSequence) loading = false;
    }
  }

  async function loadCategoryFacetOptions(): Promise<void> {
    loadingCategoryFacets = true;
    categoryFacetError = null;
    try {
      const payload = await getCategoryFacets(CATEGORY_FACET_LIMIT, CATEGORY_MIN_BOOKS);
      categoryFacets = payload.genres;
    } catch (error) {
      categoryFacetError = error instanceof Error ? error.message : "Failed to load categories";
      categoryFacets = [];
    } finally {
      loadingCategoryFacets = false;
    }
  }

  function applyFilters(nextPage = 1, nextGenres: string[] | null = null, replace = false): void {
    const url = new URL(searchBasePathForRoute(routeName), window.location.origin);
    if (routeName === "categories") {
      const genres = nextGenres ?? selectedGenres;
      for (const genre of genres) url.searchParams.append("genre", genre);
    } else if (query.trim()) {
      url.searchParams.set("query", query.trim());
    }
    url.searchParams.set("page", String(nextPage));
    url.searchParams.set("orderBy", orderBy);
    url.searchParams.set("view", viewMode);
    url.searchParams.set("coverSource", coverSource);
    url.searchParams.set("resolution", resolution);
    navigate(`${url.pathname}${url.search}`, replace);
  }

  function toggleGenre(genre: string): void {
    const trimmed = genre.trim();
    if (!trimmed) return;
    const nextGenres = selectedGenres.includes(trimmed)
      ? selectedGenres.filter((existing) => existing !== trimmed)
      : [...selectedGenres, trimmed];
    selectedGenres = nextGenres;
    query = categoryQueryFromGenres(nextGenres);
    applyFilters(1, nextGenres);
  }

  function clearGenres(): void {
    selectedGenres = [];
    query = "";
    applyFilters(1, []);
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

  function bookDetailHref(hit: SearchHit): string {
    const routeIdentifier = hit.slug ?? hit.id;
    const params = new URLSearchParams();
    params.set("bookId", hit.id);
    params.set("query", query);
    params.set("page", String(page));
    params.set("orderBy", orderBy);
    params.set("view", viewMode);
    return `/book/${encodeURIComponent(routeIdentifier)}?${params.toString()}`;
  }

  let pageTitle = $derived(routeName === "explore" ? "Explore Books" : routeName === "categories" ? "Browse Categories" : "Search Books");

  $effect(() => {
    if (ensureExploreDefaultQuery(currentUrl)) return;
    syncStateFromUrl(currentUrl);
    untrack(() => { void loadSearch(); });
  });

  $effect(() => {
    if (routeName !== "categories" || categoryFacetsLoaded) return;
    categoryFacetsLoaded = true;
    untrack(() => { void loadCategoryFacetOptions(); });
  });

  onMount(() => {
    return () => { if (unsubscribeRealtime) unsubscribeRealtime(); };
  });
</script>

<section class="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-8 md:px-6">
  <header>
    <h1 class="text-2xl font-semibold text-anthracite-900 dark:text-slate-100">{pageTitle}</h1>
    {#if routeName === "explore"}
      <p class="mt-1 text-sm text-anthracite-600 dark:text-slate-400">A random genre query is loaded by default and stays under the Explore route.</p>
    {/if}
  </header>

  <form class="rounded-xl border border-linen-200 bg-white p-4 shadow-soft dark:border-slate-700 dark:bg-slate-800" onsubmit={(event) => {
    event.preventDefault();
    if (routeName !== "categories") applyFilters(1);
  }}>
    {#if routeName !== "categories"}
      <div class="flex flex-col gap-3 sm:flex-row">
        <div class="relative flex-1">
          <input bind:value={query} aria-label="Search by title, author, or ISBN" class="w-full rounded-xl border border-gray-300 bg-white px-6 py-4 pr-14 text-base shadow-soft outline-none transition-all duration-200 focus:border-transparent focus:ring-2 focus:ring-canvas-400 focus:shadow-soft-lg dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 md:text-lg" placeholder="Search by title, author, or ISBN..." />
          <button type="submit" class="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg bg-canvas-400 px-3 py-2.5 text-white transition-all duration-200 hover:bg-canvas-500 hover:shadow-canvas focus:outline-none focus:ring-2 focus:ring-canvas-500 focus:ring-offset-2 md:px-5" aria-label="Search"><Search size={18} /></button>
        </div>
      </div>
    {/if}

    <div class={`flex flex-wrap items-center justify-between gap-3 ${routeName === "categories" ? "" : "mt-3"}`}>
      <div class="flex items-center gap-2">
        <label for="sort-select" class="text-xs font-medium text-anthracite-600 dark:text-slate-400">Sort:</label>
        <select id="sort-select" bind:value={orderBy} onchange={() => applyFilters(1)} class="rounded-lg border border-linen-300 px-3 py-1.5 text-sm dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100">
          {#each SORT_OPTIONS as option}
            <option value={option}>{SORT_LABELS[option]}</option>
          {/each}
        </select>
      </div>

      <div class="flex items-center gap-1">
        <button type="button" class={`rounded-md p-2 transition ${viewMode === "grid" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`} onclick={() => { viewMode = "grid"; applyFilters(page); }} aria-label="Grid view"><LayoutGrid size={16} /></button>
        <button type="button" class={`rounded-md p-2 transition ${viewMode === "list" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`} onclick={() => { viewMode = "list"; applyFilters(page); }} aria-label="List view"><List size={16} /></button>
      </div>
    </div>
  </form>

  {#if routeName === "categories"}
    <CategoryFacetPanel
      facets={categoryFacets}
      {selectedGenres}
      loading={loadingCategoryFacets}
      error={categoryFacetError}
      ontoggle={toggleGenre}
      onclear={clearGenres}
    />
  {/if}

  {#if loading}
    <div class="flex items-center gap-2 text-sm text-anthracite-600 dark:text-slate-300"><Loader2 size={16} class="animate-spin" /> Searching books...</div>
  {/if}
  {#if realtimeMessage}
    <div class="flex items-center gap-2 text-xs text-canvas-700 dark:text-canvas-400"><Loader2 size={14} class="animate-spin" /> {realtimeMessage}</div>
  {/if}
  {#if errorMessage}
    <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">{errorMessage}</p>
  {/if}

  {#if !loading && searchResult && query}
    <p class="text-sm text-anthracite-600 dark:text-slate-400">
      Showing {searchResult.results.length} of {searchResult.totalResults} results
      {#if routeName === "categories" && selectedGenres.length > 0}
        for genres <span class="font-medium text-anthracite-900 dark:text-slate-100">{selectedGenres.join(", ")}</span>
      {:else}
        for <span class="font-medium text-anthracite-900 dark:text-slate-100">'{query}'</span>
      {/if}
    </p>
  {/if}

  {#if !loading && !query}
    <div class="text-center py-12">
      <Search size={48} class="mx-auto mb-4 text-linen-400 dark:text-slate-600" />
      {#if routeName === "categories"}
        <p class="text-lg font-medium text-anthracite-700 dark:text-slate-300">Select one or more genres</p>
        <p class="text-sm text-anthracite-500 dark:text-slate-400">Use genre toggles above to load category results.</p>
      {:else}
        <p class="text-lg font-medium text-anthracite-700 dark:text-slate-300">Search for books</p>
        <p class="text-sm text-anthracite-500 dark:text-slate-400">Enter a title, author, or ISBN above to find books.</p>
      {/if}
    </div>
  {:else if !loading && searchResult && searchResult.results.length === 0}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">No results found.</p>
  {/if}

  {#if searchResult && searchResult.results.length > 0}
    <div class={viewMode === "grid" ? "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4" : "grid grid-cols-1 gap-4"}>
      {#each searchResult.results as hit (hit.id)}
        <BookCard layout={viewMode} book={mapHitToCard(hit)} href={bookDetailHref(hit)} />
      {/each}
    </div>

    <SearchPagination
      currentPage={page}
      totalResults={searchResult.totalResults}
      hasMore={searchResult.hasMore}
      pageSize={PAGE_SIZE}
      onnavigate={(p) => applyFilters(p)}
    />
  {/if}
</section>
