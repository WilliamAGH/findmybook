import { afterEach, describe, expect, it, vi } from "vitest";
import { cancelBookAiContentRequest, streamBookAiContent } from "$lib/services/bookAiContentStream";
import {
  BookAiContentQueuedUpdateSchema,
  BookAiContentQueueUpdateSchema,
  CoverSchema,
  SearchProgressEventSchema,
  buildCover,
  resolveCoverDisplayUrl,
} from "$lib/validation/schemas";

describe("resolveCoverDisplayUrl", () => {
  it("should_ReturnPreferredUrl_When_AllUrlsPresent", () => {
    expect(resolveCoverDisplayUrl("preferred", "s3", "external")).toBe("preferred");
  });

  it("should_SkipBlankPreferredUrl_When_S3Present", () => {
    expect(resolveCoverDisplayUrl("", "s3", "external")).toBe("s3");
  });

  it("should_SkipWhitespacePreferredUrl_When_S3Present", () => {
    expect(resolveCoverDisplayUrl("   ", "s3", "external")).toBe("s3");
  });

  it("should_ReturnS3ImagePath_When_PreferredUrlNull", () => {
    expect(resolveCoverDisplayUrl(null, "s3", "external")).toBe("s3");
  });

  it("should_ReturnExternalImageUrl_When_PreferredAndS3Null", () => {
    expect(resolveCoverDisplayUrl(null, null, "external")).toBe("external");
  });

  it("should_ReturnNull_When_AllUrlsNull", () => {
    expect(resolveCoverDisplayUrl(null, null, null)).toBeNull();
  });

  it("should_ReturnNull_When_AllUrlsUndefined", () => {
    expect(resolveCoverDisplayUrl(undefined, undefined, undefined)).toBeNull();
  });

  it("should_SkipUndefinedPreferred_When_S3Present", () => {
    expect(resolveCoverDisplayUrl(undefined, "s3", "external")).toBe("s3");
  });

  it("should_ReturnPreferredUrl_When_OthersUndefined", () => {
    expect(resolveCoverDisplayUrl("preferred", undefined, undefined)).toBe("preferred");
  });
});

describe("CoverSchema.transform displayUrl", () => {
  it("should_ComputeDisplayUrl_When_ParsedWithPreferredUrl", () => {
    const input = {
      s3ImagePath: "s3-path",
      externalImageUrl: "ext-url",
      preferredUrl: "preferred-url",
    };

    const result = CoverSchema.parse(input);

    expect(result.displayUrl).toBe("preferred-url");
  });

  it("should_FallToS3_When_ParsedWithoutPreferredUrl", () => {
    const input = {
      s3ImagePath: "s3-path",
      externalImageUrl: "ext-url",
    };

    const result = CoverSchema.parse(input);

    expect(result.displayUrl).toBe("s3-path");
  });

  it("should_ReturnNull_When_ParsedWithNoUrls", () => {
    const result = CoverSchema.parse({});

    expect(result.displayUrl).toBeNull();
  });
});

describe("buildCover", () => {
  it("should_ComputeDisplayUrl_When_CalledWithPreferredUrl", () => {
    const cover = buildCover({
      preferredUrl: "preferred",
      s3ImagePath: "s3",
      externalImageUrl: "ext",
    });

    expect(cover.displayUrl).toBe("preferred");
  });

  it("should_FallThroughPriorityChain_When_HigherPriorityMissing", () => {
    const cover = buildCover({ externalImageUrl: "ext" });

    expect(cover.displayUrl).toBe("ext");
  });
});

describe("BookAiContentQueueUpdateSchema", () => {
  it("shouldRequireRequestIdForInitialQueuedEvent", () => {
    const result = BookAiContentQueuedUpdateSchema.safeParse({
      event: "queued", position: 1, running: 0, pending: 1, maxParallel: 1,
    });

    expect(result.success).toBe(false);
  });

  it("shouldNotRequireRequestIdForPeriodicQueueEvent", () => {
    const result = BookAiContentQueueUpdateSchema.safeParse({
      event: "queue", position: 1, running: 0, pending: 1, maxParallel: 1,
    });

    expect(result.success).toBe(true);
  });
});

