<script lang="ts">
  import DOMPurify from "dompurify";
  import { ChevronDown } from "@lucide/svelte";

  const DESCRIPTION_MAX_LINES = 13;
  const DESCRIPTION_FALLBACK_HEIGHT_PX = 288; // 18rem — roughly 12-13 lines at text-sm leading-relaxed

  let {
    bookId,
    descriptionHtml,
    descriptionText,
  }: {
    bookId: string;
    descriptionHtml: string | null | undefined;
    descriptionText: string | null | undefined;
  } = $props();

  let descriptionContainer = $state<HTMLElement | null>(null);
  let descriptionExpanded = $state(false);
  let descriptionMeasured = $state(false);
  let descriptionOverflows = $state(false);
  let descriptionResizeObserver: ResizeObserver | null = null;
  let previousBookId = $state<string | null>(null);

  let sanitizedDescriptionHtml = $derived(
    descriptionHtml && descriptionHtml.trim().length > 0
      ? DOMPurify.sanitize(descriptionHtml)
      : "",
  );

  let hasDescription = $derived(
    sanitizedDescriptionHtml.length > 0
      || (descriptionText?.trim().length ?? 0) > 0,
  );

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
      descriptionOverflows = descriptionContainer.scrollHeight > maxHeightPx + 1;
      descriptionMeasured = true;
    });
  }

  $effect(() => {
    const currentId = bookId ?? null;
    if (currentId && currentId !== previousBookId) {
      previousBookId = currentId;
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

    const currentHtml = sanitizedDescriptionHtml;
    const currentText = descriptionText;
    if (currentHtml || (currentText?.trim().length ?? 0) > 0) {
      measureDescriptionOverflow();
    }

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
</script>

{#if hasDescription}
  <section
    class="rounded-xl border border-linen-200 bg-linen-50/60 dark:border-slate-700 dark:bg-slate-900/60"
    aria-labelledby="book-publisher-heading"
  >
    <div class="flex items-center justify-between gap-3 px-4 py-3">
      <h2 id="book-publisher-heading" class="text-sm font-medium text-anthracite-700 dark:text-slate-300">
        From the Publisher
      </h2>
      <button
        type="button"
        class="inline-flex items-center gap-1.5 text-xs font-medium text-anthracite-600 transition hover:text-anthracite-900 disabled:cursor-default disabled:opacity-60 dark:text-slate-400 dark:hover:text-slate-100"
        onclick={() => descriptionExpanded = !descriptionExpanded}
        aria-expanded={!descriptionCollapsed}
        disabled={descriptionMeasured && !descriptionOverflows}
        aria-label={descriptionCollapsed ? "Expand publisher description" : "Collapse publisher description"}
      >
        <ChevronDown
          size={14}
          class="shrink-0 transition-transform duration-200 {descriptionCollapsed ? '-rotate-90' : ''}"
        />
        {descriptionCollapsed ? "Expand" : "Collapse"}
      </button>
    </div>
    <div class="border-t border-linen-200 px-4 pb-4 pt-3 dark:border-slate-700">
      <div class="relative">
        <div
          bind:this={descriptionContainer}
          style:--description-max-lines={DESCRIPTION_MAX_LINES}
          class="book-description-expandable break-words text-sm leading-relaxed text-anthracite-700 dark:text-slate-300 overflow-hidden transition-[max-height] duration-300 ease-in-out"
          class:book-description-content={sanitizedDescriptionHtml.length > 0}
          class:whitespace-pre-wrap={sanitizedDescriptionHtml.length === 0}
          class:book-description-collapsed={descriptionCollapsed}
          class:book-description-expanded={!descriptionCollapsed}
        >
          {#if sanitizedDescriptionHtml.length > 0}
            {@html sanitizedDescriptionHtml}
          {:else}
            {descriptionText}
          {/if}
        </div>
        {#if descriptionMeasured && descriptionOverflows && !descriptionExpanded}
          <div class="pointer-events-none absolute inset-x-0 bottom-0 h-12 bg-linear-to-t from-linen-50 via-linen-50/70 to-transparent dark:from-slate-900 dark:via-slate-900/70"></div>
        {/if}
      </div>
      {#if descriptionMeasured && descriptionOverflows}
        <button
          type="button"
          onclick={() => descriptionExpanded = !descriptionExpanded}
          class="mx-auto mt-1 flex items-center justify-center rounded-md p-1 text-anthracite-400 transition hover:text-anthracite-700 dark:text-slate-500 dark:hover:text-slate-200"
          aria-label={descriptionExpanded ? "Collapse description" : "Expand description"}
        >
          <ChevronDown
            size={18}
            class="transition-transform duration-200 {descriptionExpanded ? 'rotate-180' : ''}"
          />
        </button>
      {/if}
    </div>
  </section>
{/if}

<style>
  .book-description-expandable.book-description-collapsed {
    max-height: calc(var(--description-max-lines) * 1lh);
  }

  .book-description-expandable.book-description-expanded {
    max-height: 9999px;
  }

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
