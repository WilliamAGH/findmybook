<script lang="ts">
  import { onMount } from "svelte";
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import { getAffiliateLinks, getBook, getSimilarBooks } from "$lib/services/books";
  import { subscribeToBookCoverUpdates } from "$lib/services/realtime";
  import type { Book } from "$lib/validation/schemas";

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
  let affiliateLinks = $state<Record<string, string>>({});
  let liveCoverUrl = $state<string | null>(null);

  let unsubscribeRealtime: (() => void) | null = null;

  async function loadPage(): Promise<void> {
    loading = true;
    errorMessage = null;

    try {
      const loadedBook = await getBook(identifier);
      const [similarResult, linksResult] = await Promise.allSettled([
        getSimilarBooks(identifier, 6),
        getAffiliateLinks(identifier),
      ]);

      book = loadedBook;
      similarBooks = similarResult.status === "fulfilled" ? similarResult.value : [];
      affiliateLinks = linksResult.status === "fulfilled" ? linksResult.value : {};
      liveCoverUrl = null;

      if (unsubscribeRealtime) {
        unsubscribeRealtime();
        unsubscribeRealtime = null;
      }

      const topicIdentifier = loadedBook.id;
      unsubscribeRealtime = await subscribeToBookCoverUpdates(topicIdentifier, (coverUrl) => {
        liveCoverUrl = coverUrl;
      });
    } catch (error) {
      errorMessage = error instanceof Error ? error.message : "Unable to load this book";
      book = null;
      similarBooks = [];
      affiliateLinks = {};
    } finally {
      loading = false;
    }
  }

  function bookCoverUrl(): string {
    if (liveCoverUrl && liveCoverUrl.trim().length > 0) {
      return liveCoverUrl;
    }

    if (book?.cover?.preferredUrl && book.cover.preferredUrl.trim().length > 0) {
      return book.cover.preferredUrl;
    }

    if (book?.cover?.fallbackUrl && book.cover.fallbackUrl.trim().length > 0) {
      return book.cover.fallbackUrl;
    }

    return "/images/placeholder-book-cover.svg";
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

  function similarCard(item: Book): BookCardDisplay {
    return {
      id: item.id,
      slug: item.slug ?? item.id,
      title: item.title ?? "Untitled",
      authors: item.authors.map((author) => author.name),
      description: item.description,
      coverUrl: item.cover?.preferredUrl ?? item.cover?.s3ImagePath ?? item.cover?.externalImageUrl ?? null,
      fallbackCoverUrl: item.cover?.fallbackUrl ?? "/images/placeholder-book-cover.svg",
    };
  }

  function backToSearchHref(): string {
    const query = currentUrl.searchParams.get("query");
    if (!query) {
      return "/search";
    }

    const url = new URL("/search", window.location.origin);
    url.searchParams.set("query", query);

    const page = currentUrl.searchParams.get("page");
    const sort = currentUrl.searchParams.get("sort");
    const view = currentUrl.searchParams.get("view");
    const year = currentUrl.searchParams.get("year");
    if (page) {
      url.searchParams.set("page", page);
    }
    if (sort) {
      url.searchParams.set("sort", sort);
    }
    if (view) {
      url.searchParams.set("view", view);
    }
    if (year) {
      url.searchParams.set("year", year);
    }

    return `${url.pathname}${url.search}`;
  }

  function sortedAffiliateEntries(): Array<[string, string]> {
    return Object.entries(affiliateLinks).sort(([left], [right]) => left.localeCompare(right));
  }

  $effect(() => {
    if (identifier) {
      void loadPage();
    }
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
    <a href={backToSearchHref()} class="w-fit rounded-lg border border-linen-300 px-3 py-1.5 text-sm text-anthracite-700 transition hover:bg-linen-100 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800">
      Back to search
    </a>

    <article class="grid gap-6 rounded-xl border border-linen-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800 md:grid-cols-[320px_1fr]">
      <img src={bookCoverUrl()} alt={`${book.title ?? "Book"} cover`} class="h-[440px] w-full rounded-xl object-cover" loading="lazy" />
      <div class="space-y-4">
        <h1 class="text-3xl font-semibold text-anthracite-900 dark:text-slate-100">{book.title ?? "Book details"}</h1>
        <p class="text-base text-anthracite-700 dark:text-slate-300">{authorNames()}</p>

        <dl class="grid gap-2 text-sm text-anthracite-700 dark:text-slate-300 sm:grid-cols-2">
          <div>
            <dt class="font-medium text-anthracite-800 dark:text-slate-200">Published</dt>
            <dd>{publishedDateText()}</dd>
          </div>
          <div>
            <dt class="font-medium text-anthracite-800 dark:text-slate-200">Publisher</dt>
            <dd>{book.publication?.publisher ?? "Unknown"}</dd>
          </div>
          <div>
            <dt class="font-medium text-anthracite-800 dark:text-slate-200">Language</dt>
            <dd>{book.publication?.language ?? "Unknown"}</dd>
          </div>
          <div>
            <dt class="font-medium text-anthracite-800 dark:text-slate-200">Pages</dt>
            <dd>{book.publication?.pageCount ?? "Unknown"}</dd>
          </div>
        </dl>

        {#if book.description}
          <p class="whitespace-pre-wrap text-sm leading-6 text-anthracite-700 dark:text-slate-300">{book.description}</p>
        {/if}

        {#if book.categories.length > 0}
          <div class="flex flex-wrap gap-2">
            {#each book.categories as category}
              <span class="rounded-full bg-linen-100 px-3 py-1 text-xs font-medium text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">{category}</span>
            {/each}
          </div>
        {/if}

        {#if sortedAffiliateEntries().length > 0}
          <div class="space-y-2">
            <p class="text-sm font-medium text-anthracite-800 dark:text-slate-200">Affiliate links</p>
            <div class="flex flex-wrap gap-2">
              {#each sortedAffiliateEntries() as [label, url]}
                <a href={url} data-no-spa="true" target="_blank" rel="noreferrer" class="rounded-lg bg-canvas-500 px-3 py-1.5 text-sm text-white transition hover:bg-canvas-600">{label}</a>
              {/each}
            </div>
          </div>
        {/if}
      </div>
    </article>

    {#if book.editions.length > 0}
      <section class="space-y-3">
        <h2 class="text-xl font-semibold text-anthracite-900 dark:text-slate-100">Editions</h2>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
          {#each book.editions as edition}
            <article class="rounded-xl border border-linen-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
              <p class="text-sm font-medium text-anthracite-800 dark:text-slate-100">{edition.identifier ?? edition.googleBooksId ?? "Edition"}</p>
              <p class="text-xs text-anthracite-600 dark:text-slate-300">ISBN-13: {edition.isbn13 ?? "N/A"}</p>
            </article>
          {/each}
        </div>
      </section>
    {/if}

    {#if similarBooks.length > 0}
      <section class="space-y-3">
        <h2 class="text-xl font-semibold text-anthracite-900 dark:text-slate-100">Similar books</h2>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {#each similarBooks as item (item.id)}
            <BookCard book={similarCard(item)} href={`/book/${encodeURIComponent(item.slug ?? item.id)}`} />
          {/each}
        </div>
      </section>
    {/if}
  {:else}
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Book not found.</p>
  {/if}
</section>
