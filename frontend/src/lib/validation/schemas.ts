import { z } from "zod/v4";

export const AuthorSchema = z.object({
  id: z.string().nullable().optional(),
  name: z.string(),
});

export const CollectionSchema = z.object({
  id: z.string().nullable().optional(),
  name: z.string().nullable().optional(),
  type: z.string().nullable().optional(),
  rank: z.number().nullable().optional(),
  source: z.string().nullable().optional(),
});

export const TagSchema = z.object({
  key: z.string(),
  attributes: z.record(z.string(), z.unknown()).optional().default({}),
});

export const CoverSchema = z.object({
  s3ImagePath: z.string().nullable().optional(),
  externalImageUrl: z.string().nullable().optional(),
  width: z.number().nullable().optional(),
  height: z.number().nullable().optional(),
  highResolution: z.boolean().nullable().optional(),
  preferredUrl: z.string().nullable().optional(),
  fallbackUrl: z.string().nullable().optional(),
  source: z.string().nullable().optional(),
});

export const EditionSchema = z.object({
  googleBooksId: z.string().nullable().optional(),
  type: z.string().nullable().optional(),
  identifier: z.string().nullable().optional(),
  isbn10: z.string().nullable().optional(),
  isbn13: z.string().nullable().optional(),
  publishedDate: z.union([z.string(), z.number(), z.null()]).optional(),
  coverImageUrl: z.string().nullable().optional(),
});

export const PublicationSchema = z.object({
  publishedDate: z.union([z.string(), z.number(), z.null()]).optional(),
  language: z.string().nullable().optional(),
  pageCount: z.number().nullable().optional(),
  publisher: z.string().nullable().optional(),
});

export const DescriptionContentSchema = z.object({
  raw: z.string().nullable().optional(),
  format: z.enum(["HTML", "MARKDOWN", "PLAIN_TEXT", "UNKNOWN"]).optional().default("UNKNOWN"),
  html: z.string().optional().default(""),
  text: z.string().optional().default(""),
});

export const BookAiContentSnapshotSchema = z.object({
  summary: z.string().max(2000),
  readerFit: z.string().max(1500).nullable().optional(),
  keyThemes: z.array(z.string().max(300)).optional().default([]),
  takeaways: z.array(z.string().max(300)).nullable().optional(),
  context: z.string().max(1500).nullable().optional(),
  version: z.number().int().nullable().optional(),
  generatedAt: z.string().datetime().nullable().optional(),
  model: z.string().nullable().optional(),
  provider: z.string().nullable().optional(),
});

export const ViewMetricsSchema = z.object({
  window: z.string(),
  totalViews: z.number(),
});

export const BookSchema = z.object({
  id: z.string(),
  slug: z.string().nullable().optional(),
  title: z.string().nullable().optional(),
  source: z.string().nullable().optional(),
  description: z.string().nullable().optional(),
  descriptionContent: DescriptionContentSchema.nullable().optional(),
  publication: PublicationSchema.nullable().optional(),
  authors: z.array(AuthorSchema).optional().default([]),
  categories: z.array(z.string()).optional().default([]),
  collections: z.array(CollectionSchema).optional().default([]),
  tags: z.array(TagSchema).optional().default([]),
  cover: CoverSchema.nullable().optional(),
  editions: z.array(EditionSchema).optional().default([]),
  recommendationIds: z.array(z.string()).optional().default([]),
  extras: z.record(z.string(), z.unknown()).optional().default({}),
  aiContent: BookAiContentSnapshotSchema.nullable().optional(),
  viewMetrics: ViewMetricsSchema.nullable().optional(),
});

export const SearchHitSchema = BookSchema.extend({
  matchType: z.string().nullable().optional(),
  relevanceScore: z.number().nullable().optional(),
});

export const SearchResponseSchema = z.object({
  query: z.string(),
  queryHash: z.string(),
  startIndex: z.number(),
  maxResults: z.number(),
  totalResults: z.number(),
  hasMore: z.boolean(),
  nextStartIndex: z.number(),
  prefetchedCount: z.number(),
  orderBy: z.string(),
  coverSource: z.string(),
  resolution: z.string(),
  results: z.array(SearchHitSchema),
});

