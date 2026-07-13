import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import BookAiContentPanel from "$lib/components/BookAiContentPanel.svelte";
import type { StreamBookAiContentOptions } from "$lib/services/bookAiContentStream";
import { BookAiContentQueuedUpdateSchema, type Book, type BookAiErrorCode } from "$lib/validation/schemas";

const {
  getBookAiContentQueueStatsMock,
  cancelBookAiContentRequestMock,
  isBookAiContentStreamErrorMock,
  streamBookAiContentMock,
  consoleErrorMock,
} = vi.hoisted(() => ({
  getBookAiContentQueueStatsMock: vi.fn(),
  cancelBookAiContentRequestMock: vi.fn(),
  isBookAiContentStreamErrorMock: vi.fn(() => false),
  streamBookAiContentMock: vi.fn(),
  consoleErrorMock: vi.fn(),
}));

vi.mock("$lib/services/books", () => ({
  getBookAiContentQueueStats: getBookAiContentQueueStatsMock,
}));

vi.mock("$lib/services/bookAiContentStream", async (importOriginal) => ({
  ...await importOriginal<typeof import("$lib/services/bookAiContentStream")>(),
  cancelBookAiContentRequest: cancelBookAiContentRequestMock,
  isBookAiContentStreamError: isBookAiContentStreamErrorMock,
  streamBookAiContent: streamBookAiContentMock,
}));

