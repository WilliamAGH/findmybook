<script lang="ts">
  import { onMount, untrack } from "svelte";
  import BookAiContentPanelView from "$lib/components/BookAiContentPanelView.svelte";
  import {
    BookAiContentRequestAttempt,
    cancelBookAiContentRequest,
    isBookAiContentStreamError,
    streamBookAiContent,
  } from "$lib/services/bookAiContentStream";
  import { getBookAiContentQueueStats } from "$lib/services/books";
  import {
    hasRenderableAiContent,
    PRODUCTION_ENVIRONMENT_MODE,
    normalizeEnvironmentMode,
    shouldRenderPanel,
  } from "$lib/services/bookAiContentPanelState";
  import type {
    Book,
    BookAiContentModelStreamUpdate,
    BookAiContentQueuedUpdate,
    BookAiContentQueueUpdate,
    BookAiContentSnapshot,
    BookAiErrorCode,
  } from "$lib/validation/schemas";

  let { identifier, book, onAiContentUpdate }: {
    identifier: string; book: Book; onAiContentUpdate: (aiContent: BookAiContentSnapshot) => void;
  } = $props();

  const COLLAPSE_STORAGE_KEY = "findmybook:ai-collapsed";

  let aiLoading = $state(false);
  let aiErrorMessage = $state<string | null>(null);
  let aiQueueMessage = $state<string | null>(null);
  let aiLoadingMessage = $state("Generating AI content...");
  let aiAutoTriggerDeferred = $state(false);
  let aiServiceAvailable = $state(true);
  let aiEnvironmentMode = $state(import.meta.env.DEV ? "development" : PRODUCTION_ENVIRONMENT_MODE);
  let collapsed = $state(false);
  let lastAutoTriggerIdentifier = $state<string | null>(null);

  let activeGenerationAttempt: BookAiContentRequestAttempt | null = null;
  let willAutoTrigger = $derived(!!identifier && !hasRenderableAiContent(book)
    && !aiAutoTriggerDeferred && lastAutoTriggerIdentifier !== identifier);

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

  function shouldDisplayPanel(): boolean {
    return shouldRenderPanel(aiFailureDiagnosticsEnabled(), aiServiceAvailable, book);
  }

  function isActiveAttempt(attempt: BookAiContentRequestAttempt): boolean {
    return activeGenerationAttempt === attempt && identifier === attempt.identifier && !attempt.isCanceled;
  }

  function cancelActiveGeneration(): void {
    const attempt = activeGenerationAttempt;
    if (attempt !== null) {
      attempt.cancel();
      activeGenerationAttempt = null;
    }
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
      aiQueueMessage = null;
      aiAutoTriggerDeferred = false;
      return;
    }

    aiErrorMessage = refresh && hasRenderableAiContent(book)
      ? "Refresh failed. Showing the previous Reader's Guide."
      : null;
    if (failure.code === "queue_busy") {
      aiQueueMessage = "Queue is busy right now. Try again shortly.";
      aiAutoTriggerDeferred = !refresh;
      return;
    }

    aiQueueMessage = null;
    aiAutoTriggerDeferred = false;
    if (!hasRenderableAiContent(book) || failure.code === "service_unavailable" || failure.retryable === false) {
      aiServiceAvailable = false;
    }
  }

  function handleAiQueued(attempt: BookAiContentRequestAttempt, update: BookAiContentQueuedUpdate): void {
    attempt.captureRequestId(update.requestId);
    if (!isActiveAttempt(attempt)) {
      attempt.cancel();
      return;
    }
    handleAiQueueUpdate(attempt, update);
  }

  function handleAiQueueUpdate(attempt: BookAiContentRequestAttempt, update: BookAiContentQueueUpdate): void {
    if (!isActiveAttempt(attempt)) {
      return;
    }
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

  function handleAiStreamEvent(attempt: BookAiContentRequestAttempt, event: BookAiContentModelStreamUpdate): void {
    if (isActiveAttempt(attempt) && event.event === "message_start") {
      aiLoadingMessage = "Generating AI content...";
    }
  }

  async function hasQueueCapacity(refresh: boolean, attempt: BookAiContentRequestAttempt): Promise<boolean> {
    try {
      const queueStats = await getBookAiContentQueueStats();
      if (!isActiveAttempt(attempt)) {
        return false;
      }
      aiEnvironmentMode = normalizeEnvironmentMode(queueStats.environmentMode);
      if (!queueStats.available) {
        aiServiceAvailable = false;
        if (!aiFailureDiagnosticsEnabled()) {
          aiErrorMessage = null;
          aiQueueMessage = null;
          aiAutoTriggerDeferred = false;
          return false;
        }
        if (!hasRenderableAiContent(book)) {
          return false;
        }
        aiErrorMessage = refresh ? "AI content service is not available right now." : null;
        return false;
      }
      aiServiceAvailable = true;
      return true;
    } catch (queueError) {
      if (!isActiveAttempt(attempt)) {
        return false;
      }
      if (aiFailureDiagnosticsEnabled()) {
        console.error("[BookAiContentPanel] Queue stats failed:", queueError);
      } else {
        console.error("[BookAiContentPanel] Queue stats unavailable in production");
      }
      const message = queueError instanceof Error
        ? queueError.message
        : "Unable to check queue status";
      if (aiFailureDiagnosticsEnabled()) {
        aiErrorMessage = message;
        aiQueueMessage = null;
        aiAutoTriggerDeferred = false;
      } else {
        aiErrorMessage = null;
        aiQueueMessage = null;
        aiAutoTriggerDeferred = false;
        if (!hasRenderableAiContent(book)) {
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
    const attempt = new BookAiContentRequestAttempt(requestIdentifier, cancelBookAiContentRequest);
    activeGenerationAttempt = attempt;
    aiLoading = true;
    aiErrorMessage = null;
    aiQueueMessage = null;
    aiAutoTriggerDeferred = false;
    aiLoadingMessage = refresh
      ? "Refreshing AI content..."
      : "Generating AI content...";

    const queueHasCapacity = await hasQueueCapacity(refresh, attempt);
    if (!isActiveAttempt(attempt)) {
      return;
    }
    if (!queueHasCapacity) {
      attempt.complete();
      activeGenerationAttempt = null;
      aiLoading = false;
      return;
    }

    aiErrorMessage = null;
    aiQueueMessage = null;

    try {
      const result = await streamBookAiContent(requestIdentifier, {
        refresh,
        signal: attempt.signal,
        onRequestId: (requestId) => {
          attempt.captureRequestId(requestId);
          if (!isActiveAttempt(attempt)) attempt.cancel();
        },
        onQueued: (update) => handleAiQueued(attempt, update),
        onQueueUpdate: (update) => update.event !== "queued" && handleAiQueueUpdate(attempt, update),
        onStreamEvent: (event) => handleAiStreamEvent(attempt, event),
      });

      attempt.complete();
      if (!isActiveAttempt(attempt)) {
        return;
      }
      onAiContentUpdate(result.aiContent);

      aiQueueMessage = null;
      aiErrorMessage = null;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError" && attempt.isCanceled) {
        return;
      }
      const attemptWasActive = isActiveAttempt(attempt);
      if (!isBookAiContentStreamError(error)) attempt.cancel();
      attempt.complete();
      if (!attemptWasActive) {
        return;
      }
      const failure = resolveAiStreamFailure(error);
      applyAiFailureState(failure, refresh);
      if (aiFailureDiagnosticsEnabled()) {
        console.error("Book AI content generation failed:", error);
      } else {
        console.error("[BookAiContentPanel] AI generation failed in production", {
          code: failure.code,
          retryable: failure.retryable,
        });
      }
    } finally {
      if (activeGenerationAttempt === attempt) {
        activeGenerationAttempt = null;
        aiLoading = false;
      }
    }
  }

  function refreshAiContent(): void { void triggerAiGeneration(true); }

  onMount(() => {
    collapsed = readCollapseState();
    return cancelActiveGeneration;
  });

  $effect(() => {
    if (!identifier || untrack(() => lastAutoTriggerIdentifier) === identifier) {
      return;
    }

    cancelActiveGeneration();
    aiLoading = false;
    aiErrorMessage = null;
    aiQueueMessage = null;
    aiAutoTriggerDeferred = false;
    aiServiceAvailable = true;
    aiLoadingMessage = "Generating AI content...";

    const scheduledIdentifier = identifier;

    const autoTriggerDelay = setTimeout(() => {
      lastAutoTriggerIdentifier = scheduledIdentifier;
      if (identifier !== scheduledIdentifier) {
        return;
      }
      if (!hasRenderableAiContent(book) && !aiLoading && !aiAutoTriggerDeferred) {
        const requiresRefresh = Boolean(book?.aiContent);
        void triggerAiGeneration(requiresRefresh);
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