export const SimilarBooksSchema = z.array(BookSchema);

export const BookCardSchema = z.object({
  id: z.string(),
  slug: z.string().nullable().optional(),
  title: z.string(),
  authors: z.array(z.string()).optional().default([]),
  cover_url: z.string().nullable().optional(),
  cover_s3_key: z.string().nullable().optional(),
  fallback_cover_url: z.string().nullable().optional(),
  average_rating: z.number().nullable().optional(),
  ratings_count: z.number().nullable().optional(),
  tags: z.record(z.string(), z.unknown()).optional().default({}),
});

export const HomePayloadSchema = z.object({
  currentBestsellers: z.array(BookCardSchema),
  recentBooks: z.array(BookCardSchema),
  popularBooks: z.array(BookCardSchema).optional().default([]),
  popularWindow: z.enum(["30d", "90d", "all"]).optional().default("30d"),
});

export const CategoryFacetSchema = z.object({
  name: z.string(),
  bookCount: z.number().int().nonnegative(),
});

export const CategoriesFacetsPayloadSchema = z.object({
  genres: z.array(CategoryFacetSchema),
  generatedAt: z.string(),
  limit: z.number().int().positive(),
  minBooks: z.number().int().nonnegative(),
});

export const OpenGraphPropertySchema = z.object({
  property: z.string(),
  content: z.string(),
});

export const PageMetadataSchema = z.object({
  title: z.string(),
  description: z.string(),
  canonicalUrl: z.string(),
  keywords: z.string(),
  ogImage: z.string(),
  robots: z.string(),
  openGraphType: z.string().optional().default("website"),
  openGraphProperties: z.array(OpenGraphPropertySchema).optional().default([]),
  structuredDataJson: z.string().optional().default(""),
  statusCode: z.number().int(),
});

export const RouteNameSchema = z.enum(["home", "search", "book", "sitemap", "explore", "categories", "notFound", "error"]);

export const RouteDefinitionSchema = z.object({
  name: RouteNameSchema,
  matchType: z.enum(["exact", "regex"]),
  pattern: z.string(),
  paramNames: z.array(z.string()),
  defaults: z.record(z.string(), z.string()),
  allowedQueryParams: z.array(z.string()),
  canonicalPathTemplate: z.string(),
});

export const RouteManifestSchema = z.object({
  version: z.number().int(),
  publicRoutes: z.array(RouteDefinitionSchema),
  passthroughPrefixes: z.array(z.string()),
});

export const SitemapBookSchema = z.object({
  id: z.string().nullable().optional(),
  slug: z.string(),
  title: z.string(),
  updatedAt: z.union([z.string(), z.number(), z.null()]).optional(),
});

export const SitemapAuthorSchema = z.object({
  authorId: z.string(),
  authorName: z.string(),
  lastModified: z.union([z.string(), z.number(), z.null()]).optional(),
  books: z.array(SitemapBookSchema),
});

export const SitemapPayloadSchema = z.object({
  viewType: z.enum(["authors", "books"]),
  activeLetter: z.string(),
  pageNumber: z.number(),
  totalPages: z.number(),
  totalItems: z.number(),
  letters: z.array(z.string()),
  baseUrl: z.string(),
  books: z.array(SitemapBookSchema).optional().default([]),
  authors: z.array(SitemapAuthorSchema).optional().default([]),
});

export const ThemePreferenceSchema = z.object({
  theme: z.string().nullable(),
  source: z.string(),
});

export const AffiliateLinksSchema = z.record(z.string(), z.string()).optional().default({});

export const SearchProgressEventSchema = z.object({
  message: z.string().optional(),
});

export const SearchResultsEventSchema = z.object({
  newResults: z.array(z.unknown()).optional().default([]),
});

export const BookCoverUpdateEventSchema = z.object({
  newCoverUrl: z.string().optional(),
});

export const BookAiContentQueueStatsSchema = z.object({
  running: z.number().int().nonnegative(),
  pending: z.number().int().nonnegative(),
  maxParallel: z.number().int().positive(),
  available: z.boolean(),
  environmentMode: z.string().optional().default("production"),
});

