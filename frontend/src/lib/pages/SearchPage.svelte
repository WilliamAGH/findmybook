<script lang="ts">
  /**
   * Search page — faceted book search with realtime result streaming and prefetch.
   * Shared by /search, /explore, and /categories while preserving route identity.
   */
  import { onMount, untrack } from "svelte";
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import { navigate, searchBasePathForRoute, type SearchRouteName } from "$lib/router/router";
  import { searchBooks, normalizeRealtimeSearchHits, mergeSearchHits, type SearchParams } from "$lib/services/books";
  import { getCategoryFacets } from "$lib/services/pages";
  import { subscribeToSearchTopics } from "$lib/services/realtime";
  import type { SearchHit, SearchResponse, CategoryFacet } from "$lib/validation/schemas";
  import { Search, LayoutGrid, List, Loader2 } from "@lucide/svelte";
  let { currentUrl, routeName }: { currentUrl: URL; routeName: SearchRouteName } = $props();
  const PAGE_SIZE = 12;
  const PREFETCH_WINDOW_SIZE = 5;
  const CATEGORY_FACET_LIMIT = 24;
  const CATEGORY_MIN_BOOKS = 1;
  const COVER_OPTIONS = ["ANY", "GOOGLE_BOOKS", "OPEN_LIBRARY", "LONGITOOD"] as const;
  const RESOLUTION_OPTIONS = ["ANY", "HIGH_ONLY", "HIGH_FIRST"] as const;
  const SORT_OPTIONS = ["relevance", "title", "author", "newest", "rating"] as const;
  const EXPLORE_DEFAULT_QUERIES = [
    "Classic literature", "Modern thrillers", "Space opera adventures", "Historical fiction bestsellers",
    "Award-winning science fiction", "Inspiring biographies", "Mind-bending philosophy", "Beginner's cookbooks",
    "Epic fantasy sagas", "Cyberpunk futures", "Cozy mysteries", "Environmental science",
    "Artificial intelligence ethics", "World mythology", "Travel memoirs",
  ] as const;
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
    return [params.query, params.startIndex, params.maxResults, params.orderBy, params.coverSource, params.resolution].join("::");
  }
  function parseEnumParam<T extends string>(params: URLSearchParams, key: string, options: readonly T[], fallback: T): T {
    const raw = params.get(key) ?? fallback;
    return options.includes(raw as T) ? (raw as T) : fallback;
  }
  function dedupeGenres(rawGenres: string[]): string[] {
    const deduped = new Set<string>();
    for (const rawGenre of rawGenres) {
      const trimmed = rawGenre.trim();
      if (trimmed.length > 0) {
        deduped.add(trimmed);
      }
    }
    return Array.from(deduped);
  }
  function categoryQueryFromGenres(genres: string[]): string {
    return genres.join(" OR ");
  }
  function syncStateFromUrl(url: URL): void {
    const params = url.searchParams;
    page = parsePositiveNumber(params.get("page"), 1);
    sort = parseEnumParam(params, "sort", SORT_OPTIONS, "newest");
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
  function pickRandomExploreQuery(): string {
    return EXPLORE_DEFAULT_QUERIES[Math.floor(Math.random() * EXPLORE_DEFAULT_QUERIES.length)];
  }
  function ensureExploreDefaultQuery(url: URL): boolean {
    if (routeName !== "explore") {
      return false;
    }
    const currentQuery = url.searchParams.get("query")?.trim();
    if (currentQuery && currentQuery.length > 0) {
      return false;
    }
    const params = new URLSearchParams(url.searchParams);
    params.set("query", pickRandomExploreQuery());
    if (!params.has("page")) {
      params.set("page", "1");
    }
    if (!params.has("sort")) {
      params.set("sort", "newest");
    }
    if (!params.has("view")) {
      params.set("view", "grid");
    }
    if (!params.has("coverSource")) {
      params.set("coverSource", "ANY");
    }
    if (!params.has("resolution")) {
      params.set("resolution", "HIGH_FIRST");
    }
    const basePath = searchBasePathForRoute(routeName);
    navigate(`${basePath}?${params.toString()}`, true);
    return true;
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
    const sequence = ++searchLoadSequence;

    if (unsubscribeRealtime) {
      unsubscribeRealtime();
      unsubscribeRealtime = null;
    }

    if (!query) {
      if (sequence !== searchLoadSequence) return;
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

      searchCache.set(key, response);
      searchResult = response;

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
      if (sequence === searchLoadSequence) {
        loading = false;
      }
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
      for (const genre of genres) {
        url.searchParams.append("genre", genre);
      }
    } else if (query.trim()) {
      url.searchParams.set("query", query.trim());
    }
    url.searchParams.set("page", String(nextPage));
    url.searchParams.set("sort", sort);
    url.searchParams.set("view", viewMode);
    url.searchParams.set("coverSource", coverSource);
    url.searchParams.set("resolution", resolution);
    navigate(`${url.pathname}${url.search}`, replace);
  }
  function toggleGenre(genre: string): void {
    const trimmed = genre.trim();
    if (!trimmed) {
      return;
    }
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
  let pageTitle = $derived(routeName === "explore" ? "Explore Books" : routeName === "categories" ? "Browse Categories" : "Search Books");
  $effect(() => {
    if (ensureExploreDefaultQuery(currentUrl)) {
      return;
    }
    syncStateFromUrl(currentUrl);
    untrack(() => {
      void loadSearch();
    });
  });
  $effect(() => {
    if (routeName !== "categories" || categoryFacetsLoaded) {
      return;
    }
    categoryFacetsLoaded = true;
    untrack(() => {
      void loadCategoryFacetOptions();
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
  <header>
    <h1 class="text-2xl font-semibold text-anthracite-900 dark:text-slate-100">{pageTitle}</h1>
    {#if routeName === "explore"}
      <p class="mt-1 text-sm text-anthracite-600 dark:text-slate-400">A random genre query is loaded by default and stays under the Explore route.</p>
    {/if}
  </header>

  <form class="rounded-xl border border-linen-200 bg-white p-4 shadow-soft dark:border-slate-700 dark:bg-slate-800" onsubmit={(event) => {
    event.preventDefault();
    if (routeName !== "categories") {
      applyFilters(1);
    }
  }}>
    {#if routeName !== "categories"}
      <div class="flex flex-col gap-3 sm:flex-row">
        <div class="relative flex-1">
          <input bind:value={query} aria-label="Search by title, author, or ISBN" class="w-full rounded-lg border border-linen-300 px-4 py-2.5 pr-12 text-sm outline-none ring-canvas-300 transition focus:ring-2 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100" placeholder="Search by title, author, ISBN" />
          <button type="submit" class="absolute right-2 top-1/2 -translate-y-1/2 rounded-md bg-canvas-400 p-1.5 text-white transition hover:bg-canvas-500" aria-label="Search"><Search size={16} /></button>
        </div>
      </div>
    {/if}

    <div class={`flex flex-wrap items-center justify-between gap-3 ${routeName === "categories" ? "" : "mt-3"}`}>
      <div class="flex items-center gap-2">
        <label for="sort-select" class="text-xs font-medium text-anthracite-600 dark:text-slate-400">Sort:</label>
        <select id="sort-select" bind:value={sort} onchange={() => applyFilters(1)} class="rounded-lg border border-linen-300 px-3 py-1.5 text-sm dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100">
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
    <section class="rounded-xl border border-linen-200 bg-white p-4 shadow-soft dark:border-slate-700 dark:bg-slate-800">
      <div class="mb-3 flex items-center justify-between gap-2">
        <p class="text-xs font-semibold uppercase tracking-wide text-anthracite-700 dark:text-slate-300">Toggle genres</p>
        {#if selectedGenres.length > 0}
          <button type="button" class="rounded-md border border-linen-300 px-2 py-1 text-xs font-medium text-anthracite-700 transition hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700" onclick={clearGenres}>Clear all</button>
        {/if}
      </div>

      {#if loadingCategoryFacets}
        <div class="flex items-center gap-2 text-sm text-anthracite-600 dark:text-slate-300"><Loader2 size={14} class="animate-spin" /> Loading genres...</div>
      {:else if categoryFacetError}
        <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">{categoryFacetError}</p>
      {:else if categoryFacets.length === 0}
        <p class="text-sm text-anthracite-600 dark:text-slate-300">No categories available.</p>
      {:else}
        <div class="flex flex-wrap gap-2">
          {#each categoryFacets as facet (facet.name)}
            <button type="button" onclick={() => toggleGenre(facet.name)} aria-pressed={selectedGenres.includes(facet.name)} class={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition ${selectedGenres.includes(facet.name) ? "border-canvas-500 bg-canvas-500 text-white" : "border-linen-300 text-anthracite-700 hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"}`}>
              <span>{facet.name}</span>
              <span class={`rounded-full px-1.5 py-0.5 text-[10px] ${selectedGenres.includes(facet.name) ? "bg-white/20 text-white" : "bg-linen-200 text-anthracite-700 dark:bg-slate-600 dark:text-slate-200"}`}>{facet.bookCount}</span>
            </button>
          {/each}
        </div>
      {/if}
    </section>
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
        <BookCard layout={viewMode} book={mapHitToCard(hit)} href={`/book/${encodeURIComponent(hit.slug ?? hit.id)}?query=${encodeURIComponent(query)}&page=${page}&sort=${encodeURIComponent(sort)}&view=${viewMode}`} />
      {/each}
    </div>

    <nav class="mt-4 flex items-center justify-center gap-1">
      <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800" disabled={page <= 1} onclick={() => applyFilters(page - 1)} aria-label="Previous page">&laquo;</button>

      {#each pages as item}
        {#if item === "ellipsis"}
          <span class="px-2 text-sm text-anthracite-400 dark:text-slate-500">&hellip;</span>
        {:else}
          <button class={`min-w-[2.25rem] rounded-md border px-2 py-1.5 text-sm transition ${item === page ? "border-canvas-500 bg-canvas-500 text-white" : "border-linen-300 text-anthracite-700 hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"}`} onclick={() => applyFilters(item)}>{item}</button>
        {/if}
      {/each}

      <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800" disabled={page >= totalPages} onclick={() => applyFilters(page + 1)} aria-label="Next page">&raquo;</button>
    </nav>
  {/if}
</section>
