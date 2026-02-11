<script lang="ts">
  import { onMount } from "svelte";
  import BookDetailCard from "$lib/components/book/BookDetailCard.svelte";
  import BookEditions from "$lib/components/BookEditions.svelte";
  import BookSimilarBooks from "$lib/components/BookSimilarBooks.svelte";
  import { previousSpaPath } from "$lib/router/router";
  import {
    getAffiliateLinks,
    getBook,
    getSimilarBooks,
    persistRenderedCover,
  } from "$lib/services/books";
  import {
    mergePersistedCoverIntoBook,
    releaseCoverRelayCandidate,
    reserveCoverRelayCandidate,
  } from "$lib/services/coverRelayPersistence";
  import { subscribeToBookCoverUpdates } from "$lib/services/realtime";
  import type { Book, BookAiContentSnapshot } from "$lib/validation/schemas";
  import { ChevronLeft } from "@lucide/svelte";

  const attemptedCoverPersistKeys = new Set<string>();

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

  let unsubscribeRealtime: (() => void) | null = null;
  let loadSequence = 0;

  function fallbackIdentifierFromUrl(): string | null {
    const fallbackIdentifier = currentUrl.searchParams.get("bookId") ?? currentUrl.searchParams.get("id");
    if (!fallbackIdentifier) {
      return null;
    }
    const trimmedIdentifier = fallbackIdentifier.trim();
    return trimmedIdentifier.length > 0 ? trimmedIdentifier : null;
  }

  function isHttpNotFoundError(error: unknown): boolean {
    if (!(error instanceof Error)) {
      return false;
    }
    return error.message.startsWith("HTTP 404");
  }

  async function loadBookWithFallback(identifierFromRoute: string): Promise<Book> {
    try {
      return await getBook(identifierFromRoute, "30d");
    } catch (primaryError) {
      const fallbackIdentifier = fallbackIdentifierFromUrl();
      if (!isHttpNotFoundError(primaryError) || !fallbackIdentifier || fallbackIdentifier === identifierFromRoute) {
        throw primaryError;
      }
      console.warn(
        `[BookPage] Book lookup by route identifier '${identifierFromRoute}' returned 404; retrying with fallback '${fallbackIdentifier}'`,
      );
      return getBook(fallbackIdentifier, "30d");
    }
  }

  async function loadPage(): Promise<void> {
    const sequence = ++loadSequence;
    loading = true;
    errorMessage = null;

    if (unsubscribeRealtime) {
      unsubscribeRealtime();
      unsubscribeRealtime = null;
    }

    try {
      const loadedBook = await loadBookWithFallback(identifier);
      if (sequence !== loadSequence) {
        return;
      }

      const relatedIdentifier = loadedBook.id.trim().length > 0
        ? loadedBook.id
        : fallbackIdentifierFromUrl() ?? identifier;

      const [similarResult, linksResult] = await Promise.allSettled([
        getSimilarBooks(relatedIdentifier, 8),
        getAffiliateLinks(relatedIdentifier),
      ]);
      if (sequence !== loadSequence) {
        return;
      }

      book = loadedBook;
      if (similarResult.status === "rejected") {
        similarBooks = [];
        similarBooksFailed = true;
      } else {
        similarBooks = similarResult.value;
        similarBooksFailed = false;
      }
      if (linksResult.status === "rejected") {
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
          (realtimeError) => {
            console.error("Realtime cover update error:", realtimeError.message);
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
    } catch (loadError) {
      if (sequence !== loadSequence) {
        return;
      }
      errorMessage = loadError instanceof Error ? loadError.message : "Unable to load this book";
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
    if (!book) {
      return;
    }
    book = {
      ...book,
      aiContent,
    };
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

  async function handleDetailCoverLoad(): Promise<void> {
    const sequence = loadSequence;
    const currentBook = book;
    const normalizedRenderedUrl = reserveCoverRelayCandidate(
      currentBook,
      detailCoverUrl,
      fallbackCoverImage,
      attemptedCoverPersistKeys,
    );
    if (!currentBook || !normalizedRenderedUrl) {
      return;
    }

    try {
      const persistedCover = await persistRenderedCover({
        identifier: currentBook.id,
        renderedCoverUrl: normalizedRenderedUrl,
        source: currentBook.cover?.source ?? null,
      });

      if (sequence !== loadSequence || !book || book.id !== currentBook.id || detailCoverUrl !== normalizedRenderedUrl) {
        releaseCoverRelayCandidate(currentBook.id, normalizedRenderedUrl, attemptedCoverPersistKeys);
        return;
      }

      liveCoverUrl = persistedCover.storedCoverUrl;
      detailCoverUrl = persistedCover.storedCoverUrl;
      book = mergePersistedCoverIntoBook(currentBook, persistedCover, normalizedRenderedUrl);
      console.info(`[BookPage] Persisted rendered cover for "${currentBook.title}" to ${persistedCover.storageKey}`);
    } catch (persistError) {
      console.warn(
        `[BookPage] Failed to persist rendered cover for "${currentBook.title}" from ${normalizedRenderedUrl}`,
        persistError,
      );
      releaseCoverRelayCandidate(currentBook.id, normalizedRenderedUrl, attemptedCoverPersistKeys);
    }
  }

  function legacySearchFallbackHref(): string {
    const query = currentUrl.searchParams.get("query")?.trim() ?? "";
    const rawPopularWindow = currentUrl.searchParams.get("popularWindow");
    const popularWindow = rawPopularWindow === "30d" || rawPopularWindow === "90d" || rawPopularWindow === "all"
      ? rawPopularWindow
      : null;

    let url: URL;
    if (query.length > 0) {
      url = new URL("/search", window.location.origin);
      url.searchParams.set("query", query);
    } else if (popularWindow) {
      url = new URL("/explore", window.location.origin);
      url.searchParams.set("popularWindow", popularWindow);
    } else {
      return "/";
    }

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
    <a
      href={backHref()}
      onclick={goBackToPreviousRoute}
      class="inline-flex w-fit items-center gap-1.5 rounded-lg border border-linen-300 px-3 py-1.5 text-sm text-anthracite-700 transition hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"
    >
      <ChevronLeft size={16} />
      Back to Results
    </a>

    <BookDetailCard
      {book}
      {detailCoverUrl}
      {affiliateLinks}
      {affiliateLinksFailed}
      onAiContentUpdate={handleAiContentUpdate}
      onCoverLoad={handleDetailCoverLoad}
      onCoverError={handleDetailCoverError}
    />

    <BookEditions editions={book.editions} />

    <BookSimilarBooks books={similarBooks} loadFailed={similarBooksFailed} />
  {:else}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Book not found.</p>
  {/if}
</section>
