<script lang="ts">
  import type { RouteName } from "$lib/router/router";
  import { navigate } from "$lib/router/router";
  import { themeMode, toggleTheme } from "$lib/stores/theme";
  import type { ResolvedTheme } from "$lib/stores/theme";
  import { Home, Search, BookOpen, Moon, Sun, Menu, X } from "@lucide/svelte";

  let { activeRoute }: { activeRoute: RouteName } = $props();

  let quickQuery = $state("");
  let mobileOpen = $state(false);
  let currentTheme = $state<ResolvedTheme>("light");

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
    mobileOpen = false;
    navigate(`/search?query=${encodeURIComponent(trimmed)}`);
  }

  function handleToggleTheme(): void {
    void toggleTheme();
  }

  type NavItem = { href: string; route: RouteName | "explore"; label: string };

  const navItems: NavItem[] = [
    { href: "/", route: "home", label: "Home" },
    { href: "/search", route: "search", label: "Search" },
    { href: "/explore", route: "explore", label: "Explore" },
  ];

  function navIcon(route: string): typeof Home {
    switch (route) {
      case "home": return Home;
      case "search": return Search;
      case "explore": return BookOpen;
      default: return Home;
    }
  }

  function isActive(route: string): boolean {
    return activeRoute === route;
  }

  function navBtnClass(route: string): string {
    if (isActive(route)) {
      return "inline-flex items-center gap-2 rounded-md px-3.5 py-2 text-sm font-semibold text-canvas-800 dark:text-canvas-500 transition-all duration-200 bg-canvas-200/85 dark:bg-canvas-600/15 shadow-[0_1px_3px_rgba(212,165,116,0.4),0_1px_2px_rgba(212,165,116,0.3)] dark:shadow-[0_1px_3px_rgba(168,151,120,0.2)]";
    }
    return "inline-flex items-center gap-2 rounded-md px-3.5 py-2 text-sm font-medium text-anthracite-700 dark:text-slate-400 transition-all duration-200 hover:text-anthracite-800 dark:hover:text-slate-50 hover:bg-canvas-400/8 dark:hover:bg-slate-700/50";
  }
</script>

<header class="sticky top-0 z-30 border-b border-linen-200/80 dark:border-slate-700 transition-all duration-300 bg-[rgba(253,252,250,0.80)] dark:bg-[rgba(30,41,59,0.95)] backdrop-blur-[12px]">
  <div class="mx-auto flex h-14 max-w-6xl items-center px-4 md:px-6">
    <!-- Logo (LEFT) — fixed width for symmetry -->
    <div class="w-[200px] shrink-0">
      <a href="/" class="group flex items-center">
        <img
          src="/images/findmybook-logo-white-bg.png"
          alt="FindMyBook Logo"
          class="h-[140px] w-auto -ml-4 transition-transform duration-200 group-hover:scale-105 block dark:hidden"
        />
        <img
          src="/images/findmybook-logo.png"
          alt="FindMyBook Logo"
          class="h-12 w-auto transition-transform duration-200 group-hover:scale-105 hidden dark:block"
        />
      </a>
    </div>

    <!-- Spacer -->
    <div class="hidden min-w-4 grow lg:flex"></div>

    <!-- Desktop Navigation (CENTER) -->
    <nav class="hidden shrink-0 items-center gap-1 lg:flex">
      {#each navItems as item (item.route)}
        {@const NavIcon = navIcon(item.route)}
        <a href={item.href} class={navBtnClass(item.route)}>
          <NavIcon size={16} />
          <span class="tracking-wide">{item.label}</span>
        </a>
      {/each}
    </nav>

    <!-- Spacer -->
    <div class="hidden min-w-4 grow lg:flex"></div>

    <!-- Theme Toggle (RIGHT) — fixed width matching logo -->
    <div class="hidden w-[200px] shrink-0 justify-end lg:flex">
      <button
        type="button"
        onclick={handleToggleTheme}
        class="group inline-flex items-center justify-center rounded-md p-2 text-anthracite-700 dark:text-slate-400 transition-all duration-200 hover:text-anthracite-800 dark:hover:text-slate-50 hover:bg-canvas-400/8 dark:hover:bg-slate-700/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-canvas-400"
        title={currentTheme === "light" ? "Light \u2192 Dark" : "Dark \u2192 Light"}
        aria-label={currentTheme === "light" ? "Switch to dark mode" : "Switch to light mode"}
      >
        {#if currentTheme === "light"}
          <Sun size={18} class="block group-hover:hidden" />
          <Moon size={18} class="hidden group-hover:block" />
        {:else}
          <Moon size={18} class="block group-hover:hidden" />
          <Sun size={18} class="hidden group-hover:block" />
        {/if}
      </button>
    </div>

    <!-- Mobile Controls (RIGHT) -->
    <div class="ml-auto flex items-center gap-1.5 lg:hidden">
      <button
        type="button"
        onclick={handleToggleTheme}
        class="group inline-flex items-center justify-center rounded-md p-2 text-anthracite-700 dark:text-slate-400 transition hover:bg-canvas-400/8 dark:hover:bg-slate-700/50"
        title={currentTheme === "light" ? "Light \u2192 Dark" : "Dark \u2192 Light"}
        aria-label={currentTheme === "light" ? "Switch to dark mode" : "Switch to light mode"}
      >
        {#if currentTheme === "light"}
          <Sun size={18} class="block group-hover:hidden" />
          <Moon size={18} class="hidden group-hover:block" />
        {:else}
          <Moon size={18} class="block group-hover:hidden" />
          <Sun size={18} class="hidden group-hover:block" />
        {/if}
      </button>

      <button
        type="button"
        onclick={() => (mobileOpen = !mobileOpen)}
        class="inline-flex items-center justify-center rounded-md p-2 text-anthracite-700 dark:text-slate-400 transition hover:bg-canvas-400/8 dark:hover:bg-slate-700/50"
        aria-label="Toggle navigation"
        aria-expanded={mobileOpen}
      >
        {#if mobileOpen}
          <X size={18} />
        {:else}
          <Menu size={18} />
        {/if}
      </button>
    </div>
  </div>

  <!-- Mobile Navigation Panel -->
  {#if mobileOpen}
    <div class="border-t border-anthracite-200/60 dark:border-slate-700 px-4 py-3 lg:hidden">
      <nav class="space-y-1">
        {#each navItems as item (item.route)}
          {@const NavIcon = navIcon(item.route)}
          <a
            href={item.href}
            onclick={() => (mobileOpen = false)}
            class={`${navBtnClass(item.route)} w-full justify-start`}
          >
            <NavIcon size={16} />
            <span class="tracking-wide">{item.label}</span>
          </a>
        {/each}
      </nav>

      <form onsubmit={submitQuickSearch} class="mt-3 flex items-center gap-2">
        <input
          bind:value={quickQuery}
          type="search"
          aria-label="Quick search"
          placeholder="Quick search..."
          class="w-full rounded-lg border border-linen-300 bg-white px-3 py-2 text-sm text-anthracite-900 outline-none ring-canvas-300 transition focus:ring-2 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-100"
        />
        <button
          type="submit"
          class="rounded-lg bg-canvas-400 px-3 py-2 text-sm font-medium text-white transition hover:bg-canvas-500 hover:shadow-canvas"
        >
          <Search size={16} />
        </button>
      </form>
    </div>
  {/if}
</header>
