# API Documentation

## Key Endpoints
- **Web Interface:** `http://localhost:{SERVER_PORT}` or `https://findmybook.net`
- **Health Check:** `/actuator/health`
- **Book API:**
  - `GET /api/books/search?query={keyword}`
  - `GET /api/books/{id}`
  - `GET /api/books/ai/content/queue`
  - `POST /api/books/{identifier}/ai/content/stream?refresh={true|false}`
  - `GET /api/books/authors/search?query={author}`
- **Page API (Svelte SPA):**
  - `GET /api/pages/home?popularWindow={30d|90d|all}&popularLimit={n}`
  - `GET /api/pages/routes`
  - `GET /api/pages/meta?path={routePath}`
  - `GET /api/pages/sitemap?view={authors|books}&letter={A-Z|0-9}&page={n}`
  - `GET /api/pages/book/{identifier}/affiliate-links`
  - `GET /api/pages/categories/facets?limit={n}&minBooks={n}`

## Public HTML Route Contract
- Canonical page routes:
  - `/`
  - `/search`
  - `/explore`
  - `/categories`
  - `/book/{identifier}`
  - `/sitemap`
  - `/sitemap/{view}/{letter}/{page}`
  - `/404`
  - `/error`
- Trailing-slash variants of page routes redirect with `308 Permanent Redirect` to the non-slash canonical URL.
- Redirects preserve original query strings (including encoded values and pagination/search params).
- SPA book links may include `bookId` as an optional query parameter (`/book/{identifier}?bookId={canonicalOrExternalId}`) for client-side fallback when slug-only detail lookups return `404`.
- Crawler and API routes are not slash-aliased and remain explicit (`/api/**`, `/robots.txt`, `/sitemap.xml`, `/sitemap-xml/**`).
- Runtime does not serve `/frontend/index.html`; frontend assets are static files only (`/frontend/app.js`, `/frontend/app.css`, icons, manifest).

## Error Response Contract
- MVC exception flows use RFC 9457 Problem Details (`application/problem+json`) via Spring Boot/Spring MVC global handling.
- Fallback `/error` handling remains centralized in Spring Boot’s global error endpoint.
- HTML error rendering is centralized through the shared SPA shell metadata pipeline.
- `POST /api/theme` invalid payloads return `400 application/problem+json`.
- `GET /r/{source}/{externalId}` returns `404 application/problem+json` when `source` is unsupported.
- `GET /sitemap-xml/books/{page}.xml` and `GET /sitemap-xml/authors/{page}.xml` return `404` when page is out of range; default content negotiation emits RFC 9457 Problem Details for the error body.
- Admin endpoint validation/runtime failures return typed Problem Details (`400`/`500`) instead of ad-hoc text error bodies.

## Search API Contract
- `GET /api/books/search` supports:
  - `query` (required)
  - `startIndex` (default `0`; **zero-based absolute offset**, not a one-based page number)
  - `maxResults` (default `12`)
  - `orderBy` (`relevance`, `newest`, `title`, `author`)
  - `publishedYear` (optional integer year filter)
  - `coverSource` (default `ANY`)
  - `resolution` (default `ANY`)
- `GET /api/books/{identifier}` and `GET /api/books/slug/{slug}` support:
  - `viewWindow` (optional, one of `30d`, `90d`, `all`)
  - Invalid `viewWindow` returns `400 Bad Request`.
- Unsupported `orderBy` values return `400 Bad Request`.
- Response includes deterministic pagination metadata plus `queryHash` for realtime routing.
- Search result ordering always applies cover tier first:
  - color covers first,
  - grayscale covers after color,
  - no-cover entries last.
- `GET /api/books/{identifier}` and search hit payloads include canonical description fields:
  - `description` (legacy string; retained for backward compatibility)
  - `descriptionContent` (backend-formatted source of truth):
    - `raw: string | null` (original provider/database value)
    - `format: "HTML" | "MARKDOWN" | "PLAIN_TEXT" | "UNKNOWN"`
    - `html: string` (sanitized deterministic HTML for rendering)
    - `text: string` (plain text for snippets and metadata)
- `GET /api/books/{identifier}` also includes optional AI content metadata:
  - `aiContent` (nullable object)
    - `summary: string`
    - `readerFit: string`
    - `keyThemes: string[]`
    - `version: number | null`
    - `generatedAt: ISO-8601 datetime | null`
    - `model: string | null`
    - `provider: string | null`
- Unknown `GET /api/books/{identifier}` lookups return `404 application/problem+json`
  (RFC 9457 Problem Details).
- Successful `GET /api/books/{identifier}` and `GET /api/books/slug/{slug}` responses append a
  `recent_book_views` event used by recently viewed and rolling view analytics.
