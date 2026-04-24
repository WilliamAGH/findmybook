# Configuration

## Environment Variables
Key variables in `.env`:

| Variable | Purpose |
| -------- | ------- |
| `SERVER_PORT` | App server port |
| `SPRING_DATASOURCE_*` | Database connection |
| `DATABASE_URL` / `POSTGRES_URL` / `JDBC_DATABASE_URL` | Fallback database URL inputs normalized into `spring.datasource.url` when `SPRING_DATASOURCE_URL` is not set |
| `OPENAI_API_KEY` | OpenAI-compatible API key for generation and embeddings |
| `OPENAI_BASE_URL` | OpenAI-compatible base URL for generation and embeddings |
| `OPENAI_MODEL` | Canonical inference model for AI book content and SEO metadata generation |
| `OPENAI_EMBEDDINGS_MODEL` | Canonical embeddings model for vector calculations |
| `AI_DEFAULT_MAX_PARALLEL` | Max concurrent outbound AI requests (queue executor cap) |
| `APP_AI_QUEUE_BACKGROUND_MAX_PENDING` | Max pending background ingestion AI jobs (default `100000`) |
| `APP_SEO_MAX_DESCRIPTION_LENGTH` | Fallback book meta description truncation length when no persisted SEO row exists (default `160`) |
| `APP_WEEKLY_REFRESH_ENABLED` | Enables the weekly orchestrator that runs NYT ingest + recommendation refresh |
| `APP_WEEKLY_REFRESH_CRON` | Weekly orchestrator cron expression (default `0 0 4 * * SUN`) |
| `APP_WEEKLY_REFRESH_NYT_PHASE_ENABLED` | Enables/disables the NYT phase inside the weekly orchestrator |
| `APP_WEEKLY_REFRESH_RECOMMENDATION_PHASE_ENABLED` | Enables/disables recommendation-cache refresh inside the weekly orchestrator |
| `APP_RECOMMENDATIONS_REFRESH_TTL_DAYS` | TTL days applied during full recommendation refresh (`book_recommendations.expires_at`) |
| `APP_SIMILARITY_EMBEDDINGS_ENABLED` | Enables lightweight scheduled refresh for missing/stale book similarity embeddings (default `true`) |
| `APP_SIMILARITY_EMBEDDINGS_INITIAL_DELAY_MS` | Initial delay before the embedding catch-up scheduler starts (default `30000`) |
| `APP_SIMILARITY_EMBEDDINGS_FIXED_DELAY_MS` | Delay between embedding catch-up scheduler passes (default `60000`) |
| `APP_SIMILARITY_EMBEDDINGS_REFRESH_BATCH_SIZE` | Number of candidate books inspected per scheduler pass (default `25`) |
| `APP_SIMILARITY_EMBEDDINGS_SCHEDULER_ENQUEUE_LIMIT` | Maximum embedding refresh tasks enqueued per scheduler pass (default `25`) |
| `APP_SIMILARITY_EMBEDDINGS_SCHEDULER_MAX_PENDING` | Central AI queue pending-depth ceiling that pauses scheduled embedding enqueue (default `100`) |
| `APP_NYT_SCHEDULER_STANDALONE_ENABLED` | Enables standalone NYT `@Scheduled` execution when not using the weekly orchestrator |
| `GOOGLE_BOOKS_API_KEY` | Book data source |
| `S3_*` | S3 storage (if used) |
| `S3_WRITE_ENABLED` | Enables/disables S3 cover uploads at runtime (`false` skips upload attempts) |
| `APP_ADMIN_PASSWORD` | Admin user password |
| `APP_USER_PASSWORD` | Basic user password |
| `SPRING_MVC_PROBLEMDETAILS_ENABLED` | Enables RFC 9457 Problem Details responses for MVC exception flows (`true` by default in this repo) |

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

Startup now fails fast with a clear error when database-required profiles are active and no datasource URL is configured. Set one of `SPRING_DATASOURCE_URL`, `DATABASE_URL`, `POSTGRES_URL`, or `JDBC_DATABASE_URL`. For explicit database-less startup, set `SPRING_PROFILES_ACTIVE=nodb`.

## Frontend Static Asset Caching

- The Spring resource handler serves `/frontend/**` with `Cache-Control: no-cache, must-revalidate`.
- This prevents stale SPA bundles when entry filenames remain stable (`app.js`, `app.css`).
- Browser validation happens on each request while still allowing conditional responses (`Last-Modified`/`ETag` semantics).

## SPA Shell Delivery

- Public HTML routes are served through server-generated SPA shells only (`/`, `/search`, `/explore`, `/categories`, `/book/{identifier}`, `/sitemap`, `/sitemap/{view}/{letter}/{page}`, `/404`, `/error`).
- Route SEO metadata is resolved server-side and is also available via `GET /api/pages/meta?path=...` for SPA navigation updates.
- Book detail `og:image` metadata points to the dynamic PNG endpoint `GET /api/pages/og/book/{identifier}`.
- Route matching/canonicalization rules are delivered by the backend route manifest, embedded as `window.__FMB_ROUTE_MANIFEST__` and available at `GET /api/pages/routes`.
- Trailing-slash page requests are permanently redirected (`308`) to canonical non-slash routes before security filtering; query strings are preserved.
- `/frontend/index.html` is intentionally not part of runtime static assets. If generated during frontend build, Gradle fails packaging to prevent fallback HTML reintroduction.

## SEO Image Cache

- Dynamic book OpenGraph PNG payloads use the `bookOgImages` Spring cache region.
- Default TTL is governed by the active cache manager profile (`CacheComponentsConfig`/`DevModeConfig`).
- Image responses are emitted with `Cache-Control: public, max-age=86400, s-maxage=86400, stale-while-revalidate=3600`.

## AI Queue Priorities

- Foreground Svelte-triggered AI requests are always dequeued before ingestion/background requests.
- Background ingestion jobs are bounded by `APP_AI_QUEUE_BACKGROUND_MAX_PENDING` and are dropped with explicit warnings when the cap is reached.
- Foreground page-load AI requests are not blocked by the background pending cap.

## Book Similarity Embedding Refresh

- Book similarity embeddings use the central AI queue for outbound embedding calls; foreground AI tasks keep priority over scheduled embedding refresh.
- Missing/stale work is derived from Postgres source hashes and timestamps, so container restarts do not lose outstanding embedding work.
- On-demand similar-book requests enqueue the source book for refresh, while the scheduler continuously backfills bounded missing/stale batches.
- The scheduler pauses when central AI queue pending depth reaches `APP_SIMILARITY_EMBEDDINGS_SCHEDULER_MAX_PENDING`, preventing large backlogs from filling memory faster than work drains.

## Weekly Catalog Refresh

- Weekly orchestration is driven by `app.weekly-refresh.*`.
- Default behavior runs both phases in one cron cycle:
  - NYT ingest (`NewYorkTimesBestsellerScheduler.forceProcessNewYorkTimesBestsellers(null)`).
  - Recommendation expiry refresh (`RecommendationCacheRefreshUseCase.refreshAllRecommendations()`).
- `app.nyt.scheduler.standalone-enabled` defaults to `false` so NYT ingest is not scheduled twice when weekly orchestration is enabled.
