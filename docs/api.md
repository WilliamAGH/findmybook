# API Documentation

## Key Endpoints
- **Web Interface:** `http://localhost:{SERVER_PORT}` or `https://findmybook.net`
- **Health Check:** `/actuator/health`
- **Book API:**
  - `GET /api/books/search?query={keyword}`
  - `GET /api/books/{id}`
  - `GET /api/books/authors/search?query={author}`
- **Page API (Svelte SPA):**
  - `GET /api/pages/home`
  - `GET /api/pages/routes`
  - `GET /api/pages/meta?path={routePath}`
  - `GET /api/pages/sitemap?view={authors|books}&letter={A-Z|0-9}&page={n}`
  - `GET /api/pages/book/{identifier}/affiliate-links`
  - `GET /api/pages/categories/facets?limit={n}&minBooks={n}`

## Search API Contract
- `GET /api/books/search` supports:
  - `query` (required)
  - `startIndex` (default `0`)
  - `maxResults` (default `12`)
  - `orderBy` (`relevance`, `newest`, `title`, `author`)
  - `publishedYear` (optional integer year filter)
  - `coverSource` (default `ANY`)
  - `resolution` (default `ANY`)
- Unsupported `orderBy` values return `400 Bad Request`.
- Response includes deterministic pagination metadata plus `queryHash` for realtime routing.
- `GET /api/books/{identifier}` and search hit payloads include canonical description fields:
  - `description` (legacy string; retained for backward compatibility)
  - `descriptionContent` (backend-formatted source of truth):
    - `raw: string | null` (original provider/database value)
    - `format: "HTML" | "MARKDOWN" | "PLAIN_TEXT" | "UNKNOWN"`
    - `html: string` (sanitized deterministic HTML for rendering)
    - `text: string` (plain text for snippets and metadata)

## Search Pagination
- The `/api/books/search` endpoint defaults to 12 results per page.
- Returns cursor metadata: `hasMore`, `nextStartIndex`, `prefetchedCount`.
- Prefetches an additional page window to keep pagination deterministic.
- Web UI caches up to six prefetched pages in-memory.

## SPA Page Payload Contracts
- `GET /api/pages/home`
  - Response fields:
    - `currentBestsellers: BookCard[]`
    - `recentBooks: BookCard[]`
  - Home payload enforces cover-bearing cards only:
    - Placeholder/null-equivalent cover values are excluded from both arrays.
    - Entries with real cover images are returned first, preserving section order among valid covers.
- `GET /api/pages/meta?path={routePath}`
  - Query params:
    - `path` (required route path, for example `/`, `/search`, `/book/the-hobbit`)
  - Response fields:
    - `title`, `description`, `canonicalUrl`, `keywords`, `ogImage`
    - `statusCode` (semantic route status for head/error handling in SPA)
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
  - Notes: `orderBy` accepts only `relevance` or `newest`; unsupported values must be normalized server-side.
- **Open Library Search API**
  - Endpoint: `GET https://openlibrary.org/search.json`
  - Required/important params: `q`, `mode=everything`, `limit`, `fields`; optional `sort` facet when provider sorting is requested
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
- On page 1, Open Library can augment Postgres results when the page has cover gaps (for example, many no-cover placeholders).
  - Result ordering remains: Postgres results with covers first, then Open Library cover-bearing results, then lower-quality/no-cover results.
- When Postgres returns zero matches:
  - Open Library fallback runs first.
  - Google Books fallback runs immediately after only for remaining slots not filled by Open Library.
- Combined external candidates are deduplicated before persistence and response assembly.

## Admin API
Admin endpoints require HTTP Basic Authentication.
- **Username:** `admin`
- **Password:** Set via `APP_ADMIN_PASSWORD`

### Example Request
```bash
curl -u admin:$APP_ADMIN_PASSWORD -X POST "http://localhost:${SERVER_PORT}/admin/s3-cleanup/move-flagged?limit=100"
```