- When `viewWindow` is requested, detail payloads include:
  - `viewMetrics` (nullable object)
    - `window: "30d" | "90d" | "all"`
    - `totalViews: number`

## Book AI Content Streaming Contract
- `GET /api/books/ai/content/queue`
  - Response fields:
    - `running: number`
    - `pending: number`
    - `maxParallel: number`
    - `available: boolean`
    - `environmentMode: string` (`development`, `production`, or `test`)
- `POST /api/books/{identifier}/ai/content/stream`
  - Query params:
    - `refresh` (`false` by default; when `false`, cached Postgres AI snapshot is returned when present)
  - Response content type:
    - `text/event-stream`
  - SSE events:
    - `queued`: `{ position, running, pending, maxParallel }`
    - `queue`: periodic queue position update while pending
    - `started`: `{ running, pending, maxParallel, queueWaitMs }`
    - `message_start`: `{ id, model, apiMode }`
    - `message_delta`: `{ delta }`
    - `message_done`: `{ message }`
    - `done`: `{ message, aiContent }` where `aiContent` matches the `book.aiContent` contract
    - `error`: `{ error, code, retryable }`
      - `code` values include:
        - `identifier_required`
        - `book_not_found`
        - `service_unavailable`
        - `queue_busy`
        - `stream_timeout`
        - `empty_generation`
        - `cache_serialization_failed`
        - `description_too_short` (emitted only after canonical description enrichment attempts from Open Library and Google Books still fail to satisfy minimum content requirements)
        - `enrichment_failed` (emitted when book description enrichment providers are unavailable)
        - `generation_failed`

## Search Pagination
- The `/api/books/search` endpoint defaults to 12 results per page.
- Route-level UI query params may use `page=1,2,3...`; clients must convert with:
  - `startIndex = (page - 1) * maxResults`
  - `page = floor(startIndex / maxResults) + 1`
- The backend search API itself is offset-based and does not use Spring Data `Pageable`/`PageRequest`.
- Returns cursor metadata: `hasMore`, `nextStartIndex`, `prefetchedCount`.
- Prefetches an additional page window to keep pagination deterministic.
- Web UI caches up to six prefetched pages in-memory.

## SPA Page Payload Contracts
- `GET /api/pages/home`
  - Query params:
    - `popularWindow` (optional; `30d`, `90d`, `all`; default `30d`)
    - `popularLimit` (optional; defaults `8`, maximum `24`)
  - Response fields:
    - `currentBestsellers: BookCard[]`
    - `recentBooks: BookCard[]`
    - `popularBooks: BookCard[]`
    - `popularWindow: string`
  - Side effect:
    - Each request appends a `page_view_events` row with `page_key = "homepage"`.
  - Home payload enforces cover-bearing cards only:
    - Placeholder/null-equivalent cover values are excluded.
    - Grayscale covers are excluded.
    - All home sections (`currentBestsellers`, `recentBooks`, `popularBooks`) emit color covers only.
- `GET /api/pages/meta?path={routePath}`
  - Query params:
    - `path` (required route path, for example `/`, `/search`, `/book/the-hobbit`)
    - Missing/blank `path` returns `400 application/problem+json` (RFC 9457 Problem Details).
  - Response fields:
    - `title`, `description`, `canonicalUrl`, `keywords`, `ogImage`, `robots`
    - `openGraphType` (for example `website` or `book`)
    - `openGraphProperties: Array<{ property, content }>` (route-specific OG extensions such as `book:*`)
    - `structuredDataJson` (JSON-LD payload for route rich-result metadata)
    - `statusCode` (semantic route status for head/error handling in SPA)
  - Special routes:
    - `path=/error` returns error metadata with `statusCode=500`.
- `GET /api/pages/routes`
  - Response fields:
    - `version: number`
    - `publicRoutes: Array<{ name, matchType, pattern, paramNames, defaults, allowedQueryParams, canonicalPathTemplate }>`
    - `passthroughPrefixes: string[]`
  - Contract purpose:
    - Defines route matching/canonical behavior once on the backend.
    - The SPA router consumes the same manifest (embedded at shell render time and available from this endpoint).
- `GET /api/pages/sitemap`
  - Query params:
    - `view` (`authors` or `books`, default `authors`)
    - `letter` (bucket `A-Z` or `0-9`)
    - `page` (1-indexed)
  - Response fields:
    - `viewType`, `activeLetter`, `pageNumber`, `totalPages`, `totalItems`, `letters`, `baseUrl`
    - `books: SitemapBookPayload[]` (books view)
    - `authors: SitemapAuthorPayload[]` (authors view)
