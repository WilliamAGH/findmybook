# Troubleshooting

## Port Conflicts
If port 8095 is in use:

**macOS/Linux:**
```bash
kill -9 $(lsof -ti :8095)
```

**Windows:**
```bash
FOR /F "tokens=5" %i IN ('netstat -ano ^| findstr :8095') DO taskkill /F /PID %i
```

## Debugging Overrides

**Bypass Caches:**
```properties
googlebooks.api.override.bypass-caches=true
```

**Bypass Rate Limiter:**
```properties
resilience4j.ratelimiter.instances.googleBooksServiceRateLimiter.limitForPeriod=2147483647
resilience4j.ratelimiter.instances.googleBooksServiceRateLimiter.limitRefreshPeriod=1ms
resilience4j.ratelimiter.instances.googleBooksServiceRateLimiter.timeoutDuration=0ms
```

## S3 Upload Disabled

When `S3_WRITE_ENABLED=false`, book upsert events still publish websocket/outbox notifications,
but cover uploads are intentionally skipped.

- Expected behavior: no S3 upload retries are executed for those events.
- Enable uploads by setting `S3_WRITE_ENABLED=true` with valid S3 credentials/configuration.

## Work Clustering Failure: `check_reasonable_member_count`

If logs show:

- `cluster_books_by_google_canonical()`
- `check_reasonable_member_count`
- failing row with `google_canonical_id` shown as `\1`

the database function definition is stale and extracting Google canonical IDs incorrectly.

Apply the latest schema functions:

```bash
make db-migrate
```

Then rerun clustering:

```bash
make cluster-books
```

During this failure mode, the scheduler skips Google canonical clustering for that cycle and continues ISBN clustering.
