# External API Logging Guide

## Scope

This guide covers records emitted by active `ExternalApiLogger` call sites. Every record described here begins with `[EXTERNAL-API]`.

Provider ordering is owned by each calling search flow, so this guide does not prescribe a universal provider sequence. URL parameters named `key`, `api_key`, or `token` are masked in formatter output.

## Active Record Formats

Runtime-specific values are shown with angle brackets.

### Google Books Search Pages

```text
[EXTERNAL-API] [GoogleBooks] AUTHENTICATED ATTEMPT: SEARCH_PAGE for query='<query> start=<offset>'
[EXTERNAL-API] [GoogleBooks] UNAUTHENTICATED ATTEMPT: SEARCH_PAGE for query='<query> start=<offset>'
[EXTERNAL-API] [GoogleBooks] SUCCESS: SEARCH_PAGE returned <count> result(s) for query='<query> start=<offset>'
[EXTERNAL-API] [GoogleBooks] FAILURE: SEARCH_PAGE failed for query='<query> start=<offset>' - <reason>
```

A zero result count is emitted as a `SUCCESS` record.

### Open Library Searches

Open Library emits `SEARCH_TITLE`, `SEARCH_AUTHOR`, or `SEARCH_EVERYTHING`.

```text
[EXTERNAL-API] [OpenLibrary] UNAUTHENTICATED ATTEMPT: <operation> for query='<query>'
[EXTERNAL-API] [OpenLibrary] SUCCESS: <operation> returned <count> result(s) for query='<query> start=<offset> limit=<limit>'
```

### Google Books HTTP Records

Google search pages emit request and response records. Google volume fetches emit the response record on success.

```text
[EXTERNAL-API] [HTTP] AUTHENTICATED GET request to: <url>
[EXTERNAL-API] [HTTP] UNAUTHENTICATED GET request to: <url>
[EXTERNAL-API] [HTTP] Response: status=<status>, url=<url>, bodySize=<bytes> bytes
```

### Google Books Volume Failures

```text
[EXTERNAL-API] [GoogleBooks] FAILURE: FETCH_VOLUME failed for query='<url>' - <reason>
```

### Circuit Breaker Events

Authenticated Google calls emit:

```text
[EXTERNAL-API] [GoogleBooks] CIRCUIT-BREAKER-OPEN: Blocking authenticated call for query='<query-or-book-id>' (unauthenticated fallback will be attempted)
```

### Persistence Hydration

When externally sourced books are persisted, the batch persistence service emits:

```text
[EXTERNAL-API] [HYDRATION] <context> START: identifier='<book-id>', correlation='<context>'
[EXTERNAL-API] [HYDRATION] <context> SUCCESS: identifier='<book-id>', canonicalId='<book-id>', tier='POSTGRES_UPSERT'
```

## Tags Not Emitted by Current Call Sites

Do not rely on `[TIERED-SEARCH]`, `[SEARCH-STRATEGY]`, or `[GoogleBooks] DISABLED` records. Current production call sites do not emit them.

## Filtering Logs

### All External API Records

```bash
grep "\[EXTERNAL-API\]" application.log
tail -f application.log | grep "\[EXTERNAL-API\]"
```

### Failures

```bash
grep "\[EXTERNAL-API\].*FAILURE" application.log
```

### Google Search Pages

```bash
grep "\[EXTERNAL-API\].*SEARCH_PAGE" application.log
```

### Open Library Searches

```bash
grep "\[EXTERNAL-API\] \[OpenLibrary\]" application.log
```

### Circuit Breaker Events

```bash
grep "\[EXTERNAL-API\].*CIRCUIT-BREAKER-OPEN" application.log
```

### Hydration Events

```bash
grep "\[EXTERNAL-API\].*\[HYDRATION\]" application.log
```

## Troubleshooting

### No external results

Verify `app.features.external-fallback.enabled` in `application.yml`. Disabling the fallback does not currently emit an `[EXTERNAL-API] [GoogleBooks] DISABLED` record.

### Authenticated Google calls are blocked

Look for:

```text
[EXTERNAL-API] [GoogleBooks] CIRCUIT-BREAKER-OPEN
```

Then inspect nearby `FAILURE` records for the triggering reason.

### Open Library author searches

Look for an Open Library `SEARCH_AUTHOR` attempt. The application does not emit a `[SEARCH-STRATEGY]` record for author-query detection.

## Active Integration Points

1. **ExternalApiLogger.java** — canonical formatter and sensitive-parameter masking.
2. **GoogleApiFetcher.java** — Google search-page, volume-failure, HTTP, and circuit-breaker records.
3. **OpenLibraryBookDataService.java** — Open Library search attempt and success records.
4. **BookExternalBatchPersistenceService.java** — hydration start and success records.
