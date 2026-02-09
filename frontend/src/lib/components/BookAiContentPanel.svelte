<script lang="ts">
  import { onMount, untrack } from "svelte";
  import BookAiContentPanelView from "$lib/components/BookAiContentPanelView.svelte";
  import { isBookAiContentStreamError, streamBookAiContent } from "$lib/services/bookAiContentStream";
  import { getBookAiContentQueueStats } from "$lib/services/books";
  import {
    AI_AUTO_TRIGGER_QUEUE_THRESHOLD,
    PRODUCTION_ENVIRONMENT_MODE,
    normalizeEnvironmentMode,
    shouldRenderPanel,
    shouldSuppressPanelForShortDescriptionInProduction,
  } from "$lib/services/bookAiContentPanelState";
  import type {
    Book,
    BookAiContentModelStreamUpdate,
    BookAiContentQueueUpdate,
    BookAiContentSnapshot,
    BookAiErrorCode,
  } from "$lib/validation/schemas";

  interface Props {
    identifier: string;
    book: Book;
    onAiContentUpdate: (aiContent: BookAiContentSnapshot) => void;
  }

  let { identifier, book, onAiContentUpdate }: Props = $props();

  const COLLAPSE_STORAGE_KEY = "findmybook:ai-collapsed";

  let aiLoading = $state(false);
  let aiErrorMessage = $state<string | null>(null);
  let aiQueueMessage = $state<string | null>(null);
  let aiLoadingMessage = $state("Generating AI content...");
  let aiAutoTriggerDeferred = $state(false);
  let aiServiceAvailable = $state(true);
  let aiEnvironmentMode = $state(import.meta.env.DEV ? "development" : PRODUCTION_ENVIRONMENT_MODE);
  let collapsed = $state(false);
  let aiAbortController: AbortController | null = null;
  let activeRequestToken: symbol | null = null;
  let lastAutoTriggerIdentifier = $state<string | null>(null);

  /** True when the auto-trigger $effect is about to fire but hasn't yet set aiLoading. */
  let willAutoTrigger = $derived(
    !!identifier
      && !book?.aiContent
      && !aiAutoTriggerDeferred
      && lastAutoTriggerIdentifier !== identifier,
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

  function aiFailureDiagnosticsEnabled(): boolean {
    return aiEnvironmentMode !== PRODUCTION_ENVIRONMENT_MODE;
  }

  function shouldSuppressPanelForCurrentBookInProduction(): boolean {
    return shouldSuppressPanelForShortDescriptionInProduction(aiFailureDiagnosticsEnabled(), book);
  }

  function shouldDisplayPanel(): boolean {
    return shouldRenderPanel(aiFailureDiagnosticsEnabled(), aiServiceAvailable, book);
  }

  interface AiStreamFailure {
    code: BookAiErrorCode;
    message: string;
    retryable: boolean;
  }
  function resolveAiStreamFailure(error: unknown): AiStreamFailure {
    if (isBookAiContentStreamError(error)) {
      return {
        code: error.code,
        message: error.message,
        retryable: error.retryable,
      };
    }

    const defaultErrorMessage = error instanceof Error ? error.message : "Unable to generate AI content";
    return {
      code: "generation_failed",
      message: defaultErrorMessage,
      retryable: true,
    };
  }

  function applyAiFailureState(failure: AiStreamFailure, refresh: boolean): void {
    if (aiFailureDiagnosticsEnabled()) {
      if (failure.code === "service_unavailable") {
        aiServiceAvailable = false;
      }
      aiErrorMessage = failure.message;
      return;
    }

    aiErrorMessage = null;
    if (failure.code === "queue_busy") {
      aiQueueMessage = "Queue is busy right now. Try again shortly.";
      aiAutoTriggerDeferred = !refresh;
      return;
    }

    aiQueueMessage = null;
    aiAutoTriggerDeferred = false;
    if (!book?.aiContent || failure.code === "service_unavailable" || failure.retryable === false) {
      aiServiceAvailable = false;
    }
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
      aiEnvironmentMode = normalizeEnvironmentMode(queueStats.environmentMode);
      if (!queueStats.available) {
        aiServiceAvailable = false;
        if (!aiFailureDiagnosticsEnabled()) {
          aiErrorMessage = null;
          aiQueueMessage = null;
          aiAutoTriggerDeferred = false;
          return false;
        }
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
        if (aiFailureDiagnosticsEnabled() && refresh) {
          aiErrorMessage = "Queue is busy right now. Try again shortly.";
        } else {
          aiErrorMessage = null;
        }
        return false;
      }
      return true;
    } catch (queueError) {
      console.error("[BookAiContentPanel] Queue stats failed:", queueError);
      const message = queueError instanceof Error
        ? queueError.message
        : "Unable to check queue status";
      if (aiFailureDiagnosticsEnabled()) {
        aiErrorMessage = message;
      } else {
        aiErrorMessage = null;
        aiQueueMessage = null;
        aiAutoTriggerDeferred = false;
        if (!book?.aiContent) {
          aiServiceAvailable = false;
        }
      }
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
      const failure = resolveAiStreamFailure(error);
      applyAiFailureState(failure, refresh);
      console.error("Book AI content generation failed:", error);
    } finally {
      if (activeRequestToken === requestToken) {
        activeRequestToken = null;
        aiLoading = false;
      }
    }
  }

  function refreshAiContent(): void {
    void triggerAiGeneration(true);
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

{#if shouldDisplayPanel()}
  <BookAiContentPanelView
    {book}
    {collapsed}
    {aiServiceAvailable}
    {aiLoading}
    {aiErrorMessage}
    {aiQueueMessage}
    {aiLoadingMessage}
    {aiAutoTriggerDeferred}
    {willAutoTrigger}
    onToggleCollapsed={toggleCollapsed}
    onRefresh={refreshAiContent}
  />
{/if}
