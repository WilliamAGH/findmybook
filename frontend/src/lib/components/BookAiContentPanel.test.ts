import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/svelte";
import BookAiContentPanel from "$lib/components/BookAiContentPanel.svelte";
import type { BookAiErrorCode } from "$lib/validation/schemas";

const {
  getBookAiContentQueueStatsMock,
  isBookAiContentStreamErrorMock,
  streamBookAiContentMock,
} = vi.hoisted(() => ({
  getBookAiContentQueueStatsMock: vi.fn(),
  isBookAiContentStreamErrorMock: vi.fn(() => false),
  streamBookAiContentMock: vi.fn(),
}));

vi.mock("$lib/services/books", () => ({
  getBookAiContentQueueStats: getBookAiContentQueueStatsMock,
}));

vi.mock("$lib/services/bookAiContentStream", () => ({
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

  beforeEach(() => {
    getBookAiContentQueueStatsMock.mockReset();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(console, "error").mockImplementation(() => {});
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
        book: {
          id: "book-short",
          slug: "book-short",
          title: "Short Description Fixture",
          description: "tiny",
          descriptionContent: { text: "tiny" },
          aiContent: null,
        } as any,
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
        book: {
          id: "book-short",
          slug: "book-short",
          title: "Short Description Fixture",
          description: "tiny",
          descriptionContent: { text: "tiny" },
          aiContent: null,
        } as any,
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

  it("shouldAttemptRegenerationWhenExistingSummaryIsDegenerate", async () => {
    streamBookAiContentMock.mockResolvedValue({
      aiContent: {
        summary: "This regenerated summary now contains enough descriptive prose to render safely.",
        keyThemes: ["Theme"],
        takeaways: ["Takeaway"],
        readerFit: null,
        context: null,
      },
    });
    const onAiContentUpdate = vi.fn();

    render(BookAiContentPanel, {
      props: {
        identifier: "degenerate-summary-book",
        book: {
          id: "book-degenerate",
          slug: "book-degenerate",
          title: "Degenerate Summary Fixture",
          description:
            "This description is long enough for AI generation and should allow regeneration attempts.",
          descriptionContent: {
            text: "This description is long enough for AI generation and should allow regeneration attempts.",
          },
          aiContent: {
            summary: "@".repeat(120),
            keyThemes: ["Legacy"],
            takeaways: ["Legacy takeaway"],
            readerFit: null,
            context: null,
          },
        } as any,
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
  });
});
