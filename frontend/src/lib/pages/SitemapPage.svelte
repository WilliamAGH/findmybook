<script lang="ts">
  import { getSitemapPayload } from "$lib/services/pages";
  import { navigate } from "$lib/router/router";
  import type { SitemapPayload } from "$lib/validation/schemas";

  let {
    view = "authors",
    letter = "A",
    page = 1,
  }: {
    view?: "authors" | "books";
    letter?: string;
    page?: number;
  } = $props();

  let loading = $state(true);
  let errorMessage = $state<string | null>(null);
  let payload = $state<SitemapPayload | null>(null);

  async function loadSitemap(): Promise<void> {
    loading = true;
    errorMessage = null;

    try {
      payload = await getSitemapPayload(view, letter, page);
    } catch (error) {
      errorMessage = error instanceof Error ? error.message : "Unable to load sitemap";
      payload = null;
    } finally {
      loading = false;
    }
  }

  function goTo(targetView: "authors" | "books", targetLetter: string, targetPage: number): void {
    navigate(`/sitemap/${targetView}/${encodeURIComponent(targetLetter)}/${Math.max(1, targetPage)}`);
  }

  $effect(() => {
    void loadSitemap();
  });
</script>

<section class="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-8 md:px-6">
  <header class="flex flex-col gap-4 rounded-xl border border-linen-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800 md:flex-row md:items-center md:justify-between">
    <div>
      <h1 class="text-2xl font-semibold text-anthracite-900 dark:text-slate-100">Site index</h1>
      <p class="text-sm text-anthracite-600 dark:text-slate-300">Browse indexed books and author directories.</p>
    </div>
    <div class="flex items-center gap-2">
      <button class={`rounded-lg px-3 py-1.5 text-sm ${view === "authors" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`} onclick={() => goTo("authors", letter, 1)}>Authors</button>
      <button class={`rounded-lg px-3 py-1.5 text-sm ${view === "books" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`} onclick={() => goTo("books", letter, 1)}>Books</button>
    </div>
  </header>

  {#if loading}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Loading sitemap...</p>
  {:else if errorMessage}
    <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">{errorMessage}</p>
  {:else if payload}
    {@const data = payload}
    <div class="flex flex-wrap gap-2">
      {#each data.letters as bucket}
        <button
          class={`rounded-md px-2.5 py-1 text-xs ${bucket === data.activeLetter ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 dark:bg-slate-700 dark:text-slate-200"}`}
          onclick={() => goTo(data.viewType, bucket, 1)}
        >
          {bucket}
        </button>
      {/each}
    </div>

    {#if data.viewType === "books"}
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        {#each data.books as item}
          <a href={`/book/${encodeURIComponent(item.slug)}`} class="rounded-xl border border-linen-200 bg-white p-4 text-sm shadow-sm transition hover:shadow-md dark:border-slate-700 dark:bg-slate-800">
            <p class="font-medium text-anthracite-900 dark:text-slate-100">{item.title}</p>
            <p class="text-xs text-anthracite-600 dark:text-slate-400">{item.updatedAt ? new Date(item.updatedAt).toLocaleDateString() : "No update date"}</p>
          </a>
        {/each}
      </div>
    {:else}
      <div class="grid grid-cols-1 gap-3">
        {#each data.authors as author}
          <article class="rounded-xl border border-linen-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
            <p class="text-base font-semibold text-anthracite-900 dark:text-slate-100">{author.authorName}</p>
            <div class="mt-2 flex flex-wrap gap-2">
              {#each author.books as item}
                <a href={`/book/${encodeURIComponent(item.slug)}`} class="rounded-md bg-linen-100 px-2.5 py-1 text-xs text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">{item.title}</a>
              {/each}
            </div>
          </article>
        {/each}
      </div>
    {/if}

    <nav class="flex items-center justify-center gap-2">
      <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm disabled:opacity-40 dark:border-slate-600" disabled={data.pageNumber <= 1} onclick={() => goTo(data.viewType, data.activeLetter, data.pageNumber - 1)}>Prev</button>
      <span class="text-sm text-anthracite-700 dark:text-slate-300">Page {data.pageNumber} of {Math.max(1, data.totalPages)}</span>
      <button class="rounded-md border border-linen-300 px-3 py-1.5 text-sm disabled:opacity-40 dark:border-slate-600" disabled={data.pageNumber >= data.totalPages} onclick={() => goTo(data.viewType, data.activeLetter, data.pageNumber + 1)}>Next</button>
    </nav>

    <p class="text-xs text-anthracite-600 dark:text-slate-400">
      XML sitemap: <a href="/sitemap.xml" data-no-spa="true" class="text-canvas-700 hover:text-canvas-800 dark:text-canvas-400">/sitemap.xml</a>
    </p>
  {/if}
</section>
