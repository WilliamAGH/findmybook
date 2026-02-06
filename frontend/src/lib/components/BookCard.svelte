<script lang="ts">
  export interface BookCardDisplay {
    id: string;
    slug: string | null;
    title: string;
    authors: string[];
    description?: string | null;
    coverUrl?: string | null;
    fallbackCoverUrl?: string | null;
    averageRating?: number | null;
    ratingsCount?: number | null;
  }

  let {
    book,
    href,
    layout = "grid",
  }: {
    book: BookCardDisplay;
    href: string;
    layout?: "grid" | "list";
  } = $props();

  const fallbackImage = "/images/placeholder-book-cover.svg";

  function coverSource(): string {
    if (book.coverUrl && book.coverUrl.trim().length > 0) {
      return book.coverUrl;
    }
    if (book.fallbackCoverUrl && book.fallbackCoverUrl.trim().length > 0) {
      return book.fallbackCoverUrl;
    }
    return fallbackImage;
  }
</script>

{#if layout === "list"}
  <article class="flex gap-4 rounded-xl border border-linen-200 bg-white p-4 shadow-sm transition hover:shadow-md dark:border-slate-700 dark:bg-slate-800">
    <img src={coverSource()} alt={`${book.title} cover`} class="h-40 w-28 shrink-0 rounded-lg object-cover" loading="lazy" />
    <div class="flex min-w-0 flex-1 flex-col gap-2">
      <a href={href} class="line-clamp-2 text-lg font-semibold text-anthracite-900 transition hover:text-canvas-700 dark:text-slate-100 dark:hover:text-canvas-400">
        {book.title}
      </a>
      <p class="line-clamp-1 text-sm text-anthracite-700 dark:text-slate-300">
        {book.authors.length > 0 ? book.authors.join(", ") : "Unknown author"}
      </p>
      {#if book.description}
        <p class="line-clamp-3 text-sm text-anthracite-600 dark:text-slate-400">{book.description}</p>
      {/if}
      {#if book.averageRating !== null && book.averageRating !== undefined}
        <p class="text-xs font-medium text-canvas-700 dark:text-canvas-400">
          {book.averageRating.toFixed(1)} ({book.ratingsCount ?? 0} ratings)
        </p>
      {/if}
    </div>
  </article>
{:else}
  <article class="group overflow-hidden rounded-xl border border-linen-200 bg-white shadow-sm transition hover:-translate-y-0.5 hover:shadow-md dark:border-slate-700 dark:bg-slate-800">
    <a href={href} class="block">
      <img src={coverSource()} alt={`${book.title} cover`} class="h-64 w-full object-cover" loading="lazy" />
    </a>
    <div class="space-y-2 p-4">
      <a href={href} class="line-clamp-2 text-base font-semibold text-anthracite-900 transition group-hover:text-canvas-700 dark:text-slate-100 dark:group-hover:text-canvas-400">
        {book.title}
      </a>
      <p class="line-clamp-1 text-sm text-anthracite-700 dark:text-slate-300">
        {book.authors.length > 0 ? book.authors.join(", ") : "Unknown author"}
      </p>
      {#if book.averageRating !== null && book.averageRating !== undefined}
        <p class="text-xs font-medium text-canvas-700 dark:text-canvas-400">
          {book.averageRating.toFixed(1)} ({book.ratingsCount ?? 0})
        </p>
      {/if}
    </div>
  </article>
{/if}
