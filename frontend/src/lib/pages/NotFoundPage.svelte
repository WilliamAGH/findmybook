<script lang="ts">
  import { navigate } from "$lib/router/router";

  let query = $state("");

  function submitSearch(event: SubmitEvent): void {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) {
      return;
    }
    navigate(`/search?query=${encodeURIComponent(trimmed)}`);
  }
</script>

<section class="mx-auto flex max-w-3xl flex-col items-center gap-6 px-4 py-16 text-center md:px-6">
  <p class="text-6xl font-semibold text-canvas-500">404</p>
  <h1 class="text-3xl font-semibold text-anthracite-900 dark:text-slate-100">Page not found</h1>
  <p class="text-sm text-anthracite-600 dark:text-slate-300">The page you requested does not exist. Try searching for a book instead.</p>

  <form onsubmit={submitSearch} class="flex w-full max-w-xl flex-col gap-3 sm:flex-row">
    <input bind:value={query} type="search" class="w-full rounded-xl border border-linen-300 px-4 py-3 outline-none ring-canvas-300 focus:ring-2 dark:border-slate-600 dark:bg-slate-800" placeholder="Search books" />
    <button type="submit" class="rounded-xl bg-canvas-500 px-5 py-3 font-medium text-white transition hover:bg-canvas-600">Search</button>
  </form>

  <a href="/" class="rounded-lg border border-linen-300 px-4 py-2 text-sm text-anthracite-700 transition hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800">Go home</a>
</section>
