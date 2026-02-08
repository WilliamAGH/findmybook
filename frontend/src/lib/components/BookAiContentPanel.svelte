<script lang="ts">
  import { onMount } from "svelte";
  import { ChevronDown, RefreshCw } from "@lucide/svelte";
  import { streamBookAiContent } from "$lib/services/bookAiContentStream";
  import { getBookAiContentQueueStats } from "$lib/services/books";
  import type { Book, BookAiContentModelStreamUpdate, BookAiContentQueueUpdate, BookAiContentSnapshot } from "$lib/validation/schemas";

  interface Props {
    identifier: string;
    book: Book;
    onAiContentUpdate: (aiContent: BookAiContentSnapshot) => void;
  }

  let { identifier, book, onAiContentUpdate }: Props = $props();

  const AI_AUTO_TRIGGER_QUEUE_THRESHOLD = 5;
  const COLLAPSE_STORAGE_KEY = "findmybook:ai-collapsed";

  let aiLoading = $state(false);
  let aiErrorMessage = $state<string | null>(null);
  let aiQueueMessage = $state<string | null>(null);
  let aiLoadingMessage = $state("Generating AI content...");
  let aiAutoTriggerDeferred = $state(false);
  let collapsed = $state(false);
  let aiAbortController: AbortController | null = null;

  function readCollapseState(): boolean {
    try {
      return localStorage.getItem(COLLAPSE_STORAGE_KEY) === "true";
    } catch (error) {
      console.warn("localStorage read failed for collapse state:", error);
      return false;
    }
  }

  function writeCollapseState(value: boolean): void {
    try {
      localStorage.setItem(COLLAPSE_STORAGE_KEY, String(value));
    } catch (error) {
      console.warn("localStorage write failed for collapse state:", error);
    }
  }

  function toggleCollapsed(): void {
    collapsed = !collapsed;
    writeCollapseState(collapsed);
  }

  function handleAiQueueUpdate(update: BookAiContentQueueUpdate): void {
    if (update.event === "queued" || update.event === "queue") {
      aiQueueMessage = update.position
        ? `Queued (position ${update.position})`
        : "Queued for generation";
      return;
    }
    aiQueueMessage = null;
  }

  function handleAiStreamEvent(event: BookAiContentModelStreamUpdate): void {
    if (event.event === "message_start") {
      aiLoadingMessage = "Generating AI content...";
      return;
    }
    if (event.event === "message_delta") {
      return;
    }
    if (event.event === "message_done") {
      /* noop — result arrives via the resolved promise */
    }
  }

  async function hasQueueCapacity(refresh: boolean): Promise<boolean> {
    try {
      const queueStats = await getBookAiContentQueueStats();
      if (queueStats.pending > AI_AUTO_TRIGGER_QUEUE_THRESHOLD) {
        aiQueueMessage = `Queue busy (${queueStats.pending} waiting)`;
        aiAutoTriggerDeferred = !refresh;
        if (refresh) {
          aiErrorMessage = "Queue is busy right now. Try again shortly.";
        }
        return false;
      }
      return true;
    } catch (queueError) {
      const message = queueError instanceof Error
        ? queueError.message
        : "Unable to check queue status";
      aiErrorMessage = message;
      aiAutoTriggerDeferred = false;
      return false;
    }
  }

  async function triggerAiGeneration(refresh: boolean): Promise<void> {
    if (!identifier || aiLoading) {
      return;
    }

    aiErrorMessage = null;
    aiQueueMessage = null;
    aiAutoTriggerDeferred = false;
    if (!(await hasQueueCapacity(refresh))) {
      return;
    }

    aiAbortController?.abort();
    aiAbortController = new AbortController();
    aiLoading = true;
    aiErrorMessage = null;
    aiQueueMessage = null;
    aiLoadingMessage = refresh
      ? "Refreshing AI content..."
      : "Generating AI content...";

    try {
      const result = await streamBookAiContent(identifier, {
        refresh,
        signal: aiAbortController.signal,
        onQueueUpdate: handleAiQueueUpdate,
        onStreamEvent: handleAiStreamEvent,
      });

      onAiContentUpdate(result.aiContent);

      aiQueueMessage = null;
      aiErrorMessage = null;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        return;
      }
      aiErrorMessage = error instanceof Error ? error.message : "Unable to generate AI content";
      console.error("Book AI content generation failed:", error);
    } finally {
      aiLoading = false;
    }
  }

  onMount(() => {
    collapsed = readCollapseState();

    const autoTriggerDelay = setTimeout(() => {
      if (!book.aiContent && !aiLoading && !aiAutoTriggerDeferred) {
        void triggerAiGeneration(false);
      }
    }, 0);

    return () => {
      clearTimeout(autoTriggerDelay);
      aiAbortController?.abort();
    };
  });
</script>

<section class="rounded-xl border border-linen-200 bg-linen-50/60 dark:border-slate-700 dark:bg-slate-900/60">
  <!-- Header toggle row -->
  <div class="flex items-center justify-between gap-3 px-4 py-3">
    <button
      type="button"
      class="inline-flex items-center gap-1.5 text-sm font-medium text-anthracite-700 transition hover:text-anthracite-900 dark:text-slate-300 dark:hover:text-slate-100"
      onclick={toggleCollapsed}
      aria-expanded={!collapsed}
    >
      <ChevronDown
        size={14}
        class="shrink-0 transition-transform duration-200 {collapsed ? '-rotate-90' : ''}"
      />
      About This Book
    </button>
    <button
      type="button"
      class="inline-flex items-center justify-center rounded-md p-1 text-anthracite-500 transition hover:bg-linen-100 hover:text-anthracite-700 disabled:cursor-not-allowed disabled:opacity-50 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200"
      disabled={aiLoading}
      onclick={() => triggerAiGeneration(true)}
      title="Refresh"
      aria-label="Refresh"
    >
      <RefreshCw size={14} class={aiLoading ? "animate-spin" : ""} />
    </button>
  </div>

  <!-- Collapsible body -->
  {#if !collapsed}
    <div class="border-t border-linen-200 px-4 pb-4 pt-3 dark:border-slate-700">
      {#if book.aiContent}
        <!-- Summary -->
        <p class="text-sm leading-relaxed text-anthracite-800 dark:text-slate-200">
          {book.aiContent.summary}
        </p>

        <!-- Key Points -->
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

        <!-- Audience -->
        <div class="mt-3">
          <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-anthracite-500 dark:text-slate-400">
            Audience
          </h3>
          <p class="text-sm text-anthracite-700 dark:text-slate-300">{book.aiContent.readerFit}</p>
        </div>

        <!-- Context -->
        {#if book.aiContent.context}
          <div class="mt-3">
            <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-anthracite-500 dark:text-slate-400">
              Context
            </h3>
            <p class="text-sm text-anthracite-700 dark:text-slate-300">{book.aiContent.context}</p>
          </div>
        {/if}

        <!-- Topic pills -->
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
          <span class="ai-spinner"></span>
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
      {:else}
        <p class="text-xs text-anthracite-500 dark:text-slate-400">
          No AI content available yet.
        </p>
      {/if}
    </div>
  {/if}
</section>

<style>
  .ai-spinner {
    display: inline-block;
    width: 14px;
    height: 14px;
    border: 2px solid currentColor;
    border-right-color: transparent;
    border-radius: 50%;
    animation: ai-spin 0.6s linear infinite;
    opacity: 0.5;
  }
  @keyframes ai-spin {
    to { transform: rotate(360deg); }
  }
</style>
