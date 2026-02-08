<script lang="ts">
  /**
   * Genre toggle panel for category-based search filtering.
   * Presentational component — receives all data and emits events to parent.
   */
  import type { CategoryFacet } from "$lib/validation/schemas";
  import { Loader2 } from "@lucide/svelte";

  let {
    facets,
    selectedGenres,
    loading,
    error,
    ontoggle,
    onclear,
  }: {
    facets: CategoryFacet[];
    selectedGenres: string[];
    loading: boolean;
    error: string | null;
    ontoggle: (genre: string) => void;
    onclear: () => void;
  } = $props();
</script>

<section class="rounded-xl border border-linen-200 bg-white p-4 shadow-soft dark:border-slate-700 dark:bg-slate-800">
  <div class="mb-3 flex items-center justify-between gap-2">
    <p class="text-xs font-semibold uppercase tracking-wide text-anthracite-700 dark:text-slate-300">Toggle genres</p>
    {#if selectedGenres.length > 0}
      <button type="button" class="rounded-md border border-linen-300 px-2 py-1 text-xs font-medium text-anthracite-700 transition hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700" onclick={onclear}>Clear all</button>
    {/if}
  </div>

  {#if loading}
    <div class="flex items-center gap-2 text-sm text-anthracite-600 dark:text-slate-300"><Loader2 size={14} class="animate-spin" /> Loading genres...</div>
  {:else if error}
    <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">{error}</p>
  {:else if facets.length === 0}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">No categories available.</p>
  {:else}
    <div class="flex flex-wrap gap-2">
      {#each facets as facet (facet.name)}
        <button type="button" onclick={() => ontoggle(facet.name)} aria-pressed={selectedGenres.includes(facet.name)} class={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition ${selectedGenres.includes(facet.name) ? "border-canvas-500 bg-canvas-500 text-white" : "border-linen-300 text-anthracite-700 hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"}`}>
          <span>{facet.name}</span>
          <span class={`rounded-full px-1.5 py-0.5 text-[10px] ${selectedGenres.includes(facet.name) ? "bg-white/20 text-white" : "bg-linen-200 text-anthracite-700 dark:bg-slate-600 dark:text-slate-200"}`}>{facet.bookCount}</span>
        </button>
      {/each}
    </div>
  {/if}
</section>
