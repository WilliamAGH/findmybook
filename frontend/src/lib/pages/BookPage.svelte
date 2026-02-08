<script lang="ts">
  import { onMount } from "svelte";
  import DOMPurify from "dompurify";
  import BookAffiliateLinks from "$lib/components/BookAffiliateLinks.svelte";
  import BookAiContentPanel from "$lib/components/BookAiContentPanel.svelte";
  import BookCategories from "$lib/components/BookCategories.svelte";
  import BookEditions from "$lib/components/BookEditions.svelte";
  import BookSimilarBooks from "$lib/components/BookSimilarBooks.svelte";
  import { previousSpaPath } from "$lib/router/router";
  import {
    getAffiliateLinks,
    getBook,
    getSimilarBooks,
  } from "$lib/services/books";
  import { subscribeToBookCoverUpdates } from "$lib/services/realtime";
  import type { Book, BookAiContentSnapshot } from "$lib/validation/schemas";
  import {
    Star,
    ChevronDown,
    ChevronLeft,
    Globe,
    BookOpen,
    Calendar,
    Info,
  } from "@lucide/svelte";

  let {
    currentUrl,
    identifier,
  }: {
    currentUrl: URL;
    identifier: string;
  } = $props();

  let loading = $state(true);
  let errorMessage = $state<string | null>(null);
  let book = $state<Book | null>(null);
  let similarBooks = $state<Book[]>([]);
  let similarBooksFailed = $state(false);
  let affiliateLinks = $state<Record<string, string>>({});
  let affiliateLinksFailed = $state(false);
  let liveCoverUrl = $state<string | null>(null);
  const fallbackCoverImage = "/images/placeholder-book-cover.svg";
  let detailCoverUrl = $state<string>(fallbackCoverImage);

  let titleElement = $state<HTMLHeadingElement | null>(null);
  let titleExpanded = $state(false);
  let titleOverflows = $state(false);

  let descriptionContainer = $state<HTMLElement | null>(null);
  let descriptionExpanded = $state(false);
  let descriptionMeasured = $state(false);
  let descriptionOverflows = $state(false);
  const DESCRIPTION_MAX_LINES = 13;
  const DESCRIPTION_FALLBACK_HEIGHT_PX = 288; // 18rem — roughly 12-13 lines at text-sm leading-relaxed
  let descriptionMaxHeightPx = $state(DESCRIPTION_FALLBACK_HEIGHT_PX);
  let descriptionResizeObserver: ResizeObserver | null = null;

  let unsubscribeRealtime: (() => void) | null = null;
  let loadSequence = 0;

  async function loadPage(): Promise<void> {
    const sequence = ++loadSequence;
    loading = true;
    errorMessage = null;

    if (unsubscribeRealtime) {
      unsubscribeRealtime();
      unsubscribeRealtime = null;
    }

    try {
      const loadedBook = await getBook(identifier);
      if (sequence !== loadSequence) return;

      const [similarResult, linksResult] = await Promise.allSettled([
        getSimilarBooks(identifier, 6),
        getAffiliateLinks(identifier),
      ]);
      if (sequence !== loadSequence) return;

      book = loadedBook;
      if (similarResult.status === "rejected") {
        console.error("Failed to load similar books for", identifier, similarResult.reason);
        similarBooks = [];
        similarBooksFailed = true;
      } else {
        similarBooks = similarResult.value;
        similarBooksFailed = false;
      }
      if (linksResult.status === "rejected") {
        console.error("Failed to load affiliate links for", identifier, linksResult.reason);
        affiliateLinks = {};
        affiliateLinksFailed = true;
      } else {
        affiliateLinks = linksResult.value;
        affiliateLinksFailed = false;
      }
      liveCoverUrl = null;

      const topicIdentifier = loadedBook.id;
      try {
        unsubscribeRealtime = await subscribeToBookCoverUpdates(
          topicIdentifier,
          (coverUrl) => {
            liveCoverUrl = coverUrl;
          },
          (error) => {
            console.error("Realtime cover update error:", error.message);
          },
        );
      } catch (realtimeError) {
        console.error("Realtime cover subscription failed:", realtimeError);
        unsubscribeRealtime = null;
      }
      if (sequence !== loadSequence && unsubscribeRealtime) {
        unsubscribeRealtime();
        unsubscribeRealtime = null;
      }
    } catch (error) {
      if (sequence !== loadSequence) return;
      errorMessage = error instanceof Error ? error.message : "Unable to load this book";
      book = null;
      similarBooks = [];
      similarBooksFailed = false;
      affiliateLinks = {};
      affiliateLinksFailed = false;
    } finally {
      if (sequence === loadSequence) {
        loading = false;
      }
    }
  }

  function handleAiContentUpdate(aiContent: BookAiContentSnapshot): void {
    if (book) {
      book = {
        ...book,
        aiContent,
      };
    }
  }

  function preferredBookCoverUrl(): string {
    if (liveCoverUrl && liveCoverUrl.trim().length > 0) {
      return liveCoverUrl;
    }

    if (book?.cover?.preferredUrl && book.cover.preferredUrl.trim().length > 0) {
      return book.cover.preferredUrl;
    }

    if (book?.cover?.fallbackUrl && book.cover.fallbackUrl.trim().length > 0) {
      return book.cover.fallbackUrl;
    }

    return detailFallbackUrl();
  }

  function detailFallbackUrl(): string {
    if (book?.cover?.fallbackUrl && book.cover.fallbackUrl.trim().length > 0) {
      return book.cover.fallbackUrl;
    }
    return fallbackCoverImage;
  }

  function handleDetailCoverError(): void {
    const failedUrl = detailCoverUrl;
    const fallbackUrl = detailFallbackUrl();
    if (detailCoverUrl !== fallbackUrl) {
      console.warn(`[BookPage] Cover load failed for "${book?.title}": ${failedUrl} → falling back to ${fallbackUrl}`);
      detailCoverUrl = fallbackUrl;
      return;
    }
    if (detailCoverUrl !== fallbackCoverImage) {
      console.warn(`[BookPage] Fallback cover also failed for "${book?.title}": ${failedUrl} → using placeholder`);
      detailCoverUrl = fallbackCoverImage;
    }
  }

  function authorNames(): string {
    if (!book || book.authors.length === 0) {
      return "Unknown author";
    }
    return book.authors.map((author) => author.name).join(", ");
  }

  function publishedDateText(): string {
    if (!book?.publication?.publishedDate) {
      return "Unknown";
    }
    const date = new Date(book.publication.publishedDate);
    if (Number.isNaN(date.getTime())) {
      return String(book.publication.publishedDate);
    }
    return date.toLocaleDateString();
  }

  let sanitizedDescriptionHtml = $derived(
    book?.descriptionContent?.html && book.descriptionContent.html.trim().length > 0
      ? DOMPurify.sanitize(book.descriptionContent.html)
      : "",
  );

  /** Whether the description content is currently height-constrained. */
  let descriptionCollapsed = $derived(
    !descriptionExpanded && (!descriptionMeasured || descriptionOverflows),
  );

  function resolveDescriptionMaxHeightPx(container: HTMLElement): number {
    const lineHeight = Number.parseFloat(getComputedStyle(container).lineHeight);
    if (!Number.isFinite(lineHeight) || lineHeight <= 0) {
      return DESCRIPTION_FALLBACK_HEIGHT_PX;
    }
    return Math.ceil(lineHeight * DESCRIPTION_MAX_LINES);
  }

  function measureDescriptionOverflow(): void {
    requestAnimationFrame(() => {
      if (!descriptionContainer) {
        descriptionMeasured = false;
        return;
      }
      const maxHeightPx = resolveDescriptionMaxHeightPx(descriptionContainer);
      descriptionMaxHeightPx = maxHeightPx;
      descriptionOverflows = descriptionContainer.scrollHeight > maxHeightPx + 1;
      descriptionMeasured = true;
    });
  }

  function legacySearchFallbackHref(): string {
    const query = currentUrl.searchParams.get("query");
    if (!query) {
      return "/";
    }

    const url = new URL("/search", window.location.origin);
    url.searchParams.set("query", query);

    const page = currentUrl.searchParams.get("page");
    const orderBy = currentUrl.searchParams.get("orderBy");
    const view = currentUrl.searchParams.get("view");
    const year = currentUrl.searchParams.get("year");
    if (page) {
      url.searchParams.set("page", page);
    }
    if (orderBy) {
      url.searchParams.set("orderBy", orderBy);
    }
    if (view) {
      url.searchParams.set("view", view);
    }
    if (year) {
      url.searchParams.set("year", year);
    }

    return `${url.pathname}${url.search}`;
  }

  function backHref(): string {
    return previousSpaPath() ?? legacySearchFallbackHref();
  }

  function goBackToPreviousRoute(event: MouseEvent): void {
    const spaPreviousPath = previousSpaPath();
    if (!spaPreviousPath || window.history.length <= 1) {
      return;
    }
    event.preventDefault();
    window.history.back();
  }

  $effect(() => {
    if (identifier) {
      void loadPage();
    }
  });

  $effect(() => {
    detailCoverUrl = preferredBookCoverUrl();
  });

  function measureTitleOverflow(): void {
    requestAnimationFrame(() => {
      if (titleElement) {
        titleOverflows = titleElement.scrollHeight > titleElement.clientHeight;
      }
    });
  }

  $effect(() => {
    if (book) {
      titleExpanded = false;
      titleOverflows = false;
      measureTitleOverflow();

      descriptionExpanded = false;
      descriptionMeasured = false;
      descriptionOverflows = false;
      measureDescriptionOverflow();
    }
  });

  $effect(() => {
    if (!descriptionContainer) {
      if (descriptionResizeObserver) {
        descriptionResizeObserver.disconnect();
        descriptionResizeObserver = null;
      }
      return;
    }

    sanitizedDescriptionHtml;
    book?.description;
    measureDescriptionOverflow();

    if (descriptionResizeObserver) {
      descriptionResizeObserver.disconnect();
    }
    descriptionResizeObserver = new ResizeObserver(() => {
      measureDescriptionOverflow();
    });
    descriptionResizeObserver.observe(descriptionContainer);

    return () => {
      if (descriptionResizeObserver) {
        descriptionResizeObserver.disconnect();
        descriptionResizeObserver = null;
      }
    };
  });

  onMount(() => {
    return () => {
      if (unsubscribeRealtime) {
        unsubscribeRealtime();
      }
    };
  });
