<script lang="ts">
  /**
   * Grid of similar book cards for the book detail page.
   * Maps domain Book objects to BookCardDisplay for consistent rendering.
   */
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import type { Book } from "$lib/validation/schemas";

  let { books, loadFailed = false }: { books: Book[]; loadFailed?: boolean } = $props();

  function toCard(item: Book): BookCardDisplay {
    return {
      id: item.id,
      slug: item.slug ?? item.id,
      title: item.title ?? "Untitled",
      authors: item.authors.map((author) => author.name),
      description: item.descriptionContent?.text ?? item.description,
      coverUrl: item.cover?.preferredUrl ?? item.cover?.s3ImagePath ?? item.cover?.externalImageUrl ?? null,
      fallbackCoverUrl: item.cover?.fallbackUrl ?? "/images/placeholder-book-cover.svg",
    };
  }
</script>

{#if books.length > 0}
  <section class="space-y-3">
    <h2 class="text-xl font-semibold text-anthracite-900 dark:text-slate-100">Similar books</h2>
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {#each books as item (item.id)}
        <BookCard book={toCard(item)} href={`/book/${encodeURIComponent(item.slug ?? item.id)}`} />
      {/each}
    </div>
  </section>
{:else if loadFailed}
  <section class="space-y-3">
    <h2 class="text-xl font-semibold text-anthracite-900 dark:text-slate-100">Similar books</h2>
    <p class="text-sm text-anthracite-500 dark:text-slate-400">Unable to load similar books.</p>
  </section>
{/if}
