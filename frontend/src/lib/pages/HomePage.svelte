<script lang="ts">
  import { onMount } from "svelte";
  import { navigate } from "$lib/router/router";
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import { getHomePagePayload } from "$lib/services/pages";
  import { Search, Info } from "@lucide/svelte";

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
    tags?: Record<string, unknown>;
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
      tags: payload.tags ?? {},
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

  const genres = [
    { label: "Fiction", query: "fiction" },
    { label: "Mystery", query: "mystery" },
    { label: "Science Fiction", query: "sci-fi" },
    { label: "Fantasy", query: "fantasy" },
    { label: "Biography", query: "biography" },
  ];

  onMount(() => {
    void loadHome();
  });
</script>

<!-- Hero Section -->
<section class="bg-gradient-to-b from-linen-50 to-white py-16 transition-colors duration-300 dark:from-slate-900 dark:to-slate-900 md:py-24">
  <div class="mx-auto max-w-4xl px-4">
    <div class="mb-10 text-center">
      <h1 class="mb-4 font-heading text-4xl font-bold tracking-tight text-anthracite-900 dark:text-slate-50 md:text-5xl lg:text-6xl">
        Discover Your Next Great Read
      </h1>
      <p class="mx-auto max-w-2xl text-lg font-light leading-relaxed text-anthracite-600 dark:text-slate-400 md:text-xl">
        Curated book recommendations with a touch of elegance. Search millions of titles and find your perfect match.
      </p>
    </div>

    <!-- Search Form with inline button -->
    <form onsubmit={submitSearch} class="mb-8">
      <div class="relative mx-auto max-w-2xl">
        <input
          bind:value={query}
          type="text"
          aria-label="Search by title, author, or ISBN"
          placeholder="Search by title, author, or ISBN..."
          class="w-full rounded-xl border border-gray-300 bg-white px-6 py-4 pr-14 text-base shadow-soft outline-none transition-all duration-200 focus:border-transparent focus:ring-2 focus:ring-canvas-400 focus:shadow-soft-lg dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 md:text-lg"
          required
        />
        <button
          type="submit"
          class="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg bg-canvas-400 px-3 py-2.5 text-white transition-all duration-200 hover:bg-canvas-500 hover:shadow-canvas focus:outline-none focus:ring-2 focus:ring-canvas-500 focus:ring-offset-2 md:px-5"
          aria-label="Search"
        >
          <Search size={18} />
        </button>
      </div>
    </form>

    <!-- Genre Pills -->
    <div class="text-center">
      <p class="mb-3 text-sm font-medium uppercase tracking-wider text-anthracite-600 dark:text-slate-400">
        Popular Genres
      </p>
      <div class="flex flex-wrap justify-center gap-2">
        {#each genres as genre (genre.query)}
          <a
            href={`/search?query=${encodeURIComponent(genre.query)}`}
            class="rounded-full border border-gray-300 px-4 py-2 text-sm font-medium shadow-sm transition-all duration-200 hover:shadow-md text-anthracite-700 hover:text-anthracite-900 dark:border-slate-600 dark:text-slate-300 dark:hover:text-slate-100 dark:hover:border-slate-500"
          >
            {genre.label}
          </a>
        {/each}
      </div>
    </div>
  </div>
</section>

<!-- Content Sections -->
<section class="mx-auto max-w-6xl px-4 py-12 md:px-6 md:py-16">
  {#if loading}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Loading homepage sections...</p>
  {:else if errorMessage}
    <div class="flex items-center gap-2 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">
      <Info size={16} />
      {errorMessage}
    </div>
  {/if}

  <!-- NYT Bestsellers -->
  <div class="mb-12 space-y-6">
    <div class="flex items-center justify-between">
      <h2 class="font-heading text-2xl font-bold text-anthracite-900 dark:text-slate-50 md:text-3xl">
        NYT Bestsellers
      </h2>
      <a
        href={`/search?query=${encodeURIComponent("new york times")}`}
        class="rounded-lg border border-canvas-400 px-6 py-2.5 text-sm font-medium text-canvas-600 transition-all duration-200 hover:bg-canvas-50 hover:text-canvas-700 dark:border-canvas-600 dark:text-canvas-400 dark:hover:bg-slate-700 dark:hover:text-canvas-300"
      >
        View All
      </a>
    </div>
    {#if !loading && bestsellers.length === 0}
      <div class="flex items-center gap-2 rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm text-blue-700 dark:border-blue-900/40 dark:bg-blue-950/40 dark:text-blue-200">
        <Info size={16} />
        No current bestsellers to display. Check back soon!
      </div>
    {:else}
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {#each bestsellers as book (book.id)}
          <BookCard book={book} href={`/book/${encodeURIComponent(book.slug ?? book.id)}`} />
        {/each}
      </div>
    {/if}
  </div>

  <!-- Recently Viewed -->
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h2 class="font-heading text-2xl font-bold text-anthracite-900 dark:text-slate-50 md:text-3xl">
        Recent Views
      </h2>
      <a
        href="/search?query=explore"
        class="rounded-lg border border-canvas-400 px-6 py-2.5 text-sm font-medium text-canvas-600 transition-all duration-200 hover:bg-canvas-50 hover:text-canvas-700 dark:border-canvas-600 dark:text-canvas-400 dark:hover:bg-slate-700 dark:hover:text-canvas-300"
      >
        Explore More
      </a>
    </div>
    {#if !loading && recentBooks.length === 0}
      <div class="flex items-center gap-2 rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm text-blue-700 dark:border-blue-900/40 dark:bg-blue-950/40 dark:text-blue-200">
        <Info size={16} />
        No recent books to display. Start exploring to see recommendations!
      </div>
    {:else}
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {#each recentBooks as book (book.id)}
          <BookCard book={book} href={`/book/${encodeURIComponent(book.slug ?? book.id)}`} showStats={true} />
        {/each}
      </div>
    {/if}
  </div>
</section>
