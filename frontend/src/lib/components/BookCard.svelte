<script lang="ts">
  import { Star } from "@lucide/svelte";

  export interface BookCardDisplay {
    id: string;
    slug: string | null;
    title: string;
    authors: string[];
    description?: string | null;
    coverUrl?: string | null;
    fallbackCoverUrl?: string | null;
    averageRating?: number | null;
    ratingsCount?: number | null;
    tags?: Record<string, unknown>;
  }

  let {
    book,
    href,
    layout = "grid",
    showStats = false,
  }: {
    book: BookCardDisplay;
    href: string;
    layout?: "grid" | "list";
    showStats?: boolean;
  } = $props();

  const fallbackImage = "/images/placeholder-book-cover.svg";

  function preferredCoverSource(): string {
    if (book.coverUrl && book.coverUrl.trim().length > 0) {
      return book.coverUrl;
    }
    return fallbackCoverSource();
  }

  function fallbackCoverSource(): string {
    if (book.fallbackCoverUrl && book.fallbackCoverUrl.trim().length > 0) {
      return book.fallbackCoverUrl;
    }
    return fallbackImage;
  }

  let renderedCoverUrl = $state<string>(preferredCoverSource());

  $effect(() => {
    renderedCoverUrl = preferredCoverSource();
  });

  function handleCoverError(): void {
    const failedUrl = renderedCoverUrl;
    const fallbackUrl = fallbackCoverSource();
    if (renderedCoverUrl !== fallbackUrl) {
      console.warn(`[BookCard] Cover load failed for "${book.title}": ${failedUrl} → falling back to ${fallbackUrl}`);
      renderedCoverUrl = fallbackUrl;
      return;
    }
    if (renderedCoverUrl !== fallbackImage) {
      console.warn(`[BookCard] Fallback cover also failed for "${book.title}": ${failedUrl} → using placeholder`);
      renderedCoverUrl = fallbackImage;
    }
  }

  function viewStat(key: string): number | null {
    if (!showStats || !book.tags) return null;
    const value = book.tags[key];
    if (typeof value === "number" && value > 0) return value;
    return null;
  }

  function hasViewStats(): boolean {
    return viewStat("recent.views.24h") !== null
      || viewStat("recent.views.7d") !== null
      || viewStat("recent.views.30d") !== null;
  }

  function lastViewedText(): string | null {
    if (!showStats || !book.tags) return null;
    const raw = book.tags["recent.views.lastViewedAt"];
    if (!raw) return null;
    try {
      const date = new Date(raw as string | number);
      if (Number.isNaN(date.getTime())) return null;
      return `Last viewed ${date.toLocaleDateString("en-US", { month: "short", day: "numeric" })} ${date.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })}`;
    } catch {
      return null;
    }
  }
</script>

{#if layout === "list"}
  <article class="flex gap-4 rounded-xl border border-linen-300 bg-white p-4 shadow-soft transition-all duration-300 hover:shadow-book hover:-translate-y-0.5 dark:border-slate-700 dark:bg-slate-800">
    <a href={href} class="flex h-40 w-28 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-linen-50 dark:bg-slate-900">
      <img src={renderedCoverUrl} alt={`${book.title} cover`} class="max-h-full w-auto object-contain" loading="lazy" onerror={handleCoverError} />
    </a>
    <div class="flex min-w-0 flex-1 flex-col gap-2">
      <a href={href} class="line-clamp-2 break-words text-lg font-semibold text-anthracite-900 transition hover:text-canvas-600 dark:text-slate-100 dark:hover:text-canvas-400" title={book.title}>
        {book.title}
      </a>
      <p class="truncate text-sm font-light text-anthracite-600 dark:text-slate-400">
        {book.authors.length > 0 ? book.authors.join(", ") : "Unknown Author"}
      </p>
      {#if book.description}
        <p class="line-clamp-3 break-words text-sm text-anthracite-600 dark:text-slate-400">{book.description}</p>
      {/if}
      {#if book.averageRating !== null && book.averageRating !== undefined}
        <div class="flex items-center gap-1 text-sm font-medium text-canvas-700 dark:text-canvas-400">
          <Star size={14} class="fill-current" />
          <span>{book.averageRating.toFixed(1)}</span>
          <span class="text-anthracite-500 dark:text-slate-500">({book.ratingsCount ?? 0})</span>
        </div>
      {/if}
    </div>
  </article>
{:else}
  <article class="group flex h-full flex-col overflow-hidden rounded-xl border border-linen-300 shadow-soft transition-all duration-300 hover:-translate-y-1 hover:shadow-book dark:border-slate-700">
    <!-- Cover Section -->
    <div class="relative flex h-80 items-center justify-center overflow-hidden bg-linen-50 p-4 dark:bg-slate-900">
      <a href={href} class="flex h-full w-full items-center justify-center">
        <img
          src={renderedCoverUrl}
          alt={`${book.title} cover`}
          class="max-h-full w-auto object-contain transition-transform duration-300 group-hover:scale-105"
          loading="lazy"
          onerror={handleCoverError}
        />
      </a>

      <!-- Rating Badge -->
      {#if book.averageRating !== null && book.averageRating !== undefined}
        <div class="absolute right-3 top-3 flex items-center gap-1 rounded-lg bg-canvas-400 px-2.5 py-1 text-sm font-medium text-white shadow-sm">
          <Star size={12} class="fill-current" />
          <span>{book.averageRating.toFixed(1)}</span>
        </div>
      {/if}
    </div>

    <!-- Card Content -->
    <div class="flex grow flex-col bg-white p-4 dark:bg-slate-800">
      <h5 class="mb-1.5 line-clamp-2 break-words text-base font-semibold leading-snug text-anthracite-900 dark:text-slate-50" title={book.title}>
        <a href={href} class="transition-colors duration-200 hover:text-canvas-600 dark:hover:text-canvas-400">
          {book.title}
        </a>
      </h5>

      <p class="mb-3 truncate text-sm font-light text-anthracite-600 dark:text-slate-400">
        {book.authors.length > 0 ? book.authors[0] : "Unknown Author"}
      </p>

      <!-- View Stats -->
      {#if hasViewStats()}
        <div class="mb-2 mt-auto flex flex-wrap gap-1.5">
          {#if viewStat("recent.views.24h")}
            <span class="rounded-full bg-linen-100 px-2 py-0.5 text-xs text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">
              {viewStat("recent.views.24h")} in 24h
            </span>
          {/if}
          {#if viewStat("recent.views.7d")}
            <span class="rounded-full bg-linen-100 px-2 py-0.5 text-xs text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">
              {viewStat("recent.views.7d")} in 7d
            </span>
          {/if}
          {#if viewStat("recent.views.30d")}
            <span class="rounded-full bg-linen-100 px-2 py-0.5 text-xs text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">
              {viewStat("recent.views.30d")} in 30d
            </span>
          {/if}
        </div>
      {/if}

      <!-- Last Viewed -->
      {#if lastViewedText()}
        <p class="mb-3 text-xs text-slate-500">{lastViewedText()}</p>
      {/if}
    </div>

    <!-- View Details CTA -->
    <div class="mt-auto bg-white px-4 pb-4 dark:bg-slate-800">
      <a
        href={href}
        class="block w-full rounded-lg bg-canvas-400 px-4 py-2.5 text-center text-sm font-medium text-white transition-all duration-200 hover:bg-canvas-500 hover:shadow-canvas focus:outline-none focus:ring-2 focus:ring-canvas-500 focus:ring-offset-2"
      >
        View Details
      </a>
    </div>
  </article>
{/if}
