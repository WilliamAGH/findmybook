<script lang="ts">
  import { onMount } from "svelte";
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import { getAffiliateLinks, getBook, getSimilarBooks } from "$lib/services/books";
  import { subscribeToBookCoverUpdates } from "$lib/services/realtime";
  import type { Book } from "$lib/validation/schemas";
  import {
    Star,
    ChevronLeft,
    ShoppingCart,
    Store,
    ShoppingBag,
    Headphones,
    ExternalLink,
    Globe,
    BookOpen,
    Tag,
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
      unsubscribeRealtime = await subscribeToBookCoverUpdates(
        topicIdentifier,
        (coverUrl) => {
          liveCoverUrl = coverUrl;
        },
        (error) => {
          console.error("Realtime cover update error:", error.message);
        },
      );
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
      description: item.descriptionContent?.text ?? item.description,
      coverUrl: item.cover?.preferredUrl ?? item.cover?.s3ImagePath ?? item.cover?.externalImageUrl ?? null,
      fallbackCoverUrl: item.cover?.fallbackUrl ?? "/images/placeholder-book-cover.svg",
    };
  }

  function descriptionHtml(): string {
    if (book?.descriptionContent?.html && book.descriptionContent.html.trim().length > 0) {
      return book.descriptionContent.html;
    }
    return "";
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

  type AffiliateConfig = {
    label: string;
    icon: typeof ShoppingCart;
    bgClass: string;
    hoverClass: string;
  };

  const AFFILIATE_BRAND_CONFIG: Record<string, AffiliateConfig> = {
    amazon: { label: "Amazon", icon: ShoppingCart, bgClass: "bg-canvas-400", hoverClass: "hover:bg-canvas-500" },
    barnesAndNoble: { label: "Barnes & Noble", icon: Store, bgClass: "bg-[#00563F]", hoverClass: "hover:bg-[#004832]" },
    bookshop: { label: "Bookshop.org", icon: ShoppingBag, bgClass: "bg-[#4C32C0]", hoverClass: "hover:bg-[#3D28A0]" },
    audible: { label: "Audible", icon: Headphones, bgClass: "bg-[#FF9900]", hoverClass: "hover:bg-[#E68A00]" },
  };

  function affiliateConfig(key: string): AffiliateConfig {
    return AFFILIATE_BRAND_CONFIG[key] ?? { label: key, icon: ExternalLink, bgClass: "bg-canvas-400", hoverClass: "hover:bg-canvas-500" };
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
    <!-- Back to Search -->
    <a
      href={backToSearchHref()}
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
            src={bookCoverUrl()}
            alt={`${book.title ?? "Book"} cover`}
            class="max-h-[400px] w-auto rounded-book object-contain shadow-book"
            loading="lazy"
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
          <h1 class="text-3xl font-semibold text-anthracite-900 dark:text-slate-100">
            {book.title ?? "Book details"}
          </h1>
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
          {#if descriptionHtml().length > 0}
            <div class="book-description-content text-sm leading-relaxed text-anthracite-700 dark:text-slate-300">
              {@html descriptionHtml()}
            </div>
          {:else if book.description}
            <p class="whitespace-pre-wrap text-sm leading-relaxed text-anthracite-700 dark:text-slate-300">{book.description}</p>
          {/if}

          <!-- Categories -->
          {#if book.categories.length > 0}
            <div class="flex flex-wrap gap-2">
              {#each book.categories as category}
                <span class="inline-flex items-center gap-1 rounded-full bg-linen-100 px-3 py-1 text-xs font-medium text-anthracite-700 dark:bg-slate-700 dark:text-slate-200">
                  <Tag size={12} />
                  {category}
                </span>
              {/each}
            </div>
          {/if}

          <!-- Affiliate Links (Branded Buttons) -->
          {#if sortedAffiliateEntries().length > 0}
            <div class="space-y-3">
              <p class="text-sm font-medium text-anthracite-800 dark:text-slate-200">Buy or Preview</p>
              <div class="flex flex-wrap gap-2">
                {#each sortedAffiliateEntries() as [label, url]}
                  {@const config = affiliateConfig(label)}
                  {@const AffIcon = config.icon}
                  <a
                    href={url}
                    data-no-spa="true"
                    target="_blank"
                    rel="noopener sponsored"
                    class={`inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-white shadow-sm transition-all duration-200 ${config.bgClass} ${config.hoverClass} hover:shadow-canvas focus:outline-none focus:ring-2 focus:ring-canvas-500 focus:ring-offset-2`}
                  >
                    <AffIcon size={16} />
                    {config.label}
                  </a>
                {/each}
              </div>
            </div>
          {/if}
        </div>
      </div>
    </article>

    <!-- Editions -->
    {#if book.editions.length > 0}
      <section class="space-y-3">
        <h2 class="text-xl font-semibold text-anthracite-900 dark:text-slate-100">Editions</h2>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
          {#each book.editions as edition}
            <article class="flex gap-3 rounded-xl border border-linen-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
              {#if edition.coverImageUrl}
                <img
                  src={edition.coverImageUrl}
                  alt="Edition cover"
                  class="h-24 w-auto shrink-0 rounded-md object-contain"
                  loading="lazy"
                />
              {/if}
              <div class="flex min-w-0 flex-col gap-0.5">
                <p class="text-sm font-medium text-anthracite-800 dark:text-slate-100">
                  {edition.identifier ?? edition.googleBooksId ?? "Edition"}
                </p>
                {#if edition.isbn13}
                  <p class="text-xs text-anthracite-600 dark:text-slate-300">ISBN-13: {edition.isbn13}</p>
                {/if}
                {#if edition.publishedDate}
                  <p class="text-xs text-anthracite-500 dark:text-slate-400">
                    Published: {new Date(edition.publishedDate).toLocaleDateString()}
                  </p>
                {/if}
              </div>
            </article>
          {/each}
        </div>
      </section>
    {/if}

    <!-- Similar Books -->
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
</style>
