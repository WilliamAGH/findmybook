<script lang="ts">
  /**
   * Page navigation controls for search results.
   * Owns total-page computation and ellipsis range generation internally.
   */
  import { computeTotalPages, paginationRange } from "$lib/services/searchConfig";

  let {
    currentPage,
    totalResults,
    hasMore,
    pageSize,
    onnavigate,
  }: {
    currentPage: number;
    totalResults: number;
    hasMore: boolean;
    pageSize: number;
    onnavigate: (page: number) => void;
  } = $props();

  let totalPages = $derived(computeTotalPages(totalResults, hasMore, currentPage, pageSize));
  let pages = $derived(paginationRange(currentPage, totalPages));
</script>

<nav class="mt-4 flex items-center justify-center gap-1">
  <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800" disabled={currentPage <= 1} onclick={() => onnavigate(currentPage - 1)} aria-label="Previous page">&laquo;</button>

  {#each pages as item}
    {#if item === "ellipsis"}
      <span class="px-2 text-sm text-anthracite-400 dark:text-slate-500">&hellip;</span>
    {:else}
      <button class={`min-w-[2.25rem] rounded-md border px-2 py-1.5 text-sm transition ${item === currentPage ? "border-canvas-500 bg-canvas-500 text-white" : "border-linen-300 text-anthracite-700 hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"}`} onclick={() => onnavigate(item)}>{item}</button>
    {/if}
  {/each}

  <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800" disabled={currentPage >= totalPages} onclick={() => onnavigate(currentPage + 1)} aria-label="Next page">&raquo;</button>
</nav>
