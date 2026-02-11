<script lang="ts">
  /**
   * Expandable category tag list with fade-out overflow indicator.
   * Self-manages the expand/collapse toggle state.
   *
   * Deduplicates categories by canonical slug before rendering, so
   * "Fiction / Anthologies (multiple authors)" and
   * "Fiction, anthologies (multiple authors)" display as one tag.
   */
  import { ChevronDown, Tag } from "@lucide/svelte";

  let { categories }: { categories: string[] } = $props();

  /** Mirrors CategoryNormalizer.normalizeForDatabase from the backend. */
  function toSlug(name: string): string {
    return name
      .toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, "-")
      .replace(/^-+|-+$/g, "");
  }

  /** Deduplicated categories: first display name wins for each canonical slug. */
  let uniqueCategories = $derived.by(() => {
    const seen = new Map<string, string>();
    for (const cat of categories) {
      const slug = toSlug(cat);
      if (slug && !seen.has(slug)) {
        seen.set(slug, cat);
      }
    }
    return [...seen.values()];
  });

  let expanded = $state(false);
</script>

{#if uniqueCategories.length > 0}
  <section class="space-y-3">
    <h2 class="text-lg font-semibold text-anthracite-900 dark:text-slate-100">Categories</h2>
    <div class="relative">
      <div
        class="flex flex-wrap gap-2 overflow-hidden transition-all duration-300 ease-out"
        class:max-h-[2.75rem]={!expanded && uniqueCategories.length > 6}
        class:max-h-none={expanded || uniqueCategories.length <= 6}
      >
        {#each uniqueCategories as category}
          <span class="inline-flex items-center gap-1 rounded-full bg-linen-100 px-3 py-1 text-xs font-medium text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">
            <Tag size={12} />
            {category}
          </span>
        {/each}
      </div>
      {#if !expanded && uniqueCategories.length > 6}
        <div class="pointer-events-none absolute bottom-0 left-0 right-0 h-6 bg-linear-to-t from-canvas-50 to-transparent dark:from-slate-900"></div>
      {/if}
      {#if uniqueCategories.length > 6}
        <button
          type="button"
          onclick={() => expanded = !expanded}
          class="mt-2 inline-flex items-center gap-1 text-xs font-medium text-canvas-500 transition hover:text-canvas-600 dark:text-canvas-400 dark:hover:text-canvas-300"
        >
          <ChevronDown size={14} class={`transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`} />
          {expanded ? 'Show less' : `Show all ${uniqueCategories.length} tags`}
        </button>
      {/if}
    </div>
  </section>
{/if}