</script>

<section class="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-8 md:px-6">
  {#if loading}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Loading book details...</p>
  {:else if errorMessage}
    <div class="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-200">
      {errorMessage}
    </div>
  {:else if book}
    <!-- Back to Previous Route -->
    <a
      href={backHref()}
      onclick={goBackToPreviousRoute}
      class="inline-flex w-fit items-center gap-1.5 rounded-lg border border-linen-300 px-3 py-1.5 text-sm text-anthracite-700 transition hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"
    >
      <ChevronLeft size={16} />
      Back to Results
    </a>

    <!-- Book Detail Card -->
    <article class="overflow-hidden rounded-xl border border-linen-200 shadow-soft dark:border-slate-700">
      <div class="grid gap-6 p-5 md:grid-cols-[320px_1fr] md:p-8">
        <!-- Cover -->
        <div class="relative flex items-center justify-center overflow-hidden rounded-xl bg-linen-50 p-6 dark:bg-slate-900">
          <img
            src={detailCoverUrl}
            alt={`${book.title ?? "Book"} cover`}
            class="max-h-[400px] w-auto rounded-book object-contain shadow-book"
            loading="lazy"
            onerror={handleDetailCoverError}
          />
          {#if book.extras?.averageRating !== undefined && book.extras?.averageRating !== null}
            <div class="absolute right-3 top-3 flex items-center gap-1 rounded-lg bg-canvas-400 px-2.5 py-1 text-sm font-medium text-white shadow-sm">
              <Star size={12} class="fill-current" />
              <span>{Number(book.extras.averageRating).toFixed(1)}</span>
            </div>
          {/if}
        </div>

        <!-- Details -->
        <div class="flex flex-col gap-4">
          <h1
            bind:this={titleElement}
            class="text-3xl font-semibold text-balance text-anthracite-900 dark:text-slate-100"
            class:line-clamp-3={!titleExpanded}
            title={book.title ?? undefined}
          >
            {book.title ?? "Book details"}
          </h1>
          {#if titleOverflows}
            <button
              type="button"
              onclick={() => titleExpanded = !titleExpanded}
              class="self-start text-sm font-medium text-canvas-600 transition hover:text-canvas-700 dark:text-canvas-400 dark:hover:text-canvas-300"
            >
              {titleExpanded ? "Show less" : "Show full title"}
            </button>
          {/if}
          <p class="text-base text-anthracite-700 dark:text-slate-300">{authorNames()}</p>

          <!-- Metadata Grid -->
          <dl class="grid gap-3 text-sm text-anthracite-700 dark:text-slate-300 sm:grid-cols-2">
            <div class="flex items-start gap-2">
              <Calendar size={16} class="mt-0.5 shrink-0 text-canvas-500" />
              <div>
                <dt class="font-medium text-anthracite-800 dark:text-slate-200">Published</dt>
                <dd>{publishedDateText()}</dd>
              </div>
            </div>
            <div class="flex items-start gap-2">
              <BookOpen size={16} class="mt-0.5 shrink-0 text-canvas-500" />
              <div>
                <dt class="font-medium text-anthracite-800 dark:text-slate-200">Publisher</dt>
                <dd>{book.publication?.publisher ?? "Unknown"}</dd>
              </div>
            </div>
            <div class="flex items-start gap-2">
              <Globe size={16} class="mt-0.5 shrink-0 text-canvas-500" />
              <div>
                <dt class="font-medium text-anthracite-800 dark:text-slate-200">Language</dt>
                <dd>{book.publication?.language ?? "Unknown"}</dd>
              </div>
            </div>
            <div class="flex items-start gap-2">
              <Info size={16} class="mt-0.5 shrink-0 text-canvas-500" />
              <div>
                <dt class="font-medium text-anthracite-800 dark:text-slate-200">Pages</dt>
                <dd>{book.publication?.pageCount ?? "Unknown"}</dd>
              </div>
            </div>
          </dl>

          <!-- Description -->
          {#if sanitizedDescriptionHtml.length > 0 || book.description}
            <section class="rounded-xl border border-linen-200 bg-linen-50/60 dark:border-slate-700 dark:bg-slate-900/60">
              <div class="flex items-center justify-between gap-3 px-4 py-3">
                <button
                  type="button"
                  class="inline-flex items-center gap-1.5 text-sm font-medium text-anthracite-700 transition hover:text-anthracite-900 disabled:cursor-default disabled:opacity-60 dark:text-slate-300 dark:hover:text-slate-100"
                  onclick={() => descriptionExpanded = !descriptionExpanded}
                  aria-expanded={descriptionExpanded}
                  disabled={descriptionMeasured && !descriptionOverflows}
                >
                  <ChevronDown
                    size={14}
                    class="shrink-0 transition-transform duration-200 {descriptionExpanded ? '' : '-rotate-90'}"
                  />
                  Book Description
                </button>
                {#if descriptionMeasured && descriptionOverflows}
                  <span class="text-xs font-medium text-canvas-500 dark:text-canvas-400">
                    {descriptionExpanded ? "Show less" : "Show full description"}
                  </span>
                {/if}
              </div>
              <div class="border-t border-linen-200 px-4 pb-4 pt-3 dark:border-slate-700">
                <div class="relative">
                  <div
                    bind:this={descriptionContainer}
                    class="text-sm leading-relaxed text-anthracite-700 dark:text-slate-300 overflow-hidden"
                    class:book-description-content={sanitizedDescriptionHtml.length > 0}
                    class:whitespace-pre-wrap={sanitizedDescriptionHtml.length === 0}
                    style:max-height={descriptionCollapsed ? `${descriptionMaxHeightPx}px` : 'none'}
                  >
                    {#if sanitizedDescriptionHtml.length > 0}
                      {@html sanitizedDescriptionHtml}
                    {:else}
                      {book.description}
                    {/if}
                  </div>
                  {#if descriptionMeasured && descriptionOverflows && !descriptionExpanded}
                    <div class="pointer-events-none absolute inset-x-0 bottom-0 h-10 bg-linear-to-t from-canvas-50/90 via-canvas-50/60 to-transparent backdrop-blur-sm dark:from-slate-900/85 dark:via-slate-900/60"></div>
                  {/if}
                </div>
              </div>
            </section>
          {/if}

          <BookAiContentPanel
            {identifier}
            {book}
            onAiContentUpdate={handleAiContentUpdate}
          />

          <BookCategories categories={book.categories} />

          <BookAffiliateLinks links={affiliateLinks} loadFailed={affiliateLinksFailed} />
        </div>
      </div>
    </article>

    <BookEditions editions={book.editions} />

    <BookSimilarBooks books={similarBooks} loadFailed={similarBooksFailed} />
  {:else}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Book not found.</p>
  {/if}
</section>

<style>
  .book-description-content :global(p),
  .book-description-content :global(ul),
  .book-description-content :global(ol),
  .book-description-content :global(blockquote),
  .book-description-content :global(pre) {
    margin: 0 0 0.75rem 0;
  }

  .book-description-content :global(ul),
  .book-description-content :global(ol) {
    padding-left: 1.2rem;
  }

  .book-description-content :global(ul) {
    list-style-type: disc;
  }

  .book-description-content :global(ol) {
    list-style-type: decimal;
  }

  .book-description-content :global(li) {
    margin-bottom: 0.35rem;
  }

  .book-description-content :global(strong),
  .book-description-content :global(b) {
    font-weight: 600;
  }

  .book-description-content :global(a) {
    color: #0b5ea7;
    text-decoration: underline;
    text-underline-offset: 2px;
  }

  :global([data-theme="dark"]) .book-description-content :global(a) {
    color: #60a5fa;
  }
</style>
