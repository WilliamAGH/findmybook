<script lang="ts">
  /**
   * Expandable category tag list with fade-out overflow indicator.
   * Self-manages the expand/collapse toggle state.
   */
  import { ChevronDown, Tag } from "@lucide/svelte";

  let { categories }: { categories: string[] } = $props();

  let expanded = $state(false);
</script>

{#if categories.length > 0}
  <div class="relative">
    <div
      class="flex flex-wrap gap-2 overflow-hidden transition-all duration-300 ease-out"
      class:max-h-[2.75rem]={!expanded && categories.length > 6}
      class:max-h-none={expanded || categories.length <= 6}
    >
      {#each categories as category}
        <span class="inline-flex items-center gap-1 rounded-full bg-linen-100 px-3 py-1 text-xs font-medium text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">
          <Tag size={12} />
          {category}
        </span>
      {/each}
    </div>
    {#if !expanded && categories.length > 6}
      <div class="pointer-events-none absolute bottom-0 left-0 right-0 h-6 bg-linear-to-t from-canvas-50 to-transparent dark:from-slate-900"></div>
    {/if}
    {#if categories.length > 6}
      <button
        type="button"
        onclick={() => expanded = !expanded}
        class="mt-2 inline-flex items-center gap-1 text-xs font-medium text-canvas-500 transition hover:text-canvas-600 dark:text-canvas-400 dark:hover:text-canvas-300"
      >
        <ChevronDown size={14} class={`transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`} />
        {expanded ? 'Show less' : `Show all ${categories.length} tags`}
      </button>
    {/if}
  </div>
{/if}
