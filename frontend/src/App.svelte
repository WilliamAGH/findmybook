<script lang="ts">
  import { onMount, untrack } from "svelte";
  import TopNav from "$lib/components/TopNav.svelte";
  import SiteFooter from "$lib/components/SiteFooter.svelte";
  import HomePage from "$lib/pages/HomePage.svelte";
  import SearchPage from "$lib/pages/SearchPage.svelte";
  import BookPage from "$lib/pages/BookPage.svelte";
  import SitemapPage from "$lib/pages/SitemapPage.svelte";
  import NotFoundPage from "$lib/pages/NotFoundPage.svelte";
  import { currentUrl, initializeSpaRouting, matchRoute } from "$lib/router/router";
  import { getPageMetadata } from "$lib/services/pages";
  import { initializeTheme } from "$lib/stores/theme";
  import type { PageMetadata } from "$lib/validation/schemas";

  const PAGE_TITLE_SUFFIX = " | findmybook";
  const DEFAULT_TITLE = "findmybook";
  const DEFAULT_DESCRIPTION =
    "Discover your next favorite read with findmybook recommendations, search, and curated collections.";
  const DEFAULT_KEYWORDS = "findmybook, book recommendations, book discovery, book search";
  const DEFAULT_OG_IMAGE = "/images/og-logo.png";
  const DEFAULT_ROBOTS = "index, follow, max-image-preview:large";

  const metadataCache = new Map<string, PageMetadata>();

  function titleWithoutSuffix(value: string): string {
    if (!value) {
      return DEFAULT_TITLE;
    }
    return value.endsWith(PAGE_TITLE_SUFFIX) ? value.slice(0, -PAGE_TITLE_SUFFIX.length) : value;
  }

  function titleWithSuffix(value: string): string {
    const normalized = value?.trim() || DEFAULT_TITLE;
    if (normalized.endsWith(PAGE_TITLE_SUFFIX)) {
      return normalized;
    }
    return `${normalized}${PAGE_TITLE_SUFFIX}`;
  }

  function readMetaByName(name: string, fallback: string): string {
    const node = document.querySelector(`meta[name="${name}"]`);
    return node?.getAttribute("content")?.trim() || fallback;
  }

  function readMetaByProperty(property: string, fallback: string): string {
    const node = document.querySelector(`meta[property="${property}"]`);
    return node?.getAttribute("content")?.trim() || fallback;
  }

  function readCanonicalUrl(): string {
    const canonical = document.querySelector('link[rel="canonical"]');
    return canonical?.getAttribute("href")?.trim() || window.location.href;
  }

  let url = $state(new URL(window.location.href));
  let route = $derived(matchRoute(url.pathname));
  let pageMetadata = $state<PageMetadata>({
    title: titleWithoutSuffix(document.title),
    description: readMetaByName("description", DEFAULT_DESCRIPTION),
    canonicalUrl: readCanonicalUrl(),
    keywords: readMetaByName("keywords", DEFAULT_KEYWORDS),
    ogImage: readMetaByProperty("og:image", DEFAULT_OG_IMAGE),
    robots: readMetaByName("robots", DEFAULT_ROBOTS),
    statusCode: 200,
  });
  let metadataLoadSequence = 0;

  async function loadRouteMetadata(pathname: string): Promise<void> {
    const normalizedPath = pathname || "/";
    const sequence = ++metadataLoadSequence;
    const cached = metadataCache.get(normalizedPath);
    if (cached) {
      pageMetadata = cached;
      return;
    }

    try {
      const metadata = await getPageMetadata(normalizedPath);
      if (sequence !== metadataLoadSequence) {
        return;
      }
      metadataCache.set(normalizedPath, metadata);
      pageMetadata = metadata;
    } catch (error) {
      if (sequence !== metadataLoadSequence) {
        return;
      }
      console.error("Failed to load page metadata", error);
      pageMetadata = {
        ...pageMetadata,
        title: titleWithoutSuffix(document.title),
        description: readMetaByName("description", DEFAULT_DESCRIPTION),
        canonicalUrl: readCanonicalUrl(),
        keywords: readMetaByName("keywords", DEFAULT_KEYWORDS),
        ogImage: readMetaByProperty("og:image", DEFAULT_OG_IMAGE),
        robots: readMetaByName("robots", DEFAULT_ROBOTS),
      };
    }
  }

  $effect(() => {
    const pathname = url.pathname;
    untrack(() => {
      void loadRouteMetadata(pathname);
    });
  });

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

<svelte:head>
  <title>{titleWithSuffix(pageMetadata.title)}</title>
  <meta name="description" content={pageMetadata.description} />
  <meta name="keywords" content={pageMetadata.keywords} />
  <meta name="robots" content={pageMetadata.robots} />
  <meta name="googlebot" content={pageMetadata.robots} />
  <meta name="application-name" content="findmybook" />
  <meta name="apple-mobile-web-app-title" content="findmybook" />
  <meta name="theme-color" content="#fdfcfa" />
  <link rel="canonical" href={pageMetadata.canonicalUrl} />
  <link rel="alternate" hreflang="en-US" href={pageMetadata.canonicalUrl} />
  <link rel="alternate" hreflang="x-default" href={pageMetadata.canonicalUrl} />

  <meta property="og:type" content="website" />
  <meta property="og:site_name" content="findmybook" />
  <meta property="og:locale" content="en_US" />
  <meta property="og:url" content={pageMetadata.canonicalUrl} />
  <meta property="og:title" content={titleWithSuffix(pageMetadata.title)} />
  <meta property="og:description" content={pageMetadata.description} />
  <meta property="og:image" content={pageMetadata.ogImage} />
  <meta property="og:image:alt" content="findmybook social preview image" />

  <meta name="twitter:card" content="summary_large_image" />
  <meta name="twitter:domain" content="findmybook.net" />
  <meta name="twitter:url" content={pageMetadata.canonicalUrl} />
  <meta name="twitter:title" content={titleWithSuffix(pageMetadata.title)} />
  <meta name="twitter:description" content={pageMetadata.description} />
  <meta name="twitter:image" content={pageMetadata.ogImage} />
  <meta name="twitter:image:alt" content="findmybook social preview image" />
</svelte:head>

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
