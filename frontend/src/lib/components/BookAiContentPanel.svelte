<script lang="ts">
  import { onMount, untrack } from "svelte";
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
  let aiServiceAvailable = $state(true);
  let collapsed = $state(false);
  let aiAbortController: AbortController | null = null;
  let activeRequestToken: symbol | null = null;
  let lastAutoTriggerIdentifier = $state<string | null>(null);

  /** True when the auto-trigger $effect is about to fire but hasn't yet set aiLoading. */
  let willAutoTrigger = $derived(
    !!identifier
      && !book?.aiContent
      && !aiAutoTriggerDeferred
      && lastAutoTriggerIdentifier !== identifier
  );

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
      aiQueueMessage = update.position != null
        ? `Queued (position ${update.position}, ${update.running}/${update.maxParallel} running)`
        : "Queued for generation";
      aiLoadingMessage = "Waiting in queue...";
      return;
    }
    aiQueueMessage = null;
    aiLoadingMessage = "Generating AI content...";
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
      if (!queueStats.available) {
        aiServiceAvailable = false;
        if (!book?.aiContent) {
          return false;
        }
        aiErrorMessage = refresh ? "AI content service is not available right now." : null;
        return false;
      }
      aiServiceAvailable = true;
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
      console.error("[BookAiContentPanel] Queue stats failed:", queueError);
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

    const requestIdentifier = identifier;
    const requestToken = Symbol("book-ai-content-request");
    activeRequestToken = requestToken;
    aiLoading = true;
    aiErrorMessage = null;
    aiQueueMessage = null;
    aiAutoTriggerDeferred = false;
    aiLoadingMessage = refresh
      ? "Refreshing AI content..."
      : "Generating AI content...";

    const queueHasCapacity = await hasQueueCapacity(refresh);
    if (activeRequestToken !== requestToken || identifier !== requestIdentifier) {
      return;
    }
    if (!queueHasCapacity) {
      activeRequestToken = null;
      aiLoading = false;
      return;
    }

    aiAbortController?.abort();
    aiAbortController = new AbortController();
    aiErrorMessage = null;
    aiQueueMessage = null;

    try {
      const result = await streamBookAiContent(requestIdentifier, {
        refresh,
        signal: aiAbortController.signal,
        onQueueUpdate: handleAiQueueUpdate,
        onStreamEvent: handleAiStreamEvent,
      });

      if (activeRequestToken !== requestToken || identifier !== requestIdentifier) {
        return;
      }
      onAiContentUpdate(result.aiContent);

      aiQueueMessage = null;
      aiErrorMessage = null;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        return;
      }
      if (activeRequestToken !== requestToken) {
        return;
      }
      const message = error instanceof Error ? error.message : "Unable to generate AI content";
      if (message.toLowerCase().includes("not configured") || message.toLowerCase().includes("not available")) {
        aiServiceAvailable = false;
      }
      aiErrorMessage = message;
      console.error("Book AI content generation failed:", error);
    } finally {
      if (activeRequestToken === requestToken) {
        activeRequestToken = null;
        aiLoading = false;
      }
    }
  }

  onMount(() => {
    collapsed = readCollapseState();

    return () => {
      aiAbortController?.abort();
    };
  });

  $effect(() => {
    // untrack lastAutoTriggerIdentifier so writing it doesn't create a
    // tracked dependency that would trigger a re-run whose cleanup
    // clearTimeout kills the pending auto-trigger before it can fire.
    if (!identifier || untrack(() => lastAutoTriggerIdentifier) === identifier) {
      return;
    }

    aiAbortController?.abort();
    aiAbortController = null;
    activeRequestToken = null;
    aiLoading = false;
    aiErrorMessage = null;
    aiQueueMessage = null;
    aiAutoTriggerDeferred = false;
    aiServiceAvailable = true;
    aiLoadingMessage = "Generating AI content...";

    const scheduledIdentifier = identifier;

    const autoTriggerDelay = setTimeout(() => {
      // Mark this identifier as processed inside the callback so
      // willAutoTrigger stays true (showing the spinner) until the
      // trigger actually fires — avoiding a flash of empty content.
      lastAutoTriggerIdentifier = scheduledIdentifier;
      if (identifier !== scheduledIdentifier) {
        return;
      }
      if (!book?.aiContent && !aiLoading && !aiAutoTriggerDeferred) {
        void triggerAiGeneration(false);
      }
    }, 0);

    return () => {
      clearTimeout(autoTriggerDelay);
    };
  });
</script>

{#if aiServiceAvailable || book.aiContent}
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
      Reader's Guide
    </button>
    {#if aiServiceAvailable}
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
    {/if}
  </div>

  <!-- Collapsible body -->
  {#if !collapsed}
    <div class="border-t border-linen-200 px-4 pb-4 pt-3 dark:border-slate-700">
      {#if book.aiContent}
        {#if aiErrorMessage}
          <p class="mb-2 text-xs text-red-700 dark:text-red-300">{aiErrorMessage}</p>
        {/if}
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
        {#if book.aiContent.readerFit}
        <div class="mt-3">
          <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-anthracite-500 dark:text-slate-400">
            Audience
          </h3>
          <p class="text-sm text-anthracite-700 dark:text-slate-300">{book.aiContent.readerFit}</p>
        </div>
        {/if}

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
{/if}