describe("BookAiContentPanel production behavior", () => {
  function createStreamError(
    message: string,
    code: BookAiErrorCode,
    retryable: boolean,
  ): Error & { code: BookAiErrorCode; retryable: boolean } {
    const streamError = new Error(message) as Error & { code: BookAiErrorCode; retryable: boolean };
    streamError.code = code;
    streamError.retryable = retryable;
    return streamError;
  }

  function createBookFixture(overrides: Partial<Book>): Book {
    return {
      id: "book-fixture",
      slug: "book-fixture",
      title: "Book Fixture",
      description: null,
      descriptionContent: { raw: null, format: "UNKNOWN", html: "", text: "" },
      publication: { publishedDate: null, language: null, pageCount: null, publisher: null },
      authors: [],
      categories: [],
      collections: [],
      tags: [],
      cover: null,
      editions: [],
      recommendationIds: [],
      extras: {},
      aiContent: null,
      ...overrides,
    };
  }

  function queued(requestId: string) {
    return BookAiContentQueuedUpdateSchema.parse({
      event: "queued", requestId, position: 1, running: 0, pending: 1, maxParallel: 1,
    });
  }

  function streamOptions(callIndex = 0): StreamBookAiContentOptions {
    return streamBookAiContentMock.mock.calls[callIndex]?.[1] as StreamBookAiContentOptions;
  }

  beforeEach(() => {
    getBookAiContentQueueStatsMock.mockReset();
    cancelBookAiContentRequestMock.mockReset();
    consoleErrorMock.mockReset();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(console, "error").mockImplementation(consoleErrorMock);
    vi.spyOn(console, "info").mockImplementation(() => {});
    getBookAiContentQueueStatsMock.mockResolvedValue({
      running: 0,
      pending: 0,
      maxParallel: 1,
      available: true,
      environmentMode: "production",
    });
    isBookAiContentStreamErrorMock.mockReset();
    isBookAiContentStreamErrorMock.mockReturnValue(true);
    streamBookAiContentMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  /**
   * Production keeps the Reader's Guide hidden, but still attempts a stream call so
   * backend enrichment can run before the `description_too_short` terminal decision.
   */
  it("shouldAttemptGenerationAndKeepPanelHiddenWhenDescriptionRemainsTooShortInProduction", async () => {
    streamBookAiContentMock.mockRejectedValue(
      createStreamError("AI content is unavailable for this book", "description_too_short", false),
    );
    const onAiContentUpdate = vi.fn();

    render(BookAiContentPanel, {
      props: {
        identifier: "short-description-book",
        book: createBookFixture({
          id: "book-short",
          slug: "book-short",
          title: "Short Description Fixture",
          description: "tiny",
          descriptionContent: { raw: "tiny", format: "PLAIN_TEXT", html: "tiny", text: "tiny" },
        }),
        onAiContentUpdate,
      },
    });

    await waitFor(() => {
      expect(screen.queryByText("Reader's Guide")).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(streamBookAiContentMock).toHaveBeenCalledTimes(1);
    });
    expect(onAiContentUpdate).not.toHaveBeenCalled();
  });

  it("shouldShowBackendShortDescriptionErrorWhenDescriptionTooShortInDiagnosticsMode", async () => {
    getBookAiContentQueueStatsMock.mockResolvedValue({
      running: 0,
      pending: 0,
      maxParallel: 1,
      available: true,
      environmentMode: "development",
    });
    streamBookAiContentMock.mockRejectedValue(
      createStreamError(
        "Book description is missing or too short for faithful AI generation",
        "description_too_short",
        false,
      ),
    );
    const onAiContentUpdate = vi.fn();

    render(BookAiContentPanel, {
      props: {
        identifier: "short-description-book",
        book: createBookFixture({
          id: "book-short",
          slug: "book-short",
          title: "Short Description Fixture",
          description: "tiny",
          descriptionContent: { raw: "tiny", format: "PLAIN_TEXT", html: "tiny", text: "tiny" },
        }),
        onAiContentUpdate,
      },
    });

    await waitFor(() => {
      expect(screen.getByText("Reader's Guide")).toBeInTheDocument();
      expect(screen.getByText(/missing or too short/i)).toBeInTheDocument();
    });
    expect(streamBookAiContentMock).toHaveBeenCalledTimes(1);
    expect(onAiContentUpdate).not.toHaveBeenCalled();
  });

  it("shouldKeepPreviousContentAndShowGenericErrorWhenRefreshFailsInProduction", async () => {
    const providerFailureDetail = "provider detail must remain private";
    streamBookAiContentMock.mockImplementation((_identifier, options: StreamBookAiContentOptions) => {
      options.onQueued?.(queued("terminal-error-request"));
      return Promise.reject(createStreamError(providerFailureDetail, "generation_failed", true));
    });
    const onAiContentUpdate = vi.fn();

    const { unmount } = render(BookAiContentPanel, {
      props: {
        identifier: "existing-guide-book",
        book: createBookFixture({
          aiContent: {
            summary: "The previously generated Reader's Guide remains available after a failed refresh.",
            keyThemes: ["Reliability"],
            takeaways: ["Preserve the last successful result."],
            readerFit: null,
            context: null,
          },
        }),
        onAiContentUpdate,
      },
    });

    await fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => {
      expect(screen.getByText("Refresh failed. Showing the previous Reader's Guide.")).toBeInTheDocument();
    });
    expect(screen.getByText(/previously generated Reader's Guide remains available/)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(providerFailureDetail);
    const consoleOutput = consoleErrorMock.mock.calls
      .flat()
      .map((argument) => {
        if (argument instanceof Error) {
          return argument.message;
        }
        return typeof argument === "string" ? argument : JSON.stringify(argument);
      })
      .join("\n");
    expect(consoleOutput).not.toContain(providerFailureDetail);
    expect(consoleErrorMock).toHaveBeenCalledWith(
      "[BookAiContentPanel] AI generation failed in production",
      { code: "generation_failed", retryable: true },
    );
    expect(onAiContentUpdate).not.toHaveBeenCalled();
    unmount();
    expect(cancelBookAiContentRequestMock).not.toHaveBeenCalled();
  });

  it("shouldAttemptRegenerationWhenExistingSummaryIsDegenerate", async () => {
    streamBookAiContentMock.mockImplementation((_identifier, options: StreamBookAiContentOptions) => {
      options.onQueued?.(queued("terminal-done-request"));
      return Promise.resolve({
        aiContent: {
          summary: "This regenerated summary now contains enough descriptive prose to render safely.",
          keyThemes: ["Theme"], takeaways: ["Takeaway"], readerFit: null, context: null,
        },
      });
    });
    const onAiContentUpdate = vi.fn();

    const { unmount } = render(BookAiContentPanel, {
      props: {
        identifier: "degenerate-summary-book",
        book: createBookFixture({
          id: "book-degenerate",
          slug: "book-degenerate",
          title: "Degenerate Summary Fixture",
          description:
            "This description is long enough for AI generation and should allow regeneration attempts.",
          descriptionContent: {
            raw: "This description is long enough for AI generation and should allow regeneration attempts.",
            format: "PLAIN_TEXT",
            html: "This description is long enough for AI generation and should allow regeneration attempts.",
            text: "This description is long enough for AI generation and should allow regeneration attempts.",
          },
          aiContent: {
            summary: "@".repeat(120),
            keyThemes: ["Legacy"],
            takeaways: ["Legacy takeaway"],
            readerFit: null,
            context: null,
          },
        }),
        onAiContentUpdate,
      },
    });

    await waitFor(() => {
      expect(streamBookAiContentMock).toHaveBeenCalledTimes(1);
    });
    expect(streamBookAiContentMock).toHaveBeenCalledWith(
      "degenerate-summary-book",
      expect.objectContaining({ refresh: true }),
    );
    expect(onAiContentUpdate).toHaveBeenCalledTimes(1);
    unmount();
    expect(cancelBookAiContentRequestMock).not.toHaveBeenCalled();
  });

  it("shouldCancelQueuedGenerationExactlyOnceWhenUnmounted", async () => {
    streamBookAiContentMock.mockImplementation(() => new Promise(() => {}));
    const rendered = render(BookAiContentPanel, {
      props: { identifier: "queued-book", book: createBookFixture({}), onAiContentUpdate: vi.fn() },
    });
    await waitFor(() => expect(streamBookAiContentMock).toHaveBeenCalledOnce());

    const options = streamOptions();
    options.onQueued?.(queued("queued-request"));
    rendered.unmount();
    options.onQueued?.(queued("queued-request"));

    expect(cancelBookAiContentRequestMock).toHaveBeenCalledOnce();
    expect(cancelBookAiContentRequestMock).toHaveBeenCalledWith("queued-request");
    expect(options.signal?.aborted).toBe(true);
  });

  it("shouldCancelAfterResponseHeaderBeforeQueuedEventIsParsed", async () => {
    streamBookAiContentMock.mockImplementation(() => new Promise(() => {}));
    const rendered = render(BookAiContentPanel, {
      props: { identifier: "late-queue-book", book: createBookFixture({}), onAiContentUpdate: vi.fn() },
    });
    await waitFor(() => expect(streamBookAiContentMock).toHaveBeenCalledOnce());

    const options = streamOptions();
    options.onRequestId?.("header-request");
    rendered.unmount();

    expect(cancelBookAiContentRequestMock).toHaveBeenCalledOnce();
    expect(cancelBookAiContentRequestMock).toHaveBeenCalledWith("header-request");
  });

  it("shouldIgnoreStaleQueueCallbacksAfterIdentifierChange", async () => {
    streamBookAiContentMock.mockImplementation(() => new Promise(() => {}));
    const rendered = render(BookAiContentPanel, {
      props: { identifier: "first-book", book: createBookFixture({}), onAiContentUpdate: vi.fn() },
    });
    await waitFor(() => expect(streamBookAiContentMock).toHaveBeenCalledOnce());
    const firstOptions = streamOptions();
    firstOptions.onRequestId?.("first-request");

    await rendered.rerender({
      identifier: "second-book", book: createBookFixture({ id: "second-book", slug: "second-book" }),
      onAiContentUpdate: vi.fn(),
    });
    firstOptions.onQueued?.(queued("first-request"));
    firstOptions.onQueueUpdate?.({ event: "queue", position: 99, running: 0, pending: 1, maxParallel: 1 });

    expect(cancelBookAiContentRequestMock).toHaveBeenCalledOnce();
    expect(cancelBookAiContentRequestMock).toHaveBeenCalledWith("first-request");
    expect(screen.queryByText(/position 99/)).not.toBeInTheDocument();
  });
});
