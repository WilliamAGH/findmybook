/**
 * Cover relay persistence utilities for the book detail page.
 *
 * Manages client-side deduplication of cover-persist attempts so that each
 * {book, renderedUrl} pair is relayed to the backend at most once per page
 * lifecycle. The caller-owned `attemptedCoverPersistKeys` Set tracks which
 * keys have been reserved, and these functions mutate that Set as a side effect.
 */
import type { CoverIngestResponse } from "$lib/validation/coverSchemas";
import type { Book } from "$lib/validation/schemas";

const S3_COVER_PATH_SEGMENT = "/images/book-covers/";
const PLACEHOLDER_COVER_FILENAME = "placeholder-book-cover.svg";

function normalizeCoverUrl(candidateUrl: string): string | null {
  if (!candidateUrl || candidateUrl.trim().length === 0) {
    return null;
  }

  try {
    const baseOrigin = typeof location !== "undefined" ? location.origin : undefined;
    const parsed = baseOrigin ? new URL(candidateUrl, baseOrigin) : new URL(candidateUrl);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return null;
    }
    return parsed.href;
  } catch (error) {
    console.debug("[normalizeCoverUrl] Invalid URL:", candidateUrl, error);
    return null;
  }
}

function hasPersistedS3Cover(book: Book): boolean {
  return Boolean(book.cover?.s3ImagePath && book.cover.s3ImagePath.trim().length > 0);
}

function isPersistedCoverUrl(candidateUrl: string): boolean {
  const normalized = candidateUrl.toLowerCase();
  return normalized.includes(S3_COVER_PATH_SEGMENT)
    || normalized.includes("images/book-covers/");
}

function isPlaceholderCoverUrl(candidateUrl: string, placeholderCoverUrl: string): boolean {
  const normalized = candidateUrl.toLowerCase();
  const normalizedPlaceholder = placeholderCoverUrl.toLowerCase();
  return normalized === normalizedPlaceholder
    || normalized.includes(PLACEHOLDER_COVER_FILENAME);
}

function coverPersistKey(bookId: string, coverUrl: string): string {
  return `${bookId}::${coverUrl}`;
}

/**
 * Validates a rendered cover URL and reserves one persistence attempt per {book,url} pair.
 *
 * Mutates the caller-owned `attemptedCoverPersistKeys` Set by adding a composite key
 * so that subsequent calls for the same book and URL are rejected as duplicates.
 *
 * @returns the normalized URL if reservation succeeded, or null if ineligible or already attempted
 */
export function reserveCoverRelayCandidate(
  book: Book | null,
  renderedCoverUrl: string,
  placeholderCoverUrl: string,
  attemptedCoverPersistKeys: Set<string>,
): string | null {
  if (!book || !book.id || book.id.trim().length === 0) {
    return null;
  }

  const normalizedRenderedUrl = normalizeCoverUrl(renderedCoverUrl);
  if (!normalizedRenderedUrl) {
    return null;
  }

  if (
    hasPersistedS3Cover(book)
    || isPersistedCoverUrl(normalizedRenderedUrl)
    || isPlaceholderCoverUrl(normalizedRenderedUrl, placeholderCoverUrl)
  ) {
    return null;
  }

  const attemptKey = coverPersistKey(book.id, normalizedRenderedUrl);
  if (attemptedCoverPersistKeys.has(attemptKey)) {
    return null;
  }

  attemptedCoverPersistKeys.add(attemptKey);
  return normalizedRenderedUrl;
}

/**
 * Releases a previously reserved persistence attempt key.
 */
export function releaseCoverRelayCandidate(
  bookId: string,
  renderedCoverUrl: string,
  attemptedCoverPersistKeys: Set<string>,
): void {
  if (!bookId || bookId.trim().length === 0) {
    return;
  }
  const normalizedRenderedUrl = normalizeCoverUrl(renderedCoverUrl);
  if (!normalizedRenderedUrl) {
    return;
  }
  attemptedCoverPersistKeys.delete(coverPersistKey(bookId, normalizedRenderedUrl));
}

/**
 * Merges persisted cover metadata into the current book projection used by the detail page.
 */
export function mergePersistedCoverIntoBook(
  book: Book,
  persistedCover: CoverIngestResponse,
  fallbackRenderedUrl: string,
): Book {
  const existingCover = book.cover ?? {
    s3ImagePath: null,
    externalImageUrl: null,
    width: null,
    height: null,
    highResolution: null,
    preferredUrl: null,
    fallbackUrl: null,
    source: null,
  };

  return {
    ...book,
    cover: {
      ...existingCover,
      preferredUrl: persistedCover.storedCoverUrl,
      fallbackUrl: existingCover.fallbackUrl ?? fallbackRenderedUrl,
      s3ImagePath: persistedCover.storageKey,
      source: persistedCover.source,
      width: persistedCover.width ?? existingCover.width ?? null,
      height: persistedCover.height ?? existingCover.height ?? null,
      highResolution: persistedCover.highResolution ?? existingCover.highResolution ?? null,
    },
  };
}
