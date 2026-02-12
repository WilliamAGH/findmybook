# Cron Schedulers & Manual Job Triggers

This document lists scheduled jobs, their configurations, and manual trigger instructions.

## Scheduled Jobs

Jobs are enabled by `@EnableScheduling` on the Spring Boot application entrypoint and defined in the `.../scheduler` package using `@Scheduled`.

| Scheduler File Path                                  | Main Method                        | Default Schedule                  | Cron Property (`application.properties`) | Description                                      |
| :--------------------------------------------------- | :--------------------------------- | :-------------------------------- | :--------------------------------------- | :----------------------------------------------- |
| `.../boot/scheduler/WeeklyCatalogRefreshScheduler.java` | `runWeeklyRefreshCycle()`       | Sunday at 4 AM (`0 0 4 * * SUN`)  | `app.weekly-refresh.cron`                | Orchestrates weekly NYT ingest + recommendation cache refresh in one job. |
| `.../scheduler/BookCacheWarmingScheduler.java`       | `warmPopularBookCaches()`          | Daily at 3 AM (`0 0 3 * * ?`)     | `app.cache.warming.cron`                 | Caches popular/recent books.                     |
| `.../scheduler/SitemapRefreshScheduler.java`         | `refreshSitemapArtifacts()`        | Hourly at :15 (`0 15 * * * *`)    | `sitemap.scheduler-cron`                 | Consolidated sitemap refresh: warms queries, uploads S3 snapshot, optional external hydration, cover warmups. |
| `.../scheduler/NewYorkTimesBestsellerScheduler.java` | `processNewYorkTimesBestsellers()` | Sunday at 4 AM (`0 0 4 * * SUN`)  | `app.nyt.scheduler.cron`                 | Ingests NYT bestsellers into canonical Postgres collections, memberships, and metadata. |

**Notes on Cron:**

- Use [Crontab.guru](https://crontab.guru/) to interpret cron expressions.
- Override defaults via specified properties or environment variables.

## Updating Schedules

- **Configurable Jobs**: Modify the `cron` property (e.g., `app.nyt.scheduler.cron`) in `application.yml` or an environment variable.
- **Hardcoded Jobs**: Edit the `cron` attribute in the `@Scheduled` annotation in the Java file and recompile.
- Weekly orchestrator phase switches:
  - `app.weekly-refresh.nyt-phase-enabled`
  - `app.weekly-refresh.recommendation-phase-enabled`
- Standalone NYT scheduler can be disabled while keeping admin/manual NYT triggers available:
  - `app.nyt.scheduler.standalone-enabled` (default `false`)

## Recommendation Cache Semantics

- Persistence table: `book_recommendations`
  - Active recommendation rows are those with `expires_at IS NULL OR expires_at > NOW()`.
  - Recommendation expiry is refreshed by `RecommendationCacheRefreshUseCase` through `RecommendationMaintenanceRepository`.
- Ingestion path for new recommendation rows:
  - `RecommendationService` computes recommendations from author/category/text strategies.
  - `BookRecommendationPersistenceService` upserts `RECOMMENDATION_PIPELINE` rows.
  - Additional rows can exist from source-prioritized strategies like `SAME_AUTHOR` and `SAME_CATEGORY`.
- API read behavior:
  - `GET /api/books/{identifier}/similar` checks active rows first.
  - If active rows are missing/stale, the API regenerates recommendations and returns refreshed rows when available.
  - If regeneration returns no refreshed rows, older persisted rows remain an explicit fallback path.

## Manual Job Triggers

Manually trigger jobs/tasks via API endpoints. Useful for testing or immediate execution.
All admin endpoints require HTTP Basic Authentication (user: `admin`, password from `APP_ADMIN_PASSWORD` env var, as defined in `.env.example`). See `README.md` for `curl` examples.

| Job / Task                               | Manual Trigger Command / Endpoint                                                                                                                     | Notes                                                                                                                                                                                             |
| :--------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Book Cache Warming**                   | `POST http://localhost:{SERVER_PORT}/admin/trigger-cache-warming`                                                                                      | Defined in `AdminController.java`. Runs `bookCacheWarmingScheduler.warmPopularBookCaches()`.                                                                                                    |
| **New York Times Bestseller Processing** | `POST http://localhost:{SERVER_PORT}/admin/trigger-nyt-bestsellers`                                                                                       | Defined in `AdminController.java`. Calls `newYorkTimesBestsellerScheduler.processNewYorkTimesBestsellers()`. Example: `dotenv run sh -c 'curl -X POST -u admin:$APP_ADMIN_PASSWORD http://localhost:${SERVER_PORT}/admin/trigger-nyt-bestsellers'` |
| **Recommendation Cache Full Refresh**    | `POST http://localhost:{SERVER_PORT}/admin/trigger-recommendation-refresh`                                                                             | Defined in `AdminController.java`. Runs `RecommendationCacheRefreshUseCase.refreshAllRecommendations()`.                                                                                        |
| **Weekly Catalog Refresh (NYT + Recommendations)** | `POST http://localhost:{SERVER_PORT}/admin/trigger-weekly-refresh`                                                                          | Defined in `AdminController.java`. Runs weekly orchestrator immediately and throws when any phase fails.                                                                                        |
| **S3 Cover Cleanup (Dry Run)**           | `GET http://localhost:{SERVER_PORT}/admin/s3-cleanup/dry-run`                                                                                             | Defined in `AdminController.java`. Optional params: `prefix`, `limit`. Example: `dotenv run sh -c 'curl -u admin:$APP_ADMIN_PASSWORD "http://localhost:${SERVER_PORT}/admin/s3-cleanup/dry-run?limit=10"'`        |
| **S3 Cover Cleanup (Move Flagged)**      | `POST http://localhost:{SERVER_PORT}/admin/s3-cleanup/move-flagged`                                                                                       | Defined in `AdminController.java`. Optional params: `prefix`, `limit`, `quarantinePrefix`. Example: `dotenv run sh -c 'curl -X POST -u admin:$APP_ADMIN_PASSWORD "http://localhost:${SERVER_PORT}/admin/s3-cleanup/move-flagged?limit=5"'` |

**Notes on Manual Triggers:**

- Replace `{SERVER_PORT}` with your application's port (e.g., 8095).
- `curl` examples use `dotenv run sh -c 'curl ...'` to load variables like `$APP_ADMIN_PASSWORD` and `$SERVER_PORT` from your `.env` file.
- If using `dotenv-cli`, the syntax is `dotenv curl ...`.
- Alternatively, export variables directly: `export APP_ADMIN_PASSWORD='your_password'`.

---

## Data Migration Utilities

### [REMOVED] S3 JSON to Redis Migration

> **Note:** Redis functionality has been removed from this application. The S3 JSON to Redis migration feature and all associated components (jsontoredis package) are no longer available.
