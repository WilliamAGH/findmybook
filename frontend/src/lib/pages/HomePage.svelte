<script lang="ts">
  import { onMount } from "svelte";
  import { navigate } from "$lib/router/router";
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import { getHomePagePayload } from "$lib/services/pages";

  let loading = $state(true);
  let errorMessage = $state<string | null>(null);
  let bestsellers = $state<BookCardDisplay[]>([]);
  let recentBooks = $state<BookCardDisplay[]>([]);
  let query = $state("");

  function toDisplayCard(payload: {
    id: string;
    slug?: string | null;
    title: string;
    authors?: string[];
    cover_url?: string | null;
    fallback_cover_url?: string | null;
    average_rating?: number | null;
    ratings_count?: number | null;
  }): BookCardDisplay {
    return {
      id: payload.id,
      slug: payload.slug ?? payload.id,
      title: payload.title,
      authors: payload.authors ?? [],
      coverUrl: payload.cover_url ?? null,
      fallbackCoverUrl: payload.fallback_cover_url ?? null,
      averageRating: payload.average_rating ?? null,
      ratingsCount: payload.ratings_count ?? null,
    };
  }

  async function loadHome(): Promise<void> {
    loading = true;
    errorMessage = null;

    try {
      const payload = await getHomePagePayload();
      bestsellers = payload.currentBestsellers.map(toDisplayCard);
      recentBooks = payload.recentBooks.map(toDisplayCard);
    } catch (error) {
      errorMessage = error instanceof Error ? error.message : "Unable to load homepage content";
      bestsellers = [];
      recentBooks = [];
    } finally {
      loading = false;
    }
  }

  function submitSearch(event: SubmitEvent): void {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) {
      return;
    }
    navigate(`/search?query=${encodeURIComponent(trimmed)}`);
  }

  onMount(() => {
    void loadHome();
  });
</script>

<section class="bg-gradient-to-b from-linen-50 to-white px-4 py-10 dark:from-slate-900 dark:to-slate-900 md:px-6 md:py-16">
  <div class="mx-auto max-w-4xl text-center">
    <h1 class="text-3xl font-semibold tracking-tight text-anthracite-900 dark:text-slate-100 md:text-5xl">Find your next great read</h1>
    <p class="mx-auto mt-4 max-w-2xl text-base text-anthracite-700 dark:text-slate-300 md:text-lg">
      Search by title, author, or ISBN with realtime results, rich book details, and sitemap browsing.
    </p>
    <form onsubmit={submitSearch} class="mx-auto mt-8 flex max-w-2xl flex-col gap-3 sm:flex-row">
      <input
        bind:value={query}
        type="search"
        placeholder="Search by title, author, or ISBN"
        class="w-full rounded-xl border border-linen-300 bg-white px-4 py-3 text-anthracite-900 outline-none ring-canvas-300 transition focus:ring-2 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-100"
        required
      />
      <button type="submit" class="rounded-xl bg-canvas-500 px-5 py-3 font-medium text-white transition hover:bg-canvas-600">Search</button>
    </form>
  </div>
</section>

<section class="mx-auto grid max-w-6xl gap-10 px-4 py-8 md:px-6 md:py-12">
  {#if loading}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Loading homepage sections...</p>
  {:else if errorMessage}
    <div class="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">
      {errorMessage}
    </div>
  {/if}

  <div class="space-y-4">
    <div class="flex items-center justify-between">
      <h2 class="text-2xl font-semibold text-anthracite-900 dark:text-slate-100">NYT Bestsellers</h2>
      <a href="/search?query=new%20york%20times" class="text-sm font-medium text-canvas-700 transition hover:text-canvas-800 dark:text-canvas-400">View all</a>
    </div>
    {#if !loading && bestsellers.length === 0}
      <p class="text-sm text-anthracite-600 dark:text-slate-400">No current bestsellers available.</p>
    {:else}
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {#each bestsellers as book (book.id)}
          <BookCard book={book} href={`/book/${encodeURIComponent(book.slug ?? book.id)}`} />
        {/each}
      </div>
    {/if}
  </div>

  <div class="space-y-4">
    <div class="flex items-center justify-between">
      <h2 class="text-2xl font-semibold text-anthracite-900 dark:text-slate-100">Recently Viewed</h2>
      <a href="/search?query=explore" class="text-sm font-medium text-canvas-700 transition hover:text-canvas-800 dark:text-canvas-400">Explore more</a>
    </div>
    {#if !loading && recentBooks.length === 0}
      <p class="text-sm text-anthracite-600 dark:text-slate-400">No recent books yet. Start searching to build your list.</p>
    {:else}
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {#each recentBooks as book (book.id)}
          <BookCard book={book} href={`/book/${encodeURIComponent(book.slug ?? book.id)}`} />
        {/each}
      </div>
    {/if}
  </div>
</section>
