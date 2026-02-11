<script lang="ts">
  import type { SearchRouteName } from "$lib/router/router";
  import type { SearchResponse } from "$lib/validation/schemas";
  import { Loader2, Search } from "@lucide/svelte";

  let {
    loading,
    realtimeMessage,
    errorMessage,
    searchResult,
    query,
    routeName,
    selectedGenres,
    showingExplorePopular,
    popularWindowDisplay,
  }: {
    loading: boolean;
    realtimeMessage: string | null;
    errorMessage: string | null;
    searchResult: SearchResponse | null;
    query: string;
    routeName: SearchRouteName;
    selectedGenres: string[];
    showingExplorePopular: boolean;
    popularWindowDisplay: string;
  } = $props();
</script>

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
  <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">
    {errorMessage}
  </p>
{/if}

{#if !loading && searchResult && (query || showingExplorePopular)}
  <p class="text-sm text-anthracite-600 dark:text-slate-400">
    Showing {searchResult.results.length} of {searchResult.totalResults} results
    {#if routeName === "categories" && selectedGenres.length > 0}
      for genres <span class="font-medium text-anthracite-900 dark:text-slate-100">{selectedGenres.join(", ")}</span>
    {:else if showingExplorePopular}
      for <span class="font-medium text-anthracite-900 dark:text-slate-100">most viewed books ({popularWindowDisplay})</span>
    {:else}
      for <span class="font-medium text-anthracite-900 dark:text-slate-100">'{query}'</span>
    {/if}
  </p>
{/if}

{#if !loading && !query && !showingExplorePopular}
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
  <p class="text-sm text-anthracite-600 dark:text-slate-300">
    {showingExplorePopular ? "No popular books found for the selected window." : "No results found."}
  </p>
{/if}
