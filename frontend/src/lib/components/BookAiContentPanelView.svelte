<script lang="ts">
  import { ChevronDown, RefreshCw } from "@lucide/svelte";
  import type { Book } from "$lib/validation/schemas";

  interface Props {
    book: Book;
    collapsed: boolean;
    aiServiceAvailable: boolean;
    aiLoading: boolean;
    aiErrorMessage: string | null;
    aiQueueMessage: string | null;
    aiLoadingMessage: string;
    aiAutoTriggerDeferred: boolean;
    willAutoTrigger: boolean;
    onToggleCollapsed: () => void;
    onRefresh: () => void;
  }

  let {
    book,
    collapsed,
    aiServiceAvailable,
    aiLoading,
    aiErrorMessage,
    aiQueueMessage,
    aiLoadingMessage,
    aiAutoTriggerDeferred,
    willAutoTrigger,
    onToggleCollapsed,
    onRefresh,
  }: Props = $props();
</script>

<section class="rounded-xl border border-linen-200 bg-linen-50/60 dark:border-slate-700 dark:bg-slate-900/60">
  <div class="flex items-center justify-between gap-3 px-4 py-3">
    <button
      type="button"
      class="inline-flex items-center gap-1.5 text-sm font-medium text-anthracite-700 transition hover:text-anthracite-900 dark:text-slate-300 dark:hover:text-slate-100"
      onclick={onToggleCollapsed}
      aria-expanded={!collapsed}
    >
      <ChevronDown
        size={14}
        class="shrink-0 transition-transform duration-200 {collapsed ? '-rotate-90' : ''}"
      />
      Reader's Guide
    </button>
    {#if aiServiceAvailable}
      <button
        type="button"
        class="inline-flex items-center justify-center rounded-md p-1 text-anthracite-500 transition hover:bg-linen-100 hover:text-anthracite-700 disabled:cursor-not-allowed disabled:opacity-50 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200"
        disabled={aiLoading}
        onclick={onRefresh}
        title="Refresh"
        aria-label="Refresh"
      >
        <RefreshCw size={14} class={aiLoading ? "animate-spin" : ""} />
      </button>
    {/if}
  </div>

  {#if !collapsed}
    <div class="border-t border-linen-200 px-4 pb-4 pt-3 dark:border-slate-700">
      {#if book.aiContent}
        {#if aiErrorMessage}
          <p class="mb-2 text-xs text-red-700 dark:text-red-300">{aiErrorMessage}</p>
        {/if}
        <p class="text-sm leading-relaxed text-anthracite-800 dark:text-slate-200">
          {book.aiContent.summary}
        </p>

        {#if book.aiContent.takeaways && book.aiContent.takeaways.length > 0}
          <div class="mt-3">
            <h3 class="mb-1.5 text-xs font-semibold uppercase tracking-wide text-anthracite-500 dark:text-slate-400">
              Key Points
            </h3>
            <ul class="space-y-1 pl-4">
              {#each book.aiContent.takeaways as point}
                <li class="list-disc text-sm text-anthracite-700 dark:text-slate-300">{point}</li>
              {/each}
            </ul>
          </div>
        {/if}

        {#if book.aiContent.readerFit}
          <div class="mt-3">
            <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-anthracite-500 dark:text-slate-400">
              Audience
            </h3>
            <p class="text-sm text-anthracite-700 dark:text-slate-300">{book.aiContent.readerFit}</p>
          </div>
        {/if}

        {#if book.aiContent.context}
          <div class="mt-3">
            <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-anthracite-500 dark:text-slate-400">
              Context
            </h3>
            <p class="text-sm text-anthracite-700 dark:text-slate-300">{book.aiContent.context}</p>
          </div>
        {/if}

        {#if book.aiContent.keyThemes.length > 0}
          <div class="mt-3 flex flex-wrap gap-1.5">
            {#each book.aiContent.keyThemes as theme}
              <span class="rounded-full border border-linen-300 px-2 py-0.5 text-[11px] text-anthracite-700 dark:border-slate-600 dark:text-slate-300">
                {theme}
              </span>
            {/each}
          </div>
        {/if}
      {:else if aiLoading}
        <div class="flex items-center gap-2">
          <span
            aria-hidden="true"
            class="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent opacity-50"
          ></span>
          <p class="text-sm text-anthracite-600 dark:text-slate-400">{aiLoadingMessage}</p>
        </div>
        {#if aiQueueMessage}
          <p class="mt-1.5 text-xs text-anthracite-500 dark:text-slate-400">{aiQueueMessage}</p>
        {/if}
      {:else if aiAutoTriggerDeferred}
        <p class="text-xs text-anthracite-500 dark:text-slate-400">
          Generation paused while the queue is busy.
        </p>
      {:else if aiErrorMessage}
        <p class="text-xs text-red-700 dark:text-red-300">{aiErrorMessage}</p>
      {:else if willAutoTrigger}
        <div class="flex items-center gap-2">
          <span
            aria-hidden="true"
            class="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent opacity-50"
          ></span>
          <p class="text-sm text-anthracite-600 dark:text-slate-400">Loading AI content...</p>
        </div>
      {/if}
    </div>
  {/if}
</section>
