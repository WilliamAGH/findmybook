import { beforeEach, describe, expect, it, vi } from "vitest";
import { normalizeStructuredDataJsonLd, upsertStructuredDataJsonLd } from "$lib/services/structuredDataHead";

const JSON_LD_SELECTOR = 'script[type="application/ld+json"]';
const MANAGED_JSON_LD_SELECTOR = `${JSON_LD_SELECTOR}[data-fmb-structured-data="true"]`;

describe("structuredDataHead", () => {
  beforeEach(() => {
    document.head.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("should_NormalizeJsonLd_When_PayloadIsValidJson", () => {
    const normalized = normalizeStructuredDataJsonLd('{"@context":"https://schema.org","@type":"Book"}');

    expect(normalized).toBe('{"@context":"https://schema.org","@type":"Book"}');
  });

  it("should_ReturnEmpty_When_PayloadIsInvalidJson", () => {
    const warningSpy = vi.spyOn(console, "warn").mockImplementation(() => {});

    const normalized = normalizeStructuredDataJsonLd("{not-json}");

    expect(normalized).toBe("");
    expect(warningSpy).toHaveBeenCalled();
  });

  it("should_UpsertManagedScript_When_ValidPayloadIsProvided", () => {
    upsertStructuredDataJsonLd('{"@context":"https://schema.org","@type":"Book"}');

    const scripts = document.head.querySelectorAll<HTMLScriptElement>(MANAGED_JSON_LD_SELECTOR);
    expect(scripts).toHaveLength(1);
    expect(scripts[0].textContent).toBe('{"@context":"https://schema.org","@type":"Book"}');
  });

  it("should_ReUseServerScript_When_UnmanagedScriptAlreadyExists", () => {
    const serverRenderedScript = document.createElement("script");
    serverRenderedScript.type = "application/ld+json";
    serverRenderedScript.textContent = '{"@type":"WebPage"}';
    document.head.append(serverRenderedScript);

    upsertStructuredDataJsonLd('{"@context":"https://schema.org","@type":"Book"}');

    const scripts = document.head.querySelectorAll<HTMLScriptElement>(JSON_LD_SELECTOR);
    expect(scripts).toHaveLength(1);
    expect(serverRenderedScript.getAttribute("data-fmb-structured-data")).toBe("true");
    expect(serverRenderedScript.textContent).toBe('{"@context":"https://schema.org","@type":"Book"}');
  });

  it("should_ReplaceScriptContent_When_PayloadChanges", () => {
    upsertStructuredDataJsonLd('{"@type":"Book"}');
    upsertStructuredDataJsonLd('{"@type":"WebPage"}');

    const scripts = document.head.querySelectorAll<HTMLScriptElement>(MANAGED_JSON_LD_SELECTOR);
    expect(scripts).toHaveLength(1);
    expect(scripts[0].textContent).toBe('{"@type":"WebPage"}');
  });

  it("should_RemoveManagedScript_When_PayloadIsInvalidOrEmpty", () => {
    const warningSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    upsertStructuredDataJsonLd('{"@type":"Book"}');

    upsertStructuredDataJsonLd("{broken-json}");
    expect(document.head.querySelector(MANAGED_JSON_LD_SELECTOR)).toBeNull();

    upsertStructuredDataJsonLd('{"@type":"Book"}');
    upsertStructuredDataJsonLd("");
    expect(document.head.querySelector(MANAGED_JSON_LD_SELECTOR)).toBeNull();
    expect(warningSpy).toHaveBeenCalled();
  });
});
