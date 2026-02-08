<!--
  Theme Toggle Component

  Provides a pill-shaped button to toggle between light and dark themes.
  Uses a vertical-slide hover animation to preview the upcoming theme:
  the current icon/label slides up while the opposite slides into view.

  Adapted from the Next.js williamcallahan.com implementation, using
  Svelte 5 runes and the findmybook design-token palette.
-->
<script lang="ts">
  import { themeMode, toggleTheme } from "$lib/stores/theme";
  import type { ResolvedTheme } from "$lib/stores/theme";
  import { Moon, Sun } from "@lucide/svelte";

  let currentTheme = $state<ResolvedTheme>("light");

  const unsubscribe = themeMode.subscribe((value) => {
    currentTheme = value;
  });

  $effect(() => {
    return () => {
      unsubscribe();
    };
  });

  function handleToggle(): void {
    void toggleTheme();
  }

  function ariaLabel(): string {
    return currentTheme === "light" ? "Switch to dark mode" : "Switch to light mode";
  }

  const isDark = $derived(currentTheme === "dark");
</script>

<button
  type="button"
  onclick={handleToggle}
  class="group flex items-center gap-2 px-3 py-1.5 rounded-lg transition-all duration-200
    bg-linen-200 dark:bg-slate-700
    hover:bg-canvas-100 dark:hover:bg-canvas-800/20
    border border-linen-300 dark:border-slate-600
    text-anthracite-700 dark:text-slate-300
    hover:shadow-soft-lg hover:scale-105 active:scale-100"
  title={ariaLabel()}
  aria-label={ariaLabel()}
>
  <!-- Text label — visible at lg+ breakpoint, hidden on mobile -->
  <div class="hidden lg:block text-sm font-medium whitespace-nowrap overflow-hidden relative">
    <span
      class="inline-block transition-transform duration-200 group-hover:-translate-y-full"
    >
      {isDark ? "Dark" : "Light"}
    </span>
    <span
      class="absolute top-0 left-0 translate-y-full transition-transform duration-200 group-hover:translate-y-0"
    >
      {isDark ? "Light" : "Dark"}
    </span>
  </div>

  <!-- Icon with vertical slide animation -->
  <div class="relative h-4 w-4 overflow-hidden">
    <div class="transition-transform duration-200 group-hover:-translate-y-full">
      {#if isDark}
        <Moon size={16} />
      {:else}
        <Sun size={16} />
      {/if}
    </div>
    <div
      class="absolute top-0 left-0 translate-y-full transition-transform duration-200 group-hover:translate-y-0"
    >
      {#if isDark}
        <Sun size={16} />
      {:else}
        <Moon size={16} />
      {/if}
    </div>
  </div>
</button>
