<script lang="ts">
  import { onMount } from "svelte";
  import TopNav from "$lib/components/TopNav.svelte";
  import SiteFooter from "$lib/components/SiteFooter.svelte";
  import HomePage from "$lib/pages/HomePage.svelte";
  import SearchPage from "$lib/pages/SearchPage.svelte";
  import BookPage from "$lib/pages/BookPage.svelte";
  import SitemapPage from "$lib/pages/SitemapPage.svelte";
  import NotFoundPage from "$lib/pages/NotFoundPage.svelte";
  import { currentUrl, initializeSpaRouting, matchRoute } from "$lib/router/router";
  import { initializeTheme } from "$lib/stores/theme";

  let url = $state(new URL(window.location.href));
  let route = $derived(matchRoute(url.pathname));

  onMount(() => {
    const unsubscribe = currentUrl.subscribe((nextUrl) => {
      if (nextUrl.href === url.href) {
        return;
      }
      url = nextUrl;
    });

    const cleanupRouting = initializeSpaRouting();
    void initializeTheme();

    return () => {
      unsubscribe();
      cleanupRouting();
    };
  });
</script>

<div class="flex min-h-screen flex-col bg-linen-50 text-anthracite-900 dark:bg-slate-900 dark:text-slate-100">
  <TopNav activeRoute={route.name} />

  <main class="flex-1">
    {#if route.name === "home"}
      <HomePage />
    {:else if route.name === "search"}
      <SearchPage currentUrl={url} routeName="search" />
    {:else if route.name === "explore"}
      <SearchPage currentUrl={url} routeName="explore" />
    {:else if route.name === "categories"}
      <SearchPage currentUrl={url} routeName="categories" />
    {:else if route.name === "book"}
      <BookPage currentUrl={url} identifier={route.params.identifier ?? ""} />
    {:else if route.name === "sitemap"}
      <SitemapPage
        view={route.params.view ?? "authors"}
        letter={route.params.letter ?? "A"}
        page={route.params.page ?? 1}
      />
    {:else}
      <NotFoundPage />
    {/if}
  </main>

  <SiteFooter />
</div>
