const STRUCTURED_DATA_TYPE = "application/ld+json";
const STRUCTURED_DATA_FLAG_ATTRIBUTE = "data-fmb-structured-data";
const STRUCTURED_DATA_FLAG_VALUE = "true";
const MANAGED_SCRIPT_SELECTOR =
  `script[type="${STRUCTURED_DATA_TYPE}"][${STRUCTURED_DATA_FLAG_ATTRIBUTE}="${STRUCTURED_DATA_FLAG_VALUE}"]`;
const ANY_STRUCTURED_DATA_SELECTOR = `script[type="${STRUCTURED_DATA_TYPE}"]`;

/**
 * Normalizes JSON-LD for deterministic head updates.
 *
 * Invalid payloads are ignored to avoid publishing broken structured data.
 */
export function normalizeStructuredDataJsonLd(rawJson: string): string {
  if (!rawJson || rawJson.trim().length === 0) {
    return "";
  }
  try {
    const parsedPayload: unknown = JSON.parse(rawJson);
    return JSON.stringify(parsedPayload);
  } catch (error) {
    console.warn("[seo] Skipping invalid structured data payload", error);
    return "";
  }
}

function claimManagedScript(): HTMLScriptElement | null {
  const managedScript = document.head.querySelector<HTMLScriptElement>(MANAGED_SCRIPT_SELECTOR);
  if (managedScript) {
    return managedScript;
  }

  const existingScript = document.head.querySelector<HTMLScriptElement>(ANY_STRUCTURED_DATA_SELECTOR);
  if (existingScript) {
    existingScript.setAttribute(STRUCTURED_DATA_FLAG_ATTRIBUTE, STRUCTURED_DATA_FLAG_VALUE);
    return existingScript;
  }

  return null;
}

/**
 * Upserts the single managed JSON-LD script element in {@code <head>}.
 */
export function upsertStructuredDataJsonLd(rawJson: string): void {
  if (typeof document === "undefined") {
    return;
  }

  const normalizedJson = normalizeStructuredDataJsonLd(rawJson);
  let scriptNode = claimManagedScript();

  if (!normalizedJson) {
    scriptNode?.remove();
    return;
  }

  if (!scriptNode) {
    scriptNode = document.createElement("script");
    scriptNode.type = STRUCTURED_DATA_TYPE;
    scriptNode.setAttribute(STRUCTURED_DATA_FLAG_ATTRIBUTE, STRUCTURED_DATA_FLAG_VALUE);
    document.head.append(scriptNode);
  }

  scriptNode.textContent = normalizedJson;
}
