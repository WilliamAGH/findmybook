<script lang="ts">
  import type { SearchRouteName } from "$lib/router/router";
  import {
    SORT_OPTIONS,
    SORT_LABELS,
    type SortOption,
  } from "$lib/services/searchConfig";
  import { LayoutGrid, List, Search } from "@lucide/svelte";

  let {
    routeName,
    query,
    orderBy,
    viewMode,
    onSubmit,
    onQueryChange,
    onOrderByChange,
    onViewModeChange,
  }: {
    routeName: SearchRouteName;
    query: string;
    orderBy: SortOption;
    viewMode: "grid" | "list";
    onSubmit: (event: SubmitEvent) => void;
    onQueryChange: (value: string) => void;
    onOrderByChange: (value: SortOption) => void;
    onViewModeChange: (value: "grid" | "list") => void;
  } = $props();
</script>

<form
  class="rounded-xl border border-linen-200 bg-white p-4 shadow-soft dark:border-slate-700 dark:bg-slate-800"
  onsubmit={onSubmit}
>
  {#if routeName !== "categories"}
    <div class="flex flex-col gap-3 sm:flex-row">
      <div class="relative flex-1">
        <input
          value={query}
          oninput={(event) => onQueryChange((event.currentTarget as HTMLInputElement).value)}
          aria-label="Search by title, author, or ISBN"
          class="w-full rounded-xl border border-gray-300 bg-white px-6 py-4 pr-14 text-base shadow-soft outline-none transition-all duration-200 focus:border-transparent focus:ring-2 focus:ring-canvas-400 focus:shadow-soft-lg dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 md:text-lg"
          placeholder="Search by title, author, or ISBN..."
        />
        <button
          type="submit"
          class="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg bg-canvas-400 px-3 py-2.5 text-white transition-all duration-200 hover:bg-canvas-500 hover:shadow-canvas focus:outline-none focus:ring-2 focus:ring-canvas-500 focus:ring-offset-2 md:px-5"
          aria-label="Search"
        >
          <Search size={18} />
        </button>
      </div>
    </div>
  {/if}

  <div class={`flex flex-wrap items-center justify-between gap-3 ${routeName === "categories" ? "" : "mt-3"}`}>
    <div class="flex items-center gap-2">
      <label for="sort-select" class="text-xs font-medium text-anthracite-600 dark:text-slate-400">Sort:</label>
      <select
        id="sort-select"
        value={orderBy}
        onchange={(event) => onOrderByChange((event.currentTarget as HTMLSelectElement).value as SortOption)}
        class="rounded-lg border border-linen-300 px-3 py-1.5 text-sm dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100"
      >
        {#each SORT_OPTIONS as option}
          <option value={option}>{SORT_LABELS[option]}</option>
        {/each}
      </select>
    </div>

    <div class="flex items-center gap-1">
      <button
        type="button"
        class={`rounded-md p-2 transition ${viewMode === "grid" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`}
        onclick={() => onViewModeChange("grid")}
        aria-label="Grid view"
      >
        <LayoutGrid size={16} />
      </button>
      <button
        type="button"
        class={`rounded-md p-2 transition ${viewMode === "list" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`}
        onclick={() => onViewModeChange("list")}
        aria-label="List view"
      >
        <List size={16} />
      </button>
    </div>
  </div>
</form>
