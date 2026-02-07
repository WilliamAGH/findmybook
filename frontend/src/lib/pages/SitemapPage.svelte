<script lang="ts">
  import { getSitemapPayload } from "$lib/services/pages";
  import { navigate } from "$lib/router/router";
  import type { SitemapPayload } from "$lib/validation/schemas";
  import { ChevronRight } from "@lucide/svelte";

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
  let openAuthorId = $state<string | null>(null);

  async function loadSitemap(currentView: "authors" | "books", currentLetter: string, currentPage: number): Promise<void> {
    loading = true;
    errorMessage = null;

    try {
      payload = await getSitemapPayload(currentView, currentLetter, currentPage);
      openAuthorId = payload.authors.length > 0 ? payload.authors[0].authorId : null;
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

  function toggleAuthor(authorId: string): void {
    openAuthorId = openAuthorId === authorId ? null : authorId;
  }

  function paginationRange(currentPage: number, total: number): (number | "ellipsis")[] {
    if (total <= 7) {
      return Array.from({ length: total }, (_, i) => i + 1);
    }

    const pages: (number | "ellipsis")[] = [1];
    if (currentPage > 3) pages.push("ellipsis");

    const start = Math.max(2, currentPage - 1);
    const end = Math.min(total - 1, currentPage + 1);
    for (let i = start; i <= end; i++) pages.push(i);

    if (currentPage < total - 2) pages.push("ellipsis");
    pages.push(total);
    return pages;
  }

  $effect(() => {
    void loadSitemap(view, letter, page);
  });
</script>

<section class="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-8 md:px-6">
  <!-- Header -->
  <header class="flex flex-col gap-4 rounded-xl border border-linen-200 bg-white p-5 shadow-soft dark:border-slate-700 dark:bg-slate-800 md:flex-row md:items-center md:justify-between">
    <div>
      <h1 class="text-2xl font-semibold text-anthracite-900 dark:text-slate-100">Site Index</h1>
      <p class="text-sm text-anthracite-600 dark:text-slate-300">
        Server-rendered directory of books and authors for search engines and readers.
      </p>
    </div>
    <div class="flex items-center gap-1">
      <button
        class={`rounded-lg px-4 py-2 text-sm font-medium transition ${view === "authors" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 hover:bg-linen-200 dark:bg-slate-700 dark:text-slate-200 dark:hover:bg-slate-600"}`}
        onclick={() => goTo("authors", letter, 1)}
      >
        Authors &amp; Books
      </button>
      <button
        class={`rounded-lg px-4 py-2 text-sm font-medium transition ${view === "books" ? "bg-canvas-500 text-white" : "bg-linen-100 text-anthracite-700 hover:bg-linen-200 dark:bg-slate-700 dark:text-slate-200 dark:hover:bg-slate-600"}`}
        onclick={() => goTo("books", letter, 1)}
      >
        Books Only
      </button>
    </div>
  </header>

  {#if loading}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Loading sitemap...</p>
  {:else if errorMessage}
    <p class="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">{errorMessage}</p>
  {:else if payload}
    {@const data = payload}

    <!-- Letter Navigation -->
    <div class="rounded-xl border border-linen-200 bg-white p-4 shadow-soft dark:border-slate-700 dark:bg-slate-800">
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-xs font-semibold uppercase tracking-wider text-anthracite-500 dark:text-slate-400">Jump to letter:</span>
        {#each data.letters as bucket}
          <button
            class={`rounded-md px-2.5 py-1 text-xs font-medium transition ${
              bucket === data.activeLetter
                ? "bg-canvas-500 text-white"
                : "bg-linen-100 text-anthracite-700 hover:bg-linen-200 dark:bg-slate-700 dark:text-slate-200 dark:hover:bg-slate-600"
            }`}
            onclick={() => goTo(data.viewType, bucket, 1)}
          >
            {bucket}
          </button>
        {/each}
      </div>
    </div>

    <!-- Content Card -->
    <div class="overflow-hidden rounded-xl border border-linen-200 bg-white shadow-soft dark:border-slate-700 dark:bg-slate-800">
      <!-- Card Header -->
      <div class="border-b border-linen-200 bg-white px-5 py-3 dark:border-slate-700 dark:bg-slate-800">
        <h2 class="text-lg font-semibold text-anthracite-900 dark:text-slate-100">
          {data.viewType === "books" ? "Books" : "Authors"} starting with '{data.activeLetter}'
        </h2>
        <p class="text-sm text-anthracite-500 dark:text-slate-400">
          {data.totalItems} {data.viewType === "books" ? "titles" : "authors"} indexed
        </p>
      </div>

      <div class="p-5">
        {#if data.viewType === "books"}
          <!-- Books View -->
          {#if data.books.length === 0}
            <div class="rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-700 dark:border-blue-900/40 dark:bg-blue-950/40 dark:text-blue-200">
              No books found for this letter yet.
            </div>
          {:else}
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              {#each data.books as item}
                <a
                  href={`/book/${encodeURIComponent(item.slug)}`}
                  class="rounded-xl border border-linen-200 p-4 text-sm transition hover:shadow-md dark:border-slate-700"
                >
                  <p class="font-medium text-anthracite-900 dark:text-slate-100">{item.title}</p>
                  <p class="text-xs text-anthracite-500 dark:text-slate-400">
                    Last updated: {item.updatedAt ? new Date(item.updatedAt).toLocaleDateString() : "Unknown"}
                  </p>
                </a>
              {/each}
            </div>
          {/if}
        {:else}
          <!-- Authors Accordion View -->
          {#if data.authors.length === 0}
            <div class="rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-700 dark:border-blue-900/40 dark:bg-blue-950/40 dark:text-blue-200">
              No authors found for this letter yet.
            </div>
          {:else}
            <div class="divide-y divide-linen-200 rounded-xl border border-linen-200 dark:divide-slate-700 dark:border-slate-700">
              {#each data.authors as author (author.authorId)}
                <div>
                  <button
                    class="flex w-full items-center justify-between px-4 py-3 text-left transition hover:bg-linen-50 dark:hover:bg-slate-800/50"
                    onclick={() => toggleAuthor(author.authorId)}
                    aria-expanded={openAuthorId === author.authorId}
                  >
                    <span class="font-medium text-anthracite-900 dark:text-slate-100">{author.authorName}</span>
                    <div class="flex items-center gap-2">
                      <span class="rounded-full bg-linen-100 px-2.5 py-0.5 text-xs font-medium text-anthracite-600 dark:bg-slate-700 dark:text-slate-300">
                        {author.books.length} {author.books.length === 1 ? "title" : "titles"}
                      </span>
                      <ChevronRight
                        size={16}
                        class={`shrink-0 text-anthracite-400 transition-transform duration-200 dark:text-slate-500 ${openAuthorId === author.authorId ? "rotate-90" : ""}`}
                      />
                    </div>
                  </button>

                  {#if openAuthorId === author.authorId}
                    <div class="border-t border-linen-200 bg-linen-50/50 px-4 py-3 dark:border-slate-700 dark:bg-slate-900/30">
                      {#if author.books.length === 0}
                        <p class="text-sm text-anthracite-500 dark:text-slate-400">No indexed titles for this author yet.</p>
                      {:else}
                        <ul class="space-y-1">
                          {#each author.books as item}
                            <li>
                              <a
                                href={`/book/${encodeURIComponent(item.slug)}`}
                                class="text-sm text-canvas-600 transition hover:text-canvas-700 dark:text-canvas-400 dark:hover:text-canvas-300"
                              >
                                {item.title}
                              </a>
                            </li>
                          {/each}
                        </ul>
                      {/if}
                    </div>
                  {/if}
                </div>
              {/each}
            </div>
          {/if}
        {/if}
      </div>
    </div>

    <!-- Pagination -->
    {#if data.totalPages > 1}
      <nav class="flex items-center justify-center gap-1">
        <button
          class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800"
          disabled={data.pageNumber <= 1}
          onclick={() => goTo(data.viewType, data.activeLetter, data.pageNumber - 1)}
          aria-label="Previous page"
        >
          &laquo;
        </button>

        {#each paginationRange(data.pageNumber, data.totalPages) as item}
          {#if item === "ellipsis"}
            <span class="px-2 text-sm text-anthracite-400 dark:text-slate-500">&hellip;</span>
          {:else}
            <button
              class={`min-w-[2.25rem] rounded-md border px-2 py-1.5 text-sm transition ${
                item === data.pageNumber
                  ? "border-canvas-500 bg-canvas-500 text-white"
                  : "border-linen-300 text-anthracite-700 hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"
              }`}
              onclick={() => goTo(data.viewType, data.activeLetter, item)}
            >
              {item}
            </button>
          {/if}
        {/each}

        <button
          class="rounded-md border border-linen-300 px-3 py-1.5 text-sm transition disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 hover:bg-linen-100 dark:hover:bg-slate-800"
          disabled={data.pageNumber >= data.totalPages}
          onclick={() => goTo(data.viewType, data.activeLetter, data.pageNumber + 1)}
          aria-label="Next page"
        >
          &raquo;
        </button>
      </nav>
    {/if}

    <!-- XML Sitemap Link -->
    <div class="rounded-lg border border-linen-300 bg-linen-50 p-3 text-sm text-anthracite-600 dark:border-slate-700 dark:bg-slate-800/50 dark:text-slate-400">
      XML versions of this sitemap are available at
      <a href="/sitemap.xml" data-no-spa="true" class="font-medium text-canvas-600 hover:text-canvas-700 dark:text-canvas-400 dark:hover:text-canvas-300">
        {data.baseUrl}/sitemap.xml
      </a>.
    </div>
  {/if}
</section>