export const BookAiContentQueueUpdateSchema = z.union([
  z.object({
    event: z.enum(["queued", "queue"]),
    position: z.number().int().nullable().optional(),
    running: z.number().int().nonnegative(),
    pending: z.number().int().nonnegative(),
    maxParallel: z.number().int().positive(),
  }),
  z.object({
    event: z.literal("started"),
    running: z.number().int().nonnegative(),
    pending: z.number().int().nonnegative(),
    maxParallel: z.number().int().positive(),
    queueWaitMs: z.number().int().nonnegative(),
  }),
]);

export const BookAiContentMessageStartSchema = z.object({
  id: z.string(),
  model: z.string(),
  apiMode: z.string(),
});

export const BookAiContentMessageDeltaSchema = z.object({
  delta: z.string(),
});

export const BookAiContentMessageDoneSchema = z.object({
  message: z.string(),
});

export const BookAiErrorCodeSchema = z.enum([
  "identifier_required",
  "book_not_found",
  "service_unavailable",
  "queue_busy",
  "stream_timeout",
  "empty_generation",
  "degenerate_content",
  "cache_serialization_failed",
  "description_too_short",
  "enrichment_failed",
  "generation_failed",
]);

export const BookAiContentStreamErrorSchema = z.object({
  error: z.string(),
  code: BookAiErrorCodeSchema.optional().default("generation_failed"),
  retryable: z.boolean().optional().default(true),
});

export const BookAiContentModelStreamUpdateSchema = z.union([
  z.object({
    event: z.literal("message_start"),
    data: BookAiContentMessageStartSchema,
  }),
  z.object({
    event: z.literal("message_delta"),
    data: BookAiContentMessageDeltaSchema,
  }),
  z.object({
    event: z.literal("message_done"),
    data: BookAiContentMessageDoneSchema,
  }),
]);

export const BookAiContentStreamDoneSchema = z.object({
  message: z.string(),
  aiContent: BookAiContentSnapshotSchema,
});

export const RealtimeSearchHitCandidateSchema = z.object({
  id: z.string(),
  slug: z.string().optional(),
  title: z.string().optional(),
  source: z.string().optional(),
  description: z.string().nullable().optional(),
  authors: z.array(z.string()).optional().default([]),
  categories: z.array(z.string()).optional().default([]),
  publishedDate: z.union([z.string(), z.number()]).optional(),
  language: z.string().optional(),
  pageCount: z.number().optional(),
  publisher: z.string().optional(),
  cover: z
    .object({
      s3ImagePath: z.string().optional(),
      externalImageUrl: z.string().optional(),
      preferredUrl: z.string().optional(),
      fallbackUrl: z.string().optional(),
      source: z.string().optional(),
    })
    .optional(),
  matchType: z.string().optional(),
  relevanceScore: z.number().optional(),
});

export type Book = z.infer<typeof BookSchema>;
export type ViewMetrics = z.infer<typeof ViewMetricsSchema>;
export type BookAiContentSnapshot = z.infer<typeof BookAiContentSnapshotSchema>;
export type BookAiContentQueueStats = z.infer<typeof BookAiContentQueueStatsSchema>;
export type BookAiContentQueueUpdate = z.infer<typeof BookAiContentQueueUpdateSchema>;
export type BookAiContentModelStreamUpdate = z.infer<typeof BookAiContentModelStreamUpdateSchema>;
export type BookAiErrorCode = z.infer<typeof BookAiErrorCodeSchema>;
export type SearchHit = z.infer<typeof SearchHitSchema>;
export type SearchResponse = z.infer<typeof SearchResponseSchema>;
export type BookCard = z.infer<typeof BookCardSchema>;
export type HomePayload = z.infer<typeof HomePayloadSchema>;
export type CategoryFacet = z.infer<typeof CategoryFacetSchema>;
export type CategoriesFacetsPayload = z.infer<typeof CategoriesFacetsPayloadSchema>;
export type PageMetadata = z.infer<typeof PageMetadataSchema>;
export type RouteManifest = z.infer<typeof RouteManifestSchema>;
export type RouteDefinition = z.infer<typeof RouteDefinitionSchema>;
export type SitemapPayload = z.infer<typeof SitemapPayloadSchema>;
export type ThemePreference = z.infer<typeof ThemePreferenceSchema>;
