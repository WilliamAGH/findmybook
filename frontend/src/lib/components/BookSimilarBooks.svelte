<script lang="ts">
  /**
   * Cover-forward recommendation section for the book detail page.
   *
   * Filters similar books to those with displayable covers using the same
   * validation pipeline as the backend CoverQuality/CoverPrioritizer:
   * non-null renderable URL, non-placeholder, valid aspect ratio when
   * dimensions are known, and runtime load-failure tracking.
   */
  import type { Book } from "$lib/validation/schemas";

  /** Matches CoverQuality.isRenderable() placeholder detection. */
  const PLACEHOLDER_COVER_SEGMENT = "placeholder-book-cover.svg";

  /** Matches ImageDimensionUtils.MIN_ASPECT_RATIO (height / width). */
  const MIN_COVER_ASPECT_RATIO = 1.2;

  /** Matches ImageDimensionUtils.MAX_ASPECT_RATIO (height / width). */
  const MAX_COVER_ASPECT_RATIO = 2.0;

  let { books, loadFailed = false }: { books: Book[]; loadFailed?: boolean } = $props();

  let failedCoverBookIds = $state(new Set<string>());
  let fallbackAttemptedBookIds = $state(new Set<string>());
  let activeCoverUrlsByBookId = $state(new Map<string, string>());
  let coverStateSignature = $state("");

  /**
   * Resolves the best available cover URL for a book.
   * Priority mirrors toCard() and mapSearchHitToBookCard() across the frontend.
   */
  function resolveCoverUrl(book: Book): string | null {
    return book.cover?.preferredUrl
      ?? book.cover?.s3ImagePath
      ?? book.cover?.externalImageUrl
      ?? null;
  }

  /** Mirrors CoverQuality.isRenderable() and coverRelayPersistence placeholder check. */
  function isPlaceholder(url: string): boolean {
    return url.toLowerCase().includes(PLACEHOLDER_COVER_SEGMENT);
  }

  /**
   * Validates aspect ratio when dimensions are known.
   * Mirrors ImageDimensionUtils.hasValidAspectRatio() — unknown dimensions
   * pass validation because the backend has already ranked cover quality.
   */
  function hasKnownValidAspectRatio(
    width: number | null | undefined,
    height: number | null | undefined,
  ): boolean {
    if (!width || !height || width <= 0 || height <= 0) {
      return true;
    }
    const ratio = height / width;
    return ratio >= MIN_COVER_ASPECT_RATIO && ratio <= MAX_COVER_ASPECT_RATIO;
  }

  /**
   * Determines whether a book has a cover suitable for the recommendation grid.
   * Combines: renderable URL + non-placeholder + valid aspect ratio + no runtime failure.
   */
  function hasDisplayableCover(book: Book): boolean {
    const url = activeCoverUrlsByBookId.get(book.id) ?? resolveCoverUrl(book);
    if (!url || url.trim().length === 0) {
      return false;
    }
    if (isPlaceholder(url)) {
      return false;
    }
    if (!hasKnownValidAspectRatio(book.cover?.width, book.cover?.height)) {
      return false;
    }
    return !failedCoverBookIds.has(book.id);
  }

  let displayableBooks = $derived(books.filter(hasDisplayableCover));

  function resolveFallbackUrl(book: Book): string | null {
    const fallback = book.cover?.fallbackUrl;
    if (!fallback || fallback.trim().length === 0 || isPlaceholder(fallback)) {
      return null;
    }
    return fallback;
  }

  function authorNames(book: Book): string {
    if (book.authors.length === 0) {
      return "Unknown author";
    }
    return book.authors.map((a) => a.name).join(", ");
  }

  function bookHref(book: Book): string {
    return `/book/${encodeURIComponent(book.slug ?? book.id)}`;
  }

  /** Attempts fallback URL on cover load failure; hides the card when all URLs fail. */
  function handleCoverError(bookId: string, event: Event): void {
    const img = event.target as HTMLImageElement;
    const book = books.find((b) => b.id === bookId);
    if (!book) {
      console.warn(
        `[BookSimilarBooks] Cover error for bookId="${bookId}" but book not found in recommendations array (${books.length} books). Removing from display.`,
      );
      failedCoverBookIds = new Set([...failedCoverBookIds, bookId]);
      return;
    }

    const fallback = resolveFallbackUrl(book);
    if (fallback && !fallbackAttemptedBookIds.has(bookId)) {
      fallbackAttemptedBookIds = new Set([...fallbackAttemptedBookIds, bookId]);
      const nextCoverUrlsByBookId = new Map(activeCoverUrlsByBookId);
      nextCoverUrlsByBookId.set(bookId, fallback);
      activeCoverUrlsByBookId = nextCoverUrlsByBookId;
      img.src = fallback;
      return;
    }

    failedCoverBookIds = new Set([...failedCoverBookIds, bookId]);
  }

  $effect(() => {
    const nextSignature = books
      .map((book) => `${book.id}:${resolveCoverUrl(book) ?? ""}`)
      .join("|");
    if (nextSignature === coverStateSignature) {
      return;
    }

    coverStateSignature = nextSignature;
    failedCoverBookIds = new Set();
    fallbackAttemptedBookIds = new Set();

    const nextCoverUrlsByBookId = new Map<string, string>();
    for (const book of books) {
      const url = resolveCoverUrl(book);
      if (url && url.trim().length > 0) {
        nextCoverUrlsByBookId.set(book.id, url);
      }
    }
    activeCoverUrlsByBookId = nextCoverUrlsByBookId;
  });
</script>

{#if displayableBooks.length > 0}
  <section class="space-y-5" aria-labelledby="related-books-heading">
    <h2
      id="related-books-heading"
      class="text-lg font-semibold tracking-tight text-anthracite-900 dark:text-slate-100"
    >
      You might also like
    </h2>

    <div class="grid grid-cols-2 gap-x-5 gap-y-6 sm:grid-cols-3 xl:grid-cols-6">
      {#each displayableBooks as book (book.id)}
        <a href={bookHref(book)} class="group block">
          <div
            class="flex aspect-[2/3] items-center justify-center overflow-hidden rounded-lg bg-linen-100 p-3 transition-all duration-300 group-hover:-translate-y-0.5 group-hover:shadow-book dark:bg-slate-800/60"
          >
            <img
              src={activeCoverUrlsByBookId.get(book.id) ?? resolveCoverUrl(book)}
              alt={`${book.title ?? "Book"} cover`}
              class="max-h-full w-auto rounded-book object-contain transition-transform duration-300 group-hover:scale-[1.03]"
              loading="lazy"
              onerror={(e) => handleCoverError(book.id, e)}
            />
          </div>

          <h3
            class="mt-2.5 line-clamp-2 text-sm font-medium leading-snug text-anthracite-900 transition-colors group-hover:text-canvas-600 dark:text-slate-100 dark:group-hover:text-canvas-400"
          >
            {book.title ?? "Untitled"}
          </h3>
          <p class="mt-0.5 truncate text-xs text-anthracite-500 dark:text-slate-400">
            {authorNames(book)}
          </p>
        </a>
      {/each}
    </div>
  </section>
{:else if loadFailed}
  <section class="space-y-3">
    <h2 class="text-lg font-semibold text-anthracite-900 dark:text-slate-100">
      You might also like
    </h2>
    <p class="text-sm text-anthracite-500 dark:text-slate-400">
      Unable to load related books right now.
    </p>
  </section>
{/if}
