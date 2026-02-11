<script lang="ts">
  /**
   * Related-book section with modal expansion for the book detail page.
   * Maps domain Book objects to BookCardDisplay for consistent rendering.
   */
  import BookCard, { type BookCardDisplay } from "$lib/components/BookCard.svelte";
  import { Sparkles, X } from "@lucide/svelte";
  import type { Book } from "$lib/validation/schemas";

  let { books, loadFailed = false }: { books: Book[]; loadFailed?: boolean } = $props();
  let isModalOpen = $state(false);

  const PREVIEW_COUNT = 3;
  const MODAL_HEADING_ID = "related-books-modal-heading";

  let previewBooks = $derived(books.slice(0, PREVIEW_COUNT));

  function toCard(item: Book): BookCardDisplay {
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

  function openModal(): void {
    isModalOpen = true;
  }

  function closeModal(): void {
    isModalOpen = false;
  }

  function handleBackdropClick(event: MouseEvent): void {
    const target = event.target as HTMLElement | null;
    if (target?.dataset.relatedBackdrop === "true") {
      closeModal();
    }
  }

  function handleWindowKeydown(event: KeyboardEvent): void {
    if (isModalOpen && event.key === "Escape") {
      closeModal();
    }
  }

  $effect(() => {
    if (!isModalOpen) {
      return;
    }
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  });
</script>

<svelte:window onkeydown={handleWindowKeydown} />

{#if books.length > 0}
  <section class="space-y-3" aria-labelledby="related-books-heading">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 id="related-books-heading" class="inline-flex items-center gap-2 text-xl font-semibold text-anthracite-900 dark:text-slate-100">
        <Sparkles size={20} class="text-canvas-500 dark:text-canvas-400" />
        You might also like
      </h2>
      <button
        type="button"
        class="inline-flex items-center rounded-lg border border-linen-300 bg-white px-3 py-1.5 text-sm font-medium text-anthracite-800 transition hover:bg-linen-100 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800"
        aria-haspopup="dialog"
        onclick={openModal}
      >
        View related books ({books.length})
      </button>
    </div>
    <p class="text-sm text-anthracite-600 dark:text-slate-300">Suggested titles based on related metadata and reading signals.</p>
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {#each previewBooks as item (item.id)}
        <BookCard book={toCard(item)} href={`/book/${encodeURIComponent(item.slug ?? item.id)}`} />
      {/each}
    </div>
  </section>

  {#if isModalOpen}
    <div
      class="fixed inset-0 z-50 flex items-center justify-center bg-anthracite-950/60 p-4 backdrop-blur-[2px]"
      data-related-backdrop="true"
      onclick={handleBackdropClick}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={MODAL_HEADING_ID}
        tabindex="-1"
        class="flex max-h-[88vh] w-full max-w-6xl flex-col overflow-hidden rounded-xl border border-linen-300 bg-white shadow-xl dark:border-slate-700 dark:bg-slate-900"
      >
        <div class="flex items-center justify-between border-b border-linen-200 px-4 py-3 dark:border-slate-700">
          <h3 id={MODAL_HEADING_ID} class="text-lg font-semibold text-anthracite-900 dark:text-slate-100">You Might Also Like</h3>
          <button
            type="button"
            onclick={closeModal}
            class="rounded-md p-1.5 text-anthracite-500 transition hover:bg-linen-100 hover:text-anthracite-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-100"
            aria-label="Close related books modal"
          >
            <X size={18} />
          </button>
        </div>
        <div class="overflow-y-auto px-4 py-4 md:px-6 md:py-5">
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {#each books as item (item.id)}
              <BookCard book={toCard(item)} href={`/book/${encodeURIComponent(item.slug ?? item.id)}`} />
            {/each}
          </div>
        </div>
      </div>
    </div>
  {/if}
{:else if loadFailed}
  <section class="space-y-3">
    <h2 class="text-xl font-semibold text-anthracite-900 dark:text-slate-100">You might also like</h2>
    <p class="text-sm text-anthracite-500 dark:text-slate-400">Unable to load related books right now.</p>
  </section>
{/if}
