<script lang="ts">
  import { onMount, untrack } from "svelte";
  import CategoryFacetPanel from "$lib/components/CategoryFacetPanel.svelte";
  import SearchPageFilters from "$lib/components/SearchPageFilters.svelte";
  import SearchPageStatus from "$lib/components/SearchPageStatus.svelte";
  import SearchResultsPanel from "$lib/components/SearchResultsPanel.svelte";
  import { navigate, type SearchRouteName } from "$lib/router/router";
  import { searchBooks, normalizeRealtimeSearchHits, mergeSearchHits, type SearchParams } from "$lib/services/books";
  import { getCategoryFacets, getHomePagePayload, type PopularWindow } from "$lib/services/pages";
  import { subscribeToSearchTopics } from "$lib/services/realtime";
  import { EXPLORE_DEFAULT_POPULAR_WINDOW, EXPLORE_POPULAR_LIMIT, buildExplorePopularSearchResponse, popularWindowLabel } from "$lib/services/explorePopular";
  import { PAGE_SIZE, PREFETCH_WINDOW_SIZE, CATEGORY_FACET_LIMIT, CATEGORY_MIN_BOOKS, pageFromStartIndex, type CoverOption, type ResolutionOption, type SortOption } from "$lib/services/searchConfig";
  import { buildBookDetailHref, buildExploreDefaultUrl, buildSearchRouteUrl, createSearchParams, mapSearchHitToBookCard, readSearchPageRouteState, searchParamsCacheKey } from "$lib/services/searchPageViewModel";
  import type { SearchHit, SearchResponse, CategoryFacet } from "$lib/validation/schemas";

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
  let explorePopularWindow = $state<PopularWindow>(EXPLORE_DEFAULT_POPULAR_WINDOW);
  let loading = $state(false);
  let errorMessage = $state<string | null>(null);
  let realtimeMessage = $state<string | null>(null);
  let searchResult = $state<SearchResponse | null>(null);
  let searchLoadSequence = 0;

  function syncStateFromUrl(url: URL): void {
    const next = readSearchPageRouteState(routeName, url);
    page = next.page;
    orderBy = next.orderBy;
    coverSource = next.coverSource;
    resolution = next.resolution;
    viewMode = next.viewMode;
    selectedGenres = next.selectedGenres;
    query = next.query;
    explorePopularWindow = next.explorePopularWindow;
  }

  async function loadExplorePopular(sequence: number): Promise<void> {
    loading = true;
    errorMessage = null;
    realtimeMessage = null;

    try {
      const payload = await getHomePagePayload({
        popularWindow: explorePopularWindow,
        popularLimit: EXPLORE_POPULAR_LIMIT,
        recordView: false,
      });
      if (sequence !== searchLoadSequence) return;
      const response = buildExplorePopularSearchResponse(
        payload.popularBooks,
        payload.popularWindow,
        page,
        PAGE_SIZE,
      );
      if (response.totalResults > 0 && response.results.length === 0) {
        const requestedPage = page;
        const lastPage = Math.max(1, Math.ceil(response.totalResults / Math.max(response.maxResults, 1)));
        if (requestedPage > lastPage) {
          applyFilters(lastPage, null, true);
          return;
        }
      }
      searchResult = response;
    } catch (error) {
      if (sequence !== searchLoadSequence) return;
      errorMessage = error instanceof Error ? error.message : "Failed to load popular books";
      searchResult = null;
    } finally {
      if (sequence === searchLoadSequence) loading = false;
    }
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
      const nextKey = searchParamsCacheKey(nextParams);
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

  async function setupRealtimeSubscription(queryHash: string): Promise<(() => void) | null> {
    try {
      return await subscribeToSearchTopics(
        queryHash,
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
      return null;
    }
  }

  async function loadSearch(): Promise<void> {
    const sequence = ++searchLoadSequence;

    if (unsubscribeRealtime) {
      unsubscribeRealtime();
      unsubscribeRealtime = null;
    }

    if (routeName === "explore" && query.trim().length === 0) {
      await loadExplorePopular(sequence);
      return;
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
    const params = createSearchParams(query, page, orderBy, coverSource, resolution, PAGE_SIZE);
    const key = searchParamsCacheKey(params);
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

      unsubscribeRealtime = await setupRealtimeSubscription(response.queryHash);

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
    const targetUrl = buildSearchRouteUrl({
      routeName,
      origin: window.location.origin,
      query,
      page: nextPage,
      orderBy,
      coverSource,
      resolution,
      viewMode,
      selectedGenres: nextGenres ?? selectedGenres,
      explorePopularWindow,
    });
    navigate(targetUrl, replace);
  }

  function toggleGenre(genre: string): void {
    const trimmed = genre.trim();
    if (!trimmed) return;
    const nextGenres = selectedGenres.includes(trimmed)
      ? selectedGenres.filter((existing) => existing !== trimmed)
      : [...selectedGenres, trimmed];
    selectedGenres = nextGenres;
    applyFilters(1, nextGenres);
  }

  function clearGenres(): void {
    selectedGenres = [];
    applyFilters(1, []);
  }

  function bookDetailHref(hit: SearchHit): string {
    return buildBookDetailHref({
      hitId: hit.id,
      hitSlug: hit.slug,
      query,
      routeName,
      explorePopularWindow,
      page,
      orderBy,
      viewMode,
    });
  }

  let pageTitle = $derived(routeName === "explore" ? "Explore Books" : routeName === "categories" ? "Browse Categories" : "Search Books");
  let showingExplorePopular = $derived(routeName === "explore" && query.trim().length === 0);

  function handleSearchSubmit(event: SubmitEvent): void {
    event.preventDefault();
    if (routeName !== "categories") applyFilters(1);
  }

  function handleQueryChange(value: string): void {
    query = value;
  }

  function handleOrderByChange(value: SortOption): void {
    orderBy = value;
    applyFilters(1);
  }

  function handleViewModeChange(value: "grid" | "list"): void {
    viewMode = value;
    applyFilters(page);
  }

  $effect(() => {
    const exploreDefaultUrl = buildExploreDefaultUrl(routeName, currentUrl);
    if (exploreDefaultUrl) {
      navigate(exploreDefaultUrl, true);
      return;
    }
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
      <p class="mt-1 text-sm text-anthracite-600 dark:text-slate-400">
        Most popular books {explorePopularWindow === "all" ? "of" : "from the"} {popularWindowLabel(explorePopularWindow)}. Add a query to switch into search mode.
      </p>
    {/if}
  </header>

  <SearchPageFilters
    {routeName}
    {query}
    {orderBy}
    {viewMode}
    onSubmit={handleSearchSubmit}
    onQueryChange={handleQueryChange}
    onOrderByChange={handleOrderByChange}
    onViewModeChange={handleViewModeChange}
  />

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

  <SearchPageStatus
    {loading}
    {realtimeMessage}
    {errorMessage}
    {searchResult}
    {query}
    {routeName}
    {selectedGenres}
    {showingExplorePopular}
    popularWindowDisplay={popularWindowLabel(explorePopularWindow)}
  />

  {#if searchResult && searchResult.results.length > 0}
    <SearchResultsPanel
      {searchResult}
      currentPage={page}
      pageSize={PAGE_SIZE}
      {viewMode}
      mapHitToCard={mapSearchHitToBookCard}
      {bookDetailHref}
      onNavigate={(p) => applyFilters(p)}
    />
  {/if}
</section>
