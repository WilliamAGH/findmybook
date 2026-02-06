<script lang="ts">
  import type { RouteName } from "$lib/router/router";
  import { navigate } from "$lib/router/router";
  import { themeMode, updateTheme } from "$lib/stores/theme";

  let { activeRoute }: { activeRoute: RouteName } = $props();

  let quickQuery = $state("");
  let currentTheme = $state<"light" | "dark" | "system">("system");

  const unsubscribe = themeMode.subscribe((value) => {
    currentTheme = value;
  });

  $effect(() => {
    return () => {
      unsubscribe();
    };
  });

  function submitQuickSearch(event: SubmitEvent): void {
    event.preventDefault();
    const trimmed = quickQuery.trim();
    if (!trimmed) {
      return;
    }
    navigate(`/search?query=${encodeURIComponent(trimmed)}`);
  }

  function isActive(route: RouteName): string {
    return activeRoute === route
      ? "bg-canvas-200 text-canvas-900 shadow-sm"
      : "text-anthracite-700 hover:bg-linen-100 hover:text-anthracite-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-slate-100";
  }
</script>

<header class="sticky top-0 z-30 border-b border-linen-200/80 bg-white/90 backdrop-blur dark:border-slate-700 dark:bg-slate-900/90">
  <div class="mx-auto flex max-w-6xl flex-col gap-3 px-4 py-3 md:px-6 lg:flex-row lg:items-center lg:justify-between">
    <div class="flex items-center gap-4">
      <a href="/" class="text-lg font-semibold tracking-tight text-anthracite-900 dark:text-slate-100">FindMyBook</a>
      <nav class="hidden items-center gap-1 rounded-lg border border-linen-200 bg-linen-50 p-1 dark:border-slate-700 dark:bg-slate-800 md:flex">
        <a href="/" class={`rounded-md px-3 py-1.5 text-sm transition ${isActive("home")}`}>Home</a>
        <a href="/search" class={`rounded-md px-3 py-1.5 text-sm transition ${isActive("search")}`}>Search</a>
        <a href="/sitemap" class={`rounded-md px-3 py-1.5 text-sm transition ${isActive("sitemap")}`}>Sitemap</a>
      </nav>
    </div>

    <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
      <form onsubmit={submitQuickSearch} class="flex items-center gap-2">
        <input
          bind:value={quickQuery}
          type="search"
          placeholder="Quick search"
          class="w-full rounded-lg border border-linen-300 bg-white px-3 py-1.5 text-sm text-anthracite-900 outline-none ring-canvas-300 transition focus:ring-2 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-100 sm:w-56"
        />
        <button
          type="submit"
          class="rounded-lg bg-canvas-500 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-canvas-600"
        >
          Go
        </button>
      </form>

      <label class="flex items-center gap-2 text-sm text-anthracite-700 dark:text-slate-200">
        Theme
        <select
          value={currentTheme}
          onchange={(event) => {
            const nextTheme = (event.currentTarget as HTMLSelectElement).value as "light" | "dark" | "system";
            void updateTheme(nextTheme);
          }}
          class="rounded-md border border-linen-300 bg-white px-2 py-1 text-sm dark:border-slate-600 dark:bg-slate-800"
        >
          <option value="system">System</option>
          <option value="light">Light</option>
          <option value="dark">Dark</option>
        </select>
      </label>
    </div>
  </div>
</header>