describe("SearchProgressEventSchema", () => {
  it("should_PreserveOpaqueStatus_When_ParsingProgressEvent", () => {
    const progressEvent = SearchProgressEventSchema.parse({
      status: "LOCAL_RATE_LIMITED",
      message: "Provider state updated",
    });

    expect(progressEvent.status).toBe("LOCAL_RATE_LIMITED");
  });

  it("should_RejectEmptyStatus_When_ParsingProgressEvent", () => {
    const result = SearchProgressEventSchema.safeParse({ status: "" });

    expect(result.success).toBe(false);
  });

  it("should_RejectMissingStatus_When_ParsingProgressEvent", () => {
    const result = SearchProgressEventSchema.safeParse({});

    expect(result.success).toBe(false);
  });
});

describe("cancelBookAiContentRequest", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shouldUseBodylessBeaconWhenAvailable", () => {
    const sendBeacon = vi.fn(() => true);
    const fetchMock = vi.fn();
    vi.stubGlobal("navigator", { sendBeacon });
    vi.stubGlobal("fetch", fetchMock);

    cancelBookAiContentRequest("request/id");

    expect(sendBeacon).toHaveBeenCalledWith("/api/books/ai/content/requests/request%2Fid/cancel");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("shouldFallbackToBodylessKeepaliveFetchWhenBeaconDeclines", () => {
    const fetchMock = vi.fn<typeof fetch>(() => Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("navigator", { sendBeacon: vi.fn(() => false) });
    vi.stubGlobal("fetch", fetchMock);

    cancelBookAiContentRequest("request-2");

    expect(fetchMock).toHaveBeenCalledWith("/api/books/ai/content/requests/request-2/cancel", {
      method: "POST",
      keepalive: true,
    });
    expect(fetchMock.mock.calls[0]?.[1]).not.toHaveProperty("body");
  });

  it("shouldWarnWithoutResponseDetailWhenKeepaliveCancellationIsRejected", async () => {
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.stubGlobal("navigator", { sendBeacon: vi.fn(() => false) });
    vi.stubGlobal("fetch", vi.fn<typeof fetch>(() => Promise.resolve(new Response(null, { status: 503 }))));

    cancelBookAiContentRequest("request-3");
    await vi.waitFor(() => expect(warning).toHaveBeenCalledOnce());

    expect(warning).toHaveBeenCalledWith("[BookAiContentStream] Explicit cancellation delivery failed");
    expect(warning).not.toHaveBeenCalledWith(expect.stringContaining("503"));
  });
});

describe("streamBookAiContent request identity", () => {
  afterEach(() => vi.unstubAllGlobals());

  function streamBody(requestId: string): string {
    return [
      `event: queued\ndata: ${JSON.stringify({
        requestId, position: 1, running: 0, pending: 1, maxParallel: 1,
      })}`,
      `event: done\ndata: ${JSON.stringify({
        message: "complete", aiContent: { summary: "Summary", keyThemes: [] },
      })}`,
      "",
    ].join("\n\n");
  }

  it("shouldExposeHeaderRequestIdBeforeQueuedEvent", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>(() => Promise.resolve(new Response(streamBody("request-1"), {
      status: 200,
      headers: { "Content-Type": "text/event-stream", "X-Book-AI-Request-Id": "request-1" },
    }))));
    const callbackOrder: string[] = [];

    await streamBookAiContent("book", {
      onRequestId: (requestId) => callbackOrder.push(`header:${requestId}`),
      onQueued: (update) => callbackOrder.push(`queued:${update.requestId}`),
    });

    expect(callbackOrder).toEqual(["header:request-1", "queued:request-1"]);
  });

  it("shouldRejectWhenHeaderAndQueuedRequestIdsDiffer", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>(() => Promise.resolve(new Response(streamBody("queued-request"), {
      status: 200,
      headers: { "Content-Type": "text/event-stream", "X-Book-AI-Request-Id": "header-request" },
    }))));

    await expect(streamBookAiContent("book")).rejects.toThrow(
      "Book AI request ID did not match queued stream payload",
    );
  });
});
