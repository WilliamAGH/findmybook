<script lang="ts">
  import BookAffiliateLinks from "$lib/components/BookAffiliateLinks.svelte";
  import BookAiContentPanel from "$lib/components/BookAiContentPanel.svelte";
  import BookCategories from "$lib/components/BookCategories.svelte";
  import BookDescriptionPanel from "$lib/components/book/BookDescriptionPanel.svelte";
  import type { Book, BookAiContentSnapshot } from "$lib/validation/schemas";
  import {
    Star,
    Globe,
    BookOpen,
    Calendar,
    Eye,
  } from "@lucide/svelte";

  const MIN_VIEW_COUNT_DISPLAY_THRESHOLD = 10;
  const TITLE_MAX_LINES = 3;
  const AUTHOR_MAX_LINES = 3;
  const CLAMP_OVERFLOW_EPSILON_PX = 2;

  let {
    book,
    detailCoverUrl,
    affiliateLinks,
    affiliateLinksFailed,
    onAiContentUpdate,
    onCoverLoad,
    onCoverError,
  }: {
    book: Book;
    detailCoverUrl: string;
    affiliateLinks: Record<string, string>;
    affiliateLinksFailed: boolean;
    onAiContentUpdate: (aiContent: BookAiContentSnapshot) => void;
    onCoverLoad: () => void | Promise<void>;
    onCoverError: () => void;
  } = $props();

  let titleElement = $state<HTMLHeadingElement | null>(null);
  let titleExpanded = $state(false);
  let titleOverflows = $state(false);

  let authorElement = $state<HTMLParagraphElement | null>(null);
  let authorExpanded = $state(false);
  let authorOverflows = $state(false);

  let titleResizeObserver: ResizeObserver | null = null;
  let authorResizeObserver: ResizeObserver | null = null;
  let previousBookId = $state<string | null>(null);

  function authorNames(): string {
    if (book.authors.length === 0) {
      return "Unknown author";
    }
    return book.authors.map((author) => author.name).join(", ");
  }

  function publishedDateText(): string {
    if (!book.publication?.publishedDate) {
      return "Unknown";
    }
    const date = new Date(book.publication.publishedDate);
    if (Number.isNaN(date.getTime())) {
      return String(book.publication.publishedDate);
    }
    return date.toLocaleDateString();
  }

  function averageRatingText(): string | null {
    const averageRating = book.extras?.averageRating;
    if (averageRating === undefined || averageRating === null) {
      return null;
    }
    const numericRating = typeof averageRating === "number"
      ? averageRating
      : Number.parseFloat(String(averageRating));
    if (!Number.isFinite(numericRating)) {
      return null;
    }
    return numericRating.toFixed(1);
  }

  let averageRatingDisplay = $derived(averageRatingText());

  function hasClampOverflow(element: HTMLElement, maxLines: number): boolean {
    const scrollDelta = element.scrollHeight - element.clientHeight;
    if (scrollDelta <= CLAMP_OVERFLOW_EPSILON_PX) {
      return false;
    }

    const lineHeight = Number.parseFloat(getComputedStyle(element).lineHeight);
    if (!Number.isFinite(lineHeight) || lineHeight <= 0) {
      return scrollDelta > CLAMP_OVERFLOW_EPSILON_PX;
    }

    const clampThresholdPx = Math.ceil(lineHeight * maxLines) + CLAMP_OVERFLOW_EPSILON_PX;
    return element.scrollHeight > clampThresholdPx;
  }

  function measureTitleOverflow(): void {
    if (!titleElement || titleExpanded) {
      return;
    }
    titleOverflows = hasClampOverflow(titleElement, TITLE_MAX_LINES);
  }

  function measureAuthorOverflow(): void {
    if (!authorElement || authorExpanded) {
      return;
    }
    authorOverflows = hasClampOverflow(authorElement, AUTHOR_MAX_LINES);
  }

  $effect(() => {
    const currentId = book.id ?? null;
    if (currentId && currentId !== previousBookId) {
      previousBookId = currentId;
      titleExpanded = false;
      titleOverflows = false;
      authorExpanded = false;
      authorOverflows = false;
    }
  });

  $effect(() => {
    if (!titleElement) {
      if (titleResizeObserver) {
        titleResizeObserver.disconnect();
        titleResizeObserver = null;
      }
      return;
    }

    if (book.title) {
      measureTitleOverflow();
    }

    if (titleResizeObserver) {
      titleResizeObserver.disconnect();
    }
    titleResizeObserver = new ResizeObserver(() => {
      measureTitleOverflow();
    });
    titleResizeObserver.observe(titleElement);

    return () => {
      if (titleResizeObserver) {
        titleResizeObserver.disconnect();
        titleResizeObserver = null;
      }
    };
  });

  $effect(() => {
    if (!authorElement) {
      if (authorResizeObserver) {
        authorResizeObserver.disconnect();
        authorResizeObserver = null;
      }
      return;
    }

    if (book.authors.length > 0) {
      measureAuthorOverflow();
    }

    if (authorResizeObserver) {
      authorResizeObserver.disconnect();
    }
    authorResizeObserver = new ResizeObserver(() => {
      measureAuthorOverflow();
    });
    authorResizeObserver.observe(authorElement);

    return () => {
      if (authorResizeObserver) {
        authorResizeObserver.disconnect();
        authorResizeObserver = null;
      }
    };
  });
