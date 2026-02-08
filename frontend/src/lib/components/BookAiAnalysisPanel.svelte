<script lang="ts">
  import { onMount } from "svelte";
  import { Sparkles, RefreshCw } from "@lucide/svelte";
  import { streamBookAiAnalysis } from "$lib/services/bookAiStream";
  import { getBookAiQueueStats } from "$lib/services/books";
  import type { Book, BookAiModelStreamUpdate, BookAiQueueUpdate, BookAiSnapshot } from "$lib/validation/schemas";

  interface Props {
    identifier: string;
    book: Book;
    onAnalysisUpdate: (analysis: BookAiSnapshot) => void;
  }

  let { identifier, book, onAnalysisUpdate }: Props = $props();

  const AI_AUTO_TRIGGER_QUEUE_THRESHOLD = 5;

  let aiLoading = $state(false);
  let aiErrorMessage = $state<string | null>(null);
  let aiQueueMessage = $state<string | null>(null);
  let aiStreamingText = $state("");
  let aiLoadingMessage = $state("Generating reader-fit snapshot...");
  let aiAutoTriggerDeferred = $state(false);
  let aiAbortController: AbortController | null = null;

  function formatAiGeneratedAt(value: string | null | undefined): string {
    if (!value) {
      return "Unknown";
    }
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
      return value;
    }
    return parsed.toLocaleString();
  }

  function handleAiQueueUpdate(update: BookAiQueueUpdate): void {
    if (update.event === "queued" || update.event === "queue") {
      aiQueueMessage = update.position
        ? `Queued for AI generation (position ${update.position})`
        : "Queued for AI generation";
      return;
    }
    aiQueueMessage = null;
  }

  function handleAiStreamEvent(event: BookAiModelStreamUpdate): void {
    if (event.event === "message_start") {
      aiStreamingText = "";
      aiLoadingMessage = "AI is generating a fresh snapshot...";
      return;
    }
    if (event.event === "message_delta") {
      aiStreamingText += event.data.delta;
      return;
    }
    if (event.event === "message_done") {
      aiStreamingText = event.data.message;
    }
  }

  async function hasQueueCapacity(refresh: boolean): Promise<boolean> {
    try {
      const queueStats = await getBookAiQueueStats();
      if (queueStats.pending > AI_AUTO_TRIGGER_QUEUE_THRESHOLD) {
        aiQueueMessage = `Queue busy (${queueStats.pending} waiting)`;
        aiAutoTriggerDeferred = !refresh;
        if (refresh) {
          aiErrorMessage = "AI queue is busy right now. Please try refresh again shortly.";
        }
        return false;
      }
      return true;
    } catch (queueError) {
      const message = queueError instanceof Error
        ? queueError.message
        : "Unable to check AI queue status";
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
    aiStreamingText = "";
    aiLoadingMessage = refresh
      ? "Refreshing reader-fit snapshot..."
      : "Generating reader-fit snapshot...";

    try {
      const result = await streamBookAiAnalysis(identifier, {
        refresh,
        signal: aiAbortController.signal,
        onQueueUpdate: handleAiQueueUpdate,
        onStreamEvent: handleAiStreamEvent,
      });

      onAnalysisUpdate(result.analysis);
      
      aiQueueMessage = null;
      aiErrorMessage = null;
      aiStreamingText = "";
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        return;
      }
      aiErrorMessage = error instanceof Error ? error.message : "Unable to generate AI snapshot";
      console.error("Book AI analysis failed:", error);
    } finally {
      aiLoading = false;
    }
  }

  onMount(() => {
    // Auto-trigger only after the parent has finished loading the book (book.ai is hydrated
    // from Postgres on the initial GET /api/books/{id} call, so if the field is null here
    // the book genuinely has no cached analysis yet).
    const autoTriggerDelay = setTimeout(() => {
      if (!book.ai && !aiLoading && !aiAutoTriggerDeferred) {
        void triggerAiGeneration(false);
      }
    }, 0);

    return () => {
      clearTimeout(autoTriggerDelay);
      aiAbortController?.abort();
    };
  });
</script>

<section class="rounded-xl border border-linen-200 bg-linen-50/60 p-4 dark:border-slate-700 dark:bg-slate-900/60">
  <div class="mb-2 flex items-center justify-between gap-3">
    <h2 class="inline-flex items-center gap-2 text-sm font-semibold text-anthracite-900 dark:text-slate-100">
      <Sparkles size={16} class="text-canvas-500" />
      Reader Fit Snapshot
    </h2>
    <button
      type="button"
      class="inline-flex items-center gap-1 rounded-md border border-linen-300 px-2.5 py-1 text-xs font-medium text-anthracite-700 transition hover:bg-linen-100 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"
      disabled={aiLoading}
      onclick={() => triggerAiGeneration(true)}
    >
      <RefreshCw size={13} class={aiLoading ? "animate-spin" : ""} />
      Refresh
    </button>
  </div>

  {#if book.ai}
    <p class="text-sm leading-relaxed text-anthracite-800 dark:text-slate-200">{book.ai.summary}</p>
    <p class="mt-2 text-xs text-anthracite-700 dark:text-slate-300">{book.ai.readerFit}</p>
    {#if book.ai.keyThemes.length > 0}
      <div class="mt-3 flex flex-wrap gap-1.5">
        {#each book.ai.keyThemes as theme}
          <span class="rounded-full border border-linen-300 px-2 py-0.5 text-[11px] text-anthracite-700 dark:border-slate-600 dark:text-slate-300">
            {theme}
          </span>
        {/each}
      </div>
    {/if}
    <p class="mt-3 text-[11px] text-anthracite-500 dark:text-slate-400">
      Version {book.ai.version ?? "?"}
      • {book.ai.model ?? "Unknown model"}
      • {formatAiGeneratedAt(book.ai.generatedAt)}
    </p>
  {:else if aiLoading}
    <p class="text-sm text-anthracite-700 dark:text-slate-300">{aiLoadingMessage}</p>
    {#if aiQueueMessage}
      <p class="mt-2 text-xs text-anthracite-500 dark:text-slate-400">{aiQueueMessage}</p>
    {/if}
    {#if aiStreamingText}
      <p class="mt-2 whitespace-pre-wrap text-xs text-anthracite-600 dark:text-slate-300">{aiStreamingText}</p>
    {/if}
  {:else if aiAutoTriggerDeferred}
    <p class="text-xs text-anthracite-600 dark:text-slate-300">
      AI generation is temporarily paused while the site-wide queue is busy.
    </p>
  {:else if aiErrorMessage}
    <p class="text-xs text-red-700 dark:text-red-300">{aiErrorMessage}</p>
  {:else}
    <p class="text-xs text-anthracite-600 dark:text-slate-300">
      Generate a concise AI snapshot of who this book is best for.
    </p>
  {/if}
</section>
