# API Documentation

## Key Endpoints
- **Web Interface:** `http://localhost:{SERVER_PORT}` or `https://findmybook.net`
- **Health Check:** `/actuator/health`
- **Book API:**
  - `GET /api/books/search?query={keyword}`
  - `GET /api/books/{id}`

## Search Pagination
- The `/api/books/search` endpoint defaults to 12 results per page.
- Returns cursor metadata: `hasMore`, `nextStartIndex`, `prefetchedCount`.
- Prefetches an additional page window to keep pagination deterministic.
- Web UI caches up to six prefetched pages in-memory.

## Admin API
Admin endpoints require HTTP Basic Authentication.
- **Username:** `admin`
- **Password:** Set via `APP_ADMIN_PASSWORD`

### Example Request
```bash
curl -u admin:$APP_ADMIN_PASSWORD -X POST "http://localhost:${SERVER_PORT}/admin/s3-cleanup/move-flagged?limit=100"
```