</script>

<article
  class="overflow-clip rounded-xl border border-linen-200 shadow-soft dark:border-slate-700"
  aria-labelledby="book-page-title"
>
  <div class="grid gap-6 p-5 md:grid-cols-[320px_1fr] md:p-8">
    <div class="md:sticky md:top-20 md:self-start relative flex items-center justify-center overflow-hidden rounded-xl bg-linen-50 p-6 dark:bg-slate-900">
      <img
        src={detailCoverUrl}
        alt={`${book.title ?? "Book"} cover`}
        class="max-h-[400px] w-auto rounded-book object-contain shadow-book"
        loading="lazy"
        onload={onCoverLoad}
        onerror={onCoverError}
      />
      {#if averageRatingDisplay}
        <div class="absolute right-3 top-3 flex items-center gap-1 rounded-lg bg-canvas-400 px-2.5 py-1 text-sm font-medium text-white shadow-sm">
          <Star size={12} class="fill-current" />
          <span>{averageRatingDisplay}</span>
        </div>
      {/if}
    </div>

    <div class="flex flex-col gap-4">
      <h1
        id="book-page-title"
        bind:this={titleElement}
        class="break-words text-3xl font-semibold text-balance text-anthracite-900 dark:text-slate-100"
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
      <p
        bind:this={authorElement}
        class="break-words text-base text-anthracite-700 dark:text-slate-300"
        class:line-clamp-3={!authorExpanded}
        title={authorNames()}
      >
        {authorNames()}
      </p>
      {#if authorOverflows}
        <button
          type="button"
          onclick={() => authorExpanded = !authorExpanded}
          class="self-start text-sm font-medium text-canvas-600 transition hover:text-canvas-700 dark:text-canvas-400 dark:hover:text-canvas-300"
        >
          {authorExpanded ? "Show less" : "Show full author"}
        </button>
      {/if}

      {#if (book.viewMetrics?.totalViews ?? 0) > MIN_VIEW_COUNT_DISPLAY_THRESHOLD}
        <p class="flex items-center gap-1.5 text-xs text-sage-500 dark:text-sage-400">
          <Eye size={14} class="shrink-0" />
          <span>{book.viewMetrics!.totalViews.toLocaleString()} views</span>
        </p>
      {/if}

      <h2 id="book-metadata-heading" class="text-lg font-semibold text-anthracite-900 dark:text-slate-100">Book Details</h2>
      <dl class="grid gap-3 text-sm text-anthracite-700 dark:text-slate-300 sm:grid-cols-2">
        {#if book.publication?.publishedDate}
          <div class="flex items-start gap-2">
            <Calendar size={16} class="mt-0.5 shrink-0 text-canvas-500" />
            <div>
              <dt class="font-medium text-anthracite-800 dark:text-slate-200">Published</dt>
              <dd>{publishedDateText()}</dd>
            </div>
          </div>
        {/if}
        {#if book.publication?.publisher}
          <div class="flex items-start gap-2">
            <BookOpen size={16} class="mt-0.5 shrink-0 text-canvas-500" />
            <div>
              <dt class="font-medium text-anthracite-800 dark:text-slate-200">Publisher</dt>
              <dd>{book.publication.publisher}</dd>
            </div>
          </div>
        {/if}
        {#if book.publication?.language}
          <div class="flex items-start gap-2">
            <Globe size={16} class="mt-0.5 shrink-0 text-canvas-500" />
            <div>
              <dt class="font-medium text-anthracite-800 dark:text-slate-200">Language</dt>
              <dd>{book.publication.language}</dd>
            </div>
          </div>
        {/if}
        {#if book.publication?.pageCount}
          <div class="flex items-start gap-2">
            <BookOpen size={16} class="mt-0.5 shrink-0 text-canvas-500" />
            <div>
              <dt class="font-medium text-anthracite-800 dark:text-slate-200">Pages</dt>
              <dd>{book.publication.pageCount.toLocaleString()}</dd>
            </div>
          </div>
        {/if}
      </dl>

      <BookAffiliateLinks links={affiliateLinks} loadFailed={affiliateLinksFailed} />

      <BookDescriptionPanel
        bookId={book.id}
        descriptionHtml={book.descriptionContent?.html}
        descriptionText={book.description}
      />

      <BookAiContentPanel
        identifier={book.id}
        {book}
        {onAiContentUpdate}
      />

      <BookCategories categories={book.categories} />
    </div>
  </div>
</article>
