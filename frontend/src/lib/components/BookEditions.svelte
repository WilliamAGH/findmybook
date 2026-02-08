<script lang="ts">
  import type { Book } from "$lib/validation/schemas";

  let { editions }: { editions: Book["editions"] } = $props();
</script>

{#if editions.length > 0}
  <section class="space-y-3">
    <h2 class="text-xl font-semibold text-anthracite-900 dark:text-slate-100">Editions</h2>
    <div class="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
      {#each editions as edition}
        <article class="flex gap-3 rounded-xl border border-linen-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
          {#if edition.coverImageUrl}
            <img
              src={edition.coverImageUrl}
              alt="Edition cover"
              class="h-24 w-auto shrink-0 rounded-md object-contain"
              loading="lazy"
            />
          {/if}
          <div class="flex min-w-0 flex-col gap-0.5">
            <p class="text-sm font-medium text-anthracite-800 dark:text-slate-100">
              {edition.identifier ?? edition.googleBooksId ?? "Edition"}
            </p>
            {#if edition.isbn13}
              <p class="text-xs text-anthracite-600 dark:text-slate-300">ISBN-13: {edition.isbn13}</p>
            {/if}
            {#if edition.publishedDate}
              <p class="text-xs text-anthracite-500 dark:text-slate-400">
                Published: {new Date(edition.publishedDate).toLocaleDateString()}
              </p>
            {/if}
          </div>
        </article>
      {/each}
    </div>
  </section>
{/if}
