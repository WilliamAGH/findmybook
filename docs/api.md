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
  - `GET /api/pages/sitemap?view={authors|books}&letter={A-Z|0-9}&page={n}`
  - `GET /api/pages/book/{identifier}/affiliate-links`

## Search API Contract
- `GET /api/books/search` supports:
  - `query` (required)
  - `startIndex` (default `0`)
  - `maxResults` (default `12`)
  - `orderBy` (`relevance`, `newest`, `title`, `author`, `rating`)
  - `publishedYear` (optional integer year filter)
  - `coverSource` (default `ANY`)
  - `resolution` (default `ANY`)
- Response includes deterministic pagination metadata plus `queryHash` for realtime routing.

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

## Realtime Search Updates (WebSocket)
- STOMP endpoint: `/ws`
- Topic for progress: `/topic/search/{queryHash}/progress`
- Topic for opportunistic external candidates: `/topic/search/{queryHash}/results`
- The `queryHash` is returned by `/api/books/search` and should be used as the subscription key.

## External Book Provider Contracts (Top 4 Public APIs)
- **Google Books Volumes Search API**
  - Endpoint: `GET https://www.googleapis.com/books/v1/volumes`
  - Required/important params: `q`, `startIndex`, `maxResults` (`<=40`), `orderBy` (`relevance|newest`), `projection`, `langRestrict`
  - Notes: `orderBy` accepts only `relevance` or `newest`; unsupported values must be normalized server-side.
- **Open Library Search API**
  - Endpoint: `GET https://openlibrary.org/search.json`
  - Required/important params: one of `q|title|author|isbn`, `limit`, `offset`, `fields`
  - Notes: request explicit `fields` for stable parsing (`key,title,author_name,isbn,cover_i,first_publish_year,subject,publisher,language`).
- **Open Library Books API**
  - Endpoint: `GET https://openlibrary.org/api/books`
  - Required/important params: `bibkeys`, `format=json`, `jscmd=data|details`
  - Notes: use this for ISBN-centric hydration when search result docs need richer edition metadata.
- **Open Library Covers API**
  - Endpoint pattern: `https://covers.openlibrary.org/b/{idType}/{id}-{size}.jpg`
  - Identifiers: `idType` in `isbn|olid|id`; sizes `S|M|L`
  - Notes: treat this as a cover-only endpoint and pair with search/details APIs for bibliographic fields.

## Admin API
Admin endpoints require HTTP Basic Authentication.
- **Username:** `admin`
- **Password:** Set via `APP_ADMIN_PASSWORD`

### Example Request
```bash
curl -u admin:$APP_ADMIN_PASSWORD -X POST "http://localhost:${SERVER_PORT}/admin/s3-cleanup/move-flagged?limit=100"
```