- `GET /api/pages/book/{identifier}/affiliate-links`
  - Returns a `Record<string, string>` of retailer label -> URL
  - Unknown identifiers return `404 application/problem+json` (RFC 9457 Problem Details).
- `GET /api/pages/categories/facets`
  - Query params:
    - `limit` (optional, defaults `24`, max `200`)
    - `minBooks` (optional, defaults `1`, minimum `0`)
  - Response fields:
    - `genres: Array<{ name: string, bookCount: number }>`
    - `generatedAt: string` (ISO timestamp)
    - `limit: number`
    - `minBooks: number`

## Realtime Search Updates (WebSocket)
- STOMP endpoint: `/ws`
- Topic for progress: `/topic/search/{queryHash}/progress`
- Topic for opportunistic external candidates: `/topic/search/{queryHash}/results`
- The `queryHash` is returned by `/api/books/search` and should be used as the subscription key.
- Provider priority for opportunistic enrichment:
  - Open Library is the primary external provider.
  - Google Books runs in parallel for realtime enrichment and contributes additional candidates when available.
  - Provider failures are isolated; one provider failure does not terminate the other provider stream.

## External Book Provider Contracts (Top 4 Public APIs)
- **Google Books Volumes Search API**
  - Endpoint: `GET https://www.googleapis.com/books/v1/volumes`
  - Required/important params: `q`, `startIndex`, `maxResults` (`<=40`), `orderBy` (`relevance|newest`), `projection`, `langRestrict`
  - Pagination basis: `startIndex` is a zero-based absolute offset.
  - Notes: `orderBy` accepts only `relevance` or `newest`; unsupported values must be normalized server-side.
- **Open Library Search API**
  - Endpoint: `GET https://openlibrary.org/search.json`
  - Required/important params: `q`, `mode=everything`, `offset` or `page`, `limit`, `fields`; optional `sort` facet when provider sorting is requested
  - Pagination basis: `offset` is zero-based; `page` is one-based.
  - Notes: backend search uses OpenLibrary web-style query semantics (`q` + `mode=everything`) with explicit fields (`key,title,author_name,isbn,cover_i,first_publish_year,number_of_pages_median,subject,publisher,language,first_sentence`); `orderBy=newest` maps to Open Library `sort=new`, while unsupported provider sort facets are handled by backend re-sorting.
- **Open Library Books API**
  - Endpoint: `GET https://openlibrary.org/api/books`
  - Required/important params: `bibkeys`, `format=json`, `jscmd=data|details`
  - Notes: use this for ISBN-centric hydration when search result docs need richer edition metadata.
- **Open Library Covers API**
  - Endpoint pattern: `https://covers.openlibrary.org/b/{idType}/{id}-{size}.jpg`
  - Identifiers: `idType` in `isbn|olid|id`; sizes `S|M|L`
  - Notes: treat this as a cover-only endpoint and pair with search/details APIs for bibliographic fields.

## External Fallback Ordering (Synchronous Search)
- Open Library is the primary fallback provider for synchronous search windows.
- Google Books is the secondary fallback provider when the requested search window still underfills after Open Library.
- Fallback evaluation is offset-window aware: page-2+ requests use the same offset-based search contract (`startIndex`/`maxResults`) and can still receive external supplementation when needed.
- Combined external candidates are deduplicated before persistence and response assembly.

## Admin API
Admin endpoints require HTTP Basic Authentication.
- **Username:** `admin`
- **Password:** Set via `APP_ADMIN_PASSWORD`

- `POST /admin/backfill/covers`
  - Query params:
    - `mode` (`missing`, `grayscale`, `rejected`; default `missing`)
    - `limit` (default `100`, clamped to `1..10000`)
  - Response:
    - `202 Accepted` text acknowledgement when backfill starts
    - `409 Conflict` when a run is already active
- `GET /admin/backfill/covers/status`
  - Response fields:
    - `totalCandidates`, `processed`, `coverFound`, `noCoverFound`, `running`
    - `currentBookId`, `currentBookTitle`, `currentBookIsbn`
    - `currentBookAttempts: Array<{ source, outcome, detail, attemptedAt }>`
    - `lastCompletedBookId`, `lastCompletedBookTitle`, `lastCompletedBookIsbn`
    - `lastCompletedBookFound`
    - `lastCompletedBookAttempts: Array<{ source, outcome, detail, attemptedAt }>`
  - Notes:
    - `outcome` values are `SUCCESS`, `NOT_FOUND`, `FAILURE`, `SKIPPED`.
    - `currentBookId` is retained for compatibility with existing polling clients.

### Example Request
```bash
curl -u admin:$APP_ADMIN_PASSWORD -X POST "http://localhost:${SERVER_PORT}/admin/s3-cleanup/move-flagged?limit=100"
```
