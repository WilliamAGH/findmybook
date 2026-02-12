/**
 * Cover image persistence — fetches rendered cover bytes and uploads them
 * to the backend ingest endpoint for permanent S3-backed storage.
 *
 * Extracted from the book data access layer to keep each service module
 * single-purpose and within the repository file-size ceiling.
 */
import { postMultipart } from "$lib/services/http";
import {
  type CoverIngestResponse,
  CoverIngestResponseSchema,
} from "$lib/validation/coverSchemas";

export interface PersistRenderedCoverRequest {
  identifier: string;
  renderedCoverUrl: string;
  source?: string | null;
}

function imageFileExtensionFromMimeType(contentType: string): string {
  const normalized = contentType.split(";")[0]?.trim().toLowerCase() ?? "";
  if (normalized === "image/png") {
    return ".png";
  }
  if (normalized === "image/webp") {
    return ".webp";
  }
  if (normalized === "image/gif") {
    return ".gif";
  }
  if (normalized === "image/svg+xml") {
    return ".svg";
  }
  if (normalized === "image/jpeg" || normalized === "image/jpg") {
    return ".jpg";
  }
  return ".jpg";
}

function normalizeHttpCoverUrl(rawUrl: string): string {
  const parsed = new URL(rawUrl, window.location.origin);
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(`persistRenderedCover received unsupported protocol: ${parsed.protocol}`);
  }
  return parsed.href;
}

function normalizeCoverSource(source: string | null | undefined): string | null {
  if (!source) {
    return null;
  }
  const trimmed = source.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * Fetches the cover image at the given URL and uploads it to the backend
 * ingest endpoint for permanent storage. Throws on fetch failure, non-image
 * content type, or empty response body — callers handle persistence errors.
 */
export async function persistRenderedCover(request: PersistRenderedCoverRequest): Promise<CoverIngestResponse> {
  const normalizedCoverUrl = normalizeHttpCoverUrl(request.renderedCoverUrl);
  const imageResponse = await fetch(normalizedCoverUrl, {
    method: "GET",
    mode: "cors",
    credentials: "omit",
    cache: "force-cache",
  });

  if (!imageResponse.ok) {
    throw new Error(`persistRenderedCover failed to fetch image bytes (${imageResponse.status})`);
  }

  const contentType = imageResponse.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("image/")) {
    throw new Error(`persistRenderedCover expected image/* response but received '${contentType}'`);
  }

  const imageBlob = await imageResponse.blob();
  if (imageBlob.size <= 0) {
    throw new Error("persistRenderedCover fetched an empty image blob");
  }

  const formData = new FormData();
  formData.append("image", imageBlob, `cover${imageFileExtensionFromMimeType(contentType)}`);
  formData.append("sourceUrl", normalizedCoverUrl);
  const normalizedSource = normalizeCoverSource(request.source);
  if (normalizedSource) {
    formData.append("source", normalizedSource);
  }

  return postMultipart(
    `/api/covers/${encodeURIComponent(request.identifier)}/ingest`,
    formData,
    CoverIngestResponseSchema,
    `persistRenderedCover:${request.identifier}`,
  );
}
