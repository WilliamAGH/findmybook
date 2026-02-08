/**
 * Search configuration constants, type aliases, and pure helper functions
 * shared across SearchPage and its extracted child components.
 */

export const PAGE_SIZE = 12;
export const PREFETCH_WINDOW_SIZE = 5;
export const CATEGORY_FACET_LIMIT = 24;
export const CATEGORY_MIN_BOOKS = 1;

export const COVER_OPTIONS = ["ANY", "GOOGLE_BOOKS", "OPEN_LIBRARY", "LONGITOOD"] as const;
export const RESOLUTION_OPTIONS = ["ANY", "HIGH_ONLY", "HIGH_FIRST"] as const;
export const SORT_OPTIONS = ["relevance", "title", "author", "newest", "rating"] as const;

export const EXPLORE_DEFAULT_QUERIES = [
  "Classic literature", "Modern thrillers", "Space opera adventures", "Historical fiction bestsellers",
  "Award-winning science fiction", "Inspiring biographies", "Mind-bending philosophy", "Beginner's cookbooks",
  "Epic fantasy sagas", "Cyberpunk futures", "Cozy mysteries", "Environmental science",
  "Artificial intelligence ethics", "World mythology", "Travel memoirs",
] as const;

export const SORT_LABELS: Record<SortOption, string> = {
  relevance: "Most Relevant",
  title: "Title A–Z",
  author: "By Author",
  newest: "Newest First",
  rating: "Highest Rated",
};

export type CoverOption = (typeof COVER_OPTIONS)[number];
export type ResolutionOption = (typeof RESOLUTION_OPTIONS)[number];
export type SortOption = (typeof SORT_OPTIONS)[number];

export function parsePositiveNumber(value: string | null, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed >= 1 ? parsed : fallback;
}

export function parseEnumParam<T extends string>(
  params: URLSearchParams, key: string, options: readonly T[], fallback: T,
): T {
  const raw = params.get(key) ?? fallback;
  return options.includes(raw as T) ? (raw as T) : fallback;
}

export function dedupeGenres(rawGenres: string[]): string[] {
  const deduped = new Set<string>();
  for (const rawGenre of rawGenres) {
    const trimmed = rawGenre.trim();
    if (trimmed.length > 0) deduped.add(trimmed);
  }
  return Array.from(deduped);
}

export function categoryQueryFromGenres(genres: string[]): string {
  return genres.join(" OR ");
}

export function pickRandomExploreQuery(): string {
  return EXPLORE_DEFAULT_QUERIES[Math.floor(Math.random() * EXPLORE_DEFAULT_QUERIES.length)];
}

export function computeTotalPages(
  totalResults: number, hasMore: boolean, currentPage: number, pageSize: number,
): number {
  const fromTotal = Math.max(1, Math.ceil(totalResults / pageSize));
  return hasMore ? Math.max(fromTotal, currentPage + 1) : fromTotal;
}

export function paginationRange(currentPage: number, total: number): (number | "ellipsis")[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
  const pages: (number | "ellipsis")[] = [1];
  if (currentPage > 3) pages.push("ellipsis");
  const start = Math.max(2, currentPage - 1);
  const end = Math.min(total - 1, currentPage + 1);
  for (let i = start; i <= end; i++) pages.push(i);
  if (currentPage < total - 2) pages.push("ellipsis");
  pages.push(total);
  return pages;
}
