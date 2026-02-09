import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/svelte";
import BookAiContentPanel from "$lib/components/BookAiContentPanel.svelte";

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
  beforeEach(() => {
    getBookAiContentQueueStatsMock.mockReset();
    getBookAiContentQueueStatsMock.mockResolvedValue({
      running: 0,
      pending: 0,
      maxParallel: 1,
      available: true,
      environmentMode: "production",
    });
    isBookAiContentStreamErrorMock.mockReset();
    isBookAiContentStreamErrorMock.mockReturnValue(false);
    streamBookAiContentMock.mockReset();
  });

  /**
   * Production must keep the Reader's Guide hidden when source material is
   * insufficient for faithful generation and no cached AI content exists.
   */
  it("shouldHideReadersGuideAndSkipGenerationWhenDescriptionTooShortInProduction", async () => {
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
    expect(streamBookAiContentMock).not.toHaveBeenCalled();
    expect(onAiContentUpdate).not.toHaveBeenCalled();
  });
});
