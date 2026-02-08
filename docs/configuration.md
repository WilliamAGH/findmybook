# Configuration

## Environment Variables
Key variables in `.env`:

| Variable | Purpose |
| -------- | ------- |
| `SERVER_PORT` | App server port |
| `SPRING_DATASOURCE_*` | Database connection |
| `SPRING_AI_OPENAI_API_KEY` | OpenAI integration |
| `GOOGLE_BOOKS_API_KEY` | Book data source |
| `S3_*` | S3 storage (if used) |
| `S3_WRITE_ENABLED` | Enables/disables S3 cover uploads at runtime (`false` skips upload attempts) |
| `APP_ADMIN_PASSWORD` | Admin user password |
| `APP_USER_PASSWORD` | Basic user password |
| `APP_ERROR_DIAGNOSTICS_INCLUDE_STACKTRACE` | Include stack traces in HTML diagnostics (`false` by default) |

## User Accounts

| Username | Role(s) | Access | Password Env Variable |
| -------- | ------- | ------ | -------------------- |
| `admin` | `ADMIN`, `USER` | All + `/admin/**` | `APP_ADMIN_PASSWORD` |
| `user` | `USER` | General features | `APP_USER_PASSWORD` |

## Database Connection
If you use a Postgres URI like `postgres://user:pass@host:port/db?sslmode=prefer`, convert it to JDBC format in `application.properties` or via environment variables:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://<host>:<port>/<db>?sslmode=prefer"
export SPRING_DATASOURCE_USERNAME="<user>"
export SPRING_DATASOURCE_PASSWORD="<pass>"
```

## Frontend Static Asset Caching

- The Spring resource handler serves `/frontend/**` with `Cache-Control: no-cache, must-revalidate`.
- This prevents stale SPA bundles when entry filenames remain stable (`app.js`, `app.css`).
- Browser validation happens on each request while still allowing conditional responses (`Last-Modified`/`ETag` semantics).

## SPA Shell Delivery

- Public HTML routes are served through a server-generated SPA shell and no longer depend on Thymeleaf templates at runtime.
- Route SEO metadata is resolved server-side and is also available via `GET /api/pages/meta?path=...` for SPA navigation updates.
- Route matching/canonicalization rules are delivered by the backend route manifest, embedded as `window.__FMB_ROUTE_MANIFEST__` and available at `GET /api/pages/routes`.
