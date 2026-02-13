import { describe, expect, it } from "vitest";
import { resolveCoverDisplayUrl, CoverSchema, buildCover } from "$lib/validation/schemas";

describe("resolveCoverDisplayUrl", () => {
  it("should_ReturnPreferredUrl_When_AllUrlsPresent", () => {
    expect(resolveCoverDisplayUrl("preferred", "s3", "external")).toBe("preferred");
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
