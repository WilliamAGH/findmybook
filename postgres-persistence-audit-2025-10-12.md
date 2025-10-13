# PostgreSQL Persistence Service Audit - Production Deployment

**Date**: 2025-10-12
**Scope**: Comprehensive review of async book persistence and S3 cover upload workflow
**Status**: 🔴 **CRITICAL BUGS FOUND**

---

## Executive Summary

The PostgreSQL persistence service has **CRITICAL BUG REGRESSIONS** preventing books from being properly persisted with S3 cover images after external API fetches. The workflow is **BROKEN** in production, causing:

1. ❌ **NO S3 uploads** happening after book persistence
2. ❌ **Missing s3_image_path** values in book_image_links table
3. ❌ **Incomplete data** for fallback books mapped by OpenLibrary (and other non-Google sources)
4. ❌ **Broken async orchestration** between book upsert and cover upload

### Key Finding: Infrastructure Exists But Is Disconnected

**IMPORTANT**: This is NOT a case of missing infrastructure. All the S3 upload code exists and works correctly:

✅ **Working Infrastructure Found**:

- `S3BookCoverService.uploadCoverToS3Async()` - Fully functional
- `CoverPersistenceService.updateAfterS3Upload()` - Fully functional
- `LocalDiskCoverCacheService` - Properly delegates to S3
- `book_image_links` schema with `s3_image_path` column - Ready

❌ **Missing Component**:

- **Event listener** to connect book persistence → S3 upload

**Root Cause**: The workflow got **disconnected**. `BookUpsertService` emits events, but no listener triggers S3 uploads. The pieces exist but aren't wired together.

---

## Workflow Analysis

### Expected Workflow (What SHOULD Happen)

```
1. External API Fetch (Google Books)
   ↓
2. BookUpsertService.upsert(aggregate)
   ├─ Persist book to books table ✅
   ├─ Persist authors ✅
   ├─ Persist external IDs ✅
   ├─ Persist image links (metadata only) ✅
   └─ Emit BookUpsertEvent ✅
   ↓
3. [MISSING] Event listener should trigger S3 upload ❌
   ↓
4. S3BookCoverService.uploadCoverToS3Async()
   ├─ Download image from external URL
   ├─ Process image (resize, optimize)
   ├─ Upload to S3
   └─ Update book_image_links with s3_image_path ❌
```

### Actual Workflow (What IS Happening)

```
1. External API Fetch (Google Books)
   ↓
2. BookUpsertService.upsert(aggregate)
   ├─ Persist book to books table ✅
   ├─ Persist authors ✅
   ├─ Persist external IDs ✅
   ├─ Persist image links (external URLs only) ✅
   └─ Emit BookUpsertEvent ✅
   ↓
3. CoverUpdateNotifierService.handleBookUpsert()
   └─ Send WebSocket notification only ✅
   ↓
4. ❌ WORKFLOW STOPS HERE - NO S3 UPLOAD TRIGGERED
```

---

## Critical Bug #0: OpenLibrary Fallback Persistence Drops Books

**Location**: `BookDataOrchestrator.java:631-652`, `GoogleBooksMapper.java:38-89`, `OpenLibraryBookDataService.java:347-384`
**Severity**: 🔴 **CRITICAL**
**Impact**: Books fetched from OpenLibrary (and any fallback source lacking `volumeInfo`) never reach PostgreSQL

### Problem

`persistBooksAsync()` accepts `Book` instances from Google and OpenLibrary pipelines. When the source JSON comes from OpenLibrary, `googleBooksMapper.map(sourceJson)` returns `null` because OpenLibrary payloads do not include a `volumeInfo` node. This short-circuits the persistence flow and increments the failure counter, so fallback books never get written to Postgres or queued for cover processing.

### Code Evidence

```java
// BookDataOrchestrator.java:631-646
BookAggregate aggregate = googleBooksMapper.map(sourceJson);
if (aggregate == null) {
    logger.warn("GoogleBooksMapper returned null for book {}", book.getId());
    return false;
}
bookUpsertService.upsert(aggregate);
```

```java
// GoogleBooksMapper.java:38-59
if (json == null || !json.has("volumeInfo")) {
    log.warn("Invalid Google Books JSON: missing volumeInfo");
    return null;
}
```

```java
// OpenLibraryBookDataService.java:347-363
book.setExternalImageUrl(thumbnailUrl);
book.setRawJsonResponse(bookDataNode.toString());
```

### Expected Behaviour

The orchestrator should convert OpenLibrary responses into a `BookAggregate` using the existing `OpenLibraryBookDataService` data or a normaliser that can translate the stored `rawJsonResponse`. Once the aggregate is created, `BookUpsertService` already handles persistence. No new infrastructure is required—only reuse of existing mapper utilities (`BookDomainMapper`, `CategoryNormalizer`, `BookAggregate` DTO).

### Retrofit Plan

- Extend `BookDataOrchestrator.persistBook` to detect non-Google payloads and route them through a lightweight OpenLibrary-to-aggregate mapper (leveraging `BookDomainMapper.fromAggregate` + `BookAggregate.builder()` to avoid duplicate logic).
- Ensure the new mapper lives alongside existing utilities (e.g., `BookDomainMapper`) rather than introducing a new class; add concise Javadoc explaining the fallback path to prevent future regressions.
- Add unit coverage in `BookDataOrchestratorPersistenceScenariosTest` to prove OpenLibrary payloads persist successfully.


## Critical Bug #1: Missing S3 Upload Wiring In Existing Listener

**Location**: `CoverUpdateNotifierService.java:179-205`, `OutboxRelay.java`, `CoverPersistenceService.java`

**Severity**: 🔴 **CRITICAL**
**Impact**: High-resolution book covers are never uploaded to S3

### Problem

There is **NO `@EventListener` implementation that reuses the existing services to trigger S3 uploads** after `BookUpsertService` emits a `BookUpsertEvent`. The only listener, `CoverUpdateNotifierService.handleBookUpsert`, forwards payloads to WebSocket subscribers and exits.

**IMPORTANT DISCOVERY**: The S3 upload infrastructure DOES exist and is fully functional:

✅ **Existing Working Components**:

- `S3BookCoverService.uploadCoverToS3Async()` - Fully functional S3 upload
- `CoverPersistenceService.updateAfterS3Upload()` - Working update method (lines 162-204)
- `LocalDiskCoverCacheService` - Properly delegates to S3 pipeline (lines 86-97)
- `book_image_links` table with `s3_image_path` column - Schema ready

❌ **Missing Wiring**:

- `CoverUpdateNotifierService` should be retrofitted to branch into the S3 pipeline instead of creating a brand new listener class. No new files are required—only extend the existing listener with concise orchestration logic and Javadoc.

### Code Evidence

```java
// CoverUpdateNotifierService.java:179-194
@EventListener
public void handleBookUpsert(BookUpsertEvent event) {
    if (event == null || event.getBookId() == null) {
        logger.warn("Received BookUpsertEvent with null content");
        return;
    }
    String destination = "/topic/book/" + event.getBookId() + "/upsert";
    Map<String, Object> payload = new HashMap<>();
    payload.put("bookId", event.getBookId());
    payload.put("slug", event.getSlug());
    payload.put("title", event.getTitle());
    payload.put("isNew", event.isNew());
    payload.put("context", event.getContext());
    logger.info("Sending book upsert to {}: {} (new={})", destination, event.getTitle(), event.isNew());
    this.messagingTemplate.convertAndSend(destination, payload);
    // ← Retrofit: trigger S3 upload workflow here using existing services
}
```

**The code only sends WebSocket notifications - NO S3 UPLOAD**

### Existing Infrastructure That Must Be Reused

```java
// CoverPersistenceService.java:162-204 - already persists S3 metadata
@Transactional
public PersistenceResult updateAfterS3Upload(
    UUID bookId,
    String s3Key,
    String s3CdnUrl,
    Integer width,
    Integer height,
    CoverImageSource source
) {
    upsertImageLink(bookId, "canonical", canonicalUrl, source.name(), width, height, highRes, s3Key);
    log.info("Updated cover metadata for book {} after S3 upload: {} ({}x{}, highRes={})",
        bookId, s3Key, width, height, highRes);
    return new PersistenceResult(true, canonicalUrl, width, height, highRes);
}
```

```java
// S3BookCoverService.java - uploadCoverToS3Async() already handles upload + processing
Mono<ImageDetails> upload = s3BookCoverService
    .uploadCoverToS3Async(imageUrl, bookIdForLog, normalizedSource, provenanceData)
    .doOnSuccess(details -> handleSuccessfulUpload(details, attemptInfo, sourceNameEnum));
```

### Root Cause

The infrastructure is **DISCONNECTED**. All the pieces exist but `CoverUpdateNotifierService` stops at notifications. Retrofitting that class (and documenting the behaviour with succinct Javadoc) restores the pipeline without introducing new files.

---

## Critical Bug #2: BookUpsertEvent Missing Cover Image Data

**Location**: `BookUpsertEvent.java:1-28`
**Severity**: 🔴 **CRITICAL**
**Impact**: Even if an event listener existed, it has no cover image URL to upload

### Problem

`BookUpsertEvent` only contains:

- `bookId` (String)
- `slug` (String)
- `title` (String)
- `isNew` (boolean)
- `context` (String)

**Missing**:

- ❌ `coverImageUrl` - External URL to download
- ❌ `imageLinks` - Map of all image variants
- ❌ `source` - Image source provider

### Code Evidence

```java
// BookUpsertEvent.java:1-28
public class BookUpsertEvent {
    private final String bookId;
    private final String slug;
    private final String title;
    private final boolean isNew;
    private final String context;

    // NO COVER IMAGE DATA
}
```

### Fix Required

```java
public class BookUpsertEvent {
    private final String bookId;
    private final String slug;
    private final String title;
    private final boolean isNew;
    private final String context;

    // ADD THESE:
    private final Map<String, String> imageLinks;
    private final String source;
    private final String externalCoverUrl;
}
```

---

## Critical Bug #3: BookUpsertService Does Not Emit Complete Events

**Location**: `BookUpsertService.java:644-668`
**Severity**: 🟡 **HIGH**
**Impact**: Event emission happens but without cover image data

### Problem

`BookUpsertService.emitOutboxEvent()` creates an event with minimal data:

```java
// BookUpsertService.java:644-668
private void emitOutboxEvent(UUID bookId, String slug, String title, boolean isNew) {
    try {
        String topic = "/topic/book." + bookId;

        Map<String, Object> payload = Map.of(
            "bookId", bookId.toString(),
            "slug", slug != null ? slug : "",
            "title", title != null ? title : "",
            "isNew", isNew,
            "timestamp", System.currentTimeMillis()
        );

        String payloadJson = objectMapper.writeValueAsString(payload);

        jdbcTemplate.update(
            "INSERT INTO events_outbox (topic, payload, created_at) VALUES (?, ?::jsonb, NOW())",
            topic,
            payloadJson
        );

        log.debug("Emitted outbox event for book {}", bookId);
    } catch (Exception e) {
        log.warn("Failed to emit outbox event for book {}: {}", bookId, e.getMessage());
    }
}
```

**Missing**:

- Cover image URLs
- Source provider
- Image links map

---

## Critical Bug #4: CoverPersistenceService Only Persists Metadata

**Location**: `CoverPersistenceService.java:521-544`
**Severity**: 🟡 **HIGH**
**Impact**: `book_image_links` table gets external URLs but never S3 paths

### Problem

`CoverPersistenceService.persistFromGoogleImageLinks()` is called during book upsert but:

1. ✅ Persists external URLs to `book_image_links.url`
2. ✅ Estimates dimensions
3. ❌ **NEVER** triggers S3 upload
4. ❌ **NEVER** calls `updateAfterS3Upload()`

### Code Evidence

```java
// CoverPersistenceService.java:76-145
@Transactional
public PersistenceResult persistFromGoogleImageLinks(UUID bookId, Map<String, String> imageLinks, String source) {
    // ... processes all image types ...

    // Persists to book_image_links with NULL s3_image_path
    upsertImageLink(bookId, imageType, httpsUrl, source,
        estimate.width(), estimate.height(), estimate.highRes());

    // NO S3 UPLOAD TRIGGERED HERE

    return new PersistenceResult(true, resolved.url(), resolved.width(), resolved.height(), resolved.highResolution());
}
```

### What's Missing

After persisting image metadata, there should be:

```java
// MISSING CODE:
CompletableFuture.runAsync(() -> {
    s3BookCoverService.uploadCoverToS3Async(canonicalCoverUrl, bookId.toString(), source)
        .subscribe(imageDetails -> {
            coverPersistenceService.updateAfterS3Upload(
                bookId,
                imageDetails.getStorageKey(),
                imageDetails.getUrlOrPath(),
                imageDetails.getWidth(),
                imageDetails.getHeight(),
                CoverImageSource.fromString(source)
            );
        });
});
```

---

## Critical Bug #5: S3 Write Disabled in Development Profile

**Location**: `application.yml:222-223`
**Severity**: 🟡 **MODERATE**
**Impact**: S3 uploads disabled in dev environment (intentional but needs documentation)

### Configuration

```yaml
# application.yml:222-223
s3:
  write-enabled: false  # DEV OVERRIDE
```

**Analysis**:

- ✅ Intentional for development safety
- ⚠️ Must ensure production uses `s3.write-enabled: true`
- ⚠️ No fallback logging when write-enabled=false

### Recommendation

Add explicit logging when S3 writes are disabled:

```java
if (!s3WriteEnabled) {
    log.warn("S3 write disabled for book {} - cover will not be persisted to S3. Enable with S3_WRITE_ENABLED=true", bookId);
    // Return placeholder instead of silently failing
}
```

---

## Critical Bug #6: Missing Async Orchestration Between Upsert and Upload

**Location**: Architecture-level issue
**Severity**: 🔴 **CRITICAL**
**Impact**: No coordination between book persistence and S3 upload

### Current Architecture Problem

```
BookUpsertService
    └─ Persists book ✅
    └─ Emits event ✅

[GAP - NO COORDINATION]

S3BookCoverService
    └─ Has upload methods ✅
    └─ Never called ❌
```

### Expected Architecture

```
BookUpsertService
    └─ Persists book ✅
    └─ Emits BookUpsertEvent ✅

BookCoverUploadListener (MISSING)
    └─ @EventListener(BookUpsertEvent)
    └─ Extract cover URL from book_image_links
    └─ S3BookCoverService.uploadCoverToS3Async()
    └─ CoverPersistenceService.updateAfterS3Upload()
    └─ Emit BookCoverUpdatedEvent
```

---

## Critical Bug #7: Book Data Orchestrator's persistBooksAsync() Not Wired Up

**Location**: `BookDataOrchestrator.java:443-510`
**Severity**: 🟡 **HIGH**
**Impact**: Search results persistence missing S3 upload step

### Problem

`BookDataOrchestrator.persistBooksAsync()` calls `persistBook()` which:

1. ✅ Converts `Book` to `BookAggregate`
2. ✅ Calls `BookUpsertService.upsert()`
3. ❌ Does NOT trigger S3 upload
4. ❌ Books from search results never get S3 covers

### Code Evidence

```java
// BookDataOrchestrator.java:631-652
private boolean persistBook(Book book, JsonNode sourceJson) {
    try {
        com.williamcallahan.book_recommendation_engine.dto.BookAggregate aggregate =
            googleBooksMapper.map(sourceJson);

        if (aggregate == null) {
            logger.warn("GoogleBooksMapper returned null for book {}", book.getId());
            return false;
        }

        // SSOT for writes
        bookUpsertService.upsert(aggregate);  // ← NO S3 UPLOAD AFTER THIS
        triggerSearchViewRefresh(false);
        logger.debug("Persisted book via BookUpsertService: {}", book.getId());
        return true;
    } catch (Exception e) {
        logger.error("Error persisting via BookUpsertService for book {}: {}",
            book.getId(), e.getMessage(), e);
        return false;
    }
}
```

### Expected Flow

After `bookUpsertService.upsert()`, there should be:

```java
// Extract cover URL from aggregate
if (aggregate.getIdentifiers() != null && aggregate.getIdentifiers().getImageLinks() != null) {
    Map<String, String> imageLinks = aggregate.getIdentifiers().getImageLinks();
    String coverUrl = findBestCoverUrl(imageLinks);

    if (coverUrl != null) {
        // Trigger async S3 upload
        s3BookCoverService.uploadCoverToS3Async(coverUrl, book.getId(), aggregate.getIdentifiers().getSource())
            .subscribe(/* handle result */);
    }
}
```

---

## Database State Analysis

### book_image_links Table Schema

```sql
-- book_image_links columns:
- id (text, primary key)
- book_id (uuid, foreign key to books)
- image_type (text, e.g., 'thumbnail', 'large', 'canonical')
- url (text, external URL from provider)
- s3_image_path (text, NULL for all new books) ← PROBLEM
- source (text, provider name)
- width (integer, estimated or actual)
- height (integer, estimated or actual)
- is_high_resolution (boolean)
- created_at (timestamptz)
```

### Current State

```sql
-- Expected for a properly persisted book:
SELECT book_id, image_type, url, s3_image_path FROM book_image_links WHERE book_id = '<some_uuid>';

-- ACTUAL RESULT (buggy):
book_id  | image_type | url                                      | s3_image_path
---------|------------|------------------------------------------|---------------
<uuid>   | canonical  | https://books.googleusercontent.com/...  | NULL  ← BUG
<uuid>   | large      | https://books.googleusercontent.com/...  | NULL  ← BUG
<uuid>   | thumbnail  | https://books.googleusercontent.com/...  | NULL  ← BUG

-- EXPECTED RESULT (fixed):
book_id  | image_type | url                                      | s3_image_path
---------|------------|------------------------------------------|------------------------------
<uuid>   | canonical  | https://books.googleusercontent.com/...  | books/<uuid>/google-books.jpg
<uuid>   | large      | https://books.googleusercontent.com/...  | books/<uuid>/google-books.jpg
<uuid>   | thumbnail  | https://books.googleusercontent.com/...  | books/<uuid>/google-books.jpg
```

---

## Services Involved (Architecture Map)

```
┌─────────────────────────────────────────────────────────────────┐
│ EXTERNAL API FETCH LAYER                                        │
├─────────────────────────────────────────────────────────────────┤
│ BookDataOrchestrator.fetchFromApisAndAggregate()               │
│   ├─ Calls GoogleApiFetcher                                     │
│   ├─ Maps JSON to BookAggregate via GoogleBooksMapper          │
│   └─ Calls BookUpsertService.upsert(aggregate) ✅              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ PERSISTENCE LAYER                                                │
├─────────────────────────────────────────────────────────────────┤
│ BookUpsertService.upsert(BookAggregate)                         │
│   ├─ Persists to books table ✅                                │
│   ├─ Persists to authors + book_authors_join ✅                │
│   ├─ Persists to book_external_ids ✅                          │
│   ├─ Calls CoverPersistenceService.persistFromGoogleImageLinks()│
│   │   └─ Persists to book_image_links (URL only) ✅           │
│   └─ Emits BookUpsertEvent (via emitOutboxEvent) ✅            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ EVENT RELAY LAYER                                                │
├─────────────────────────────────────────────────────────────────┤
│ OutboxRelay.relayEvents()                                       │
│   └─ Polls events_outbox table every 1 second                  │
│   └─ Publishes to WebSocket via SimpMessagingTemplate ✅       │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ WEBSOCKET NOTIFICATION LAYER                                     │
├─────────────────────────────────────────────────────────────────┤
│ CoverUpdateNotifierService.handleBookUpsert()                   │
│   └─ Receives BookUpsertEvent from Spring Events               │
│   └─ Sends WebSocket notification to /topic/book/{id}/upsert ✅│
└─────────────────────────────────────────────────────────────────┘
                              ↓
                    ❌ WORKFLOW ENDS HERE

┌─────────────────────────────────────────────────────────────────┐
│ S3 UPLOAD LAYER (NEVER REACHED)                                 │
├─────────────────────────────────────────────────────────────────┤
│ S3BookCoverService.uploadCoverToS3Async() ❌                   │
│   ├─ Downloads image from external URL                         │
│   ├─ Processes image (resize, optimize)                        │
│   └─ Uploads to S3                                             │
│                                                                  │
│ CoverPersistenceService.updateAfterS3Upload() ❌               │
│   └─ Updates book_image_links with s3_image_path               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Summary of All Bugs

| # | Bug Description | Severity | Impact | Location |
|---|-----------------|----------|--------|----------|
| 1 | Missing S3 upload event listener | 🔴 CRITICAL | No S3 uploads happen | N/A (missing file) |
| 2 | BookUpsertEvent missing cover image data | 🔴 CRITICAL | Cannot trigger S3 upload | BookUpsertEvent.java |
| 3 | BookUpsertService emits incomplete events | 🟡 HIGH | Event lacks image data | BookUpsertService.java:644-668 |
| 4 | CoverPersistenceService only persists metadata | 🟡 HIGH | s3_image_path always NULL | CoverPersistenceService.java:76-145 |
| 5 | S3 writes disabled in dev profile | 🟡 MODERATE | No uploads in development | application.yml:222-223 |
| 6 | Missing async orchestration | 🔴 CRITICAL | No coordination layer | Architecture-level |
| 7 | persistBooksAsync() not wired to S3 | 🟡 HIGH | Search results incomplete | BookDataOrchestrator.java:631-652 |

---

## Impact Assessment

### User-Facing Impact

- ❌ Books fetched from external APIs have **no high-resolution covers** in S3
- ❌ Users see **external CDN URLs** (books.googleusercontent.com) instead of fast S3 CDN
- ❌ **Slower page loads** due to external image fetching
- ❌ **Unreliable images** if Google Books changes/removes cover URLs
- ❌ Books are **not fully cached** (image data not persisted)

### System Impact

- ❌ `book_image_links.s3_image_path` column is **always NULL** for new books
- ❌ S3 storage is **underutilized** (images not being uploaded)
- ❌ **Wasted work** - metadata persisted but images never uploaded
- ❌ **Incomplete data** in PostgreSQL (breaks analytics/reporting)

### Production Severity

**🔴 CRITICAL** - Core functionality completely broken. Books are being persisted without S3 covers, violating the expected workflow.

---

## Recommended Fixes (Priority Order)

### Fix #1: Create BookCoverUploadListener (HIGHEST PRIORITY)

**File**: `src/main/java/com/williamcallahan/book_recommendation_engine/service/event/BookCoverUploadListener.java`

```java
@Service
@Slf4j
public class BookCoverUploadListener {

    private final S3BookCoverService s3BookCoverService;
    private final CoverPersistenceService coverPersistenceService;
    private final JdbcTemplate jdbcTemplate;

    public BookCoverUploadListener(
        S3BookCoverService s3BookCoverService,
        CoverPersistenceService coverPersistenceService,
        JdbcTemplate jdbcTemplate
    ) {
        this.s3BookCoverService = s3BookCoverService;
        this.coverPersistenceService = coverPersistenceService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBookUpsert(BookUpsertEvent event) {
        if (event == null || event.getBookId() == null) {
            return;
        }

        UUID bookId = UUID.fromString(event.getBookId());

        // Fetch canonical cover URL from book_image_links
        String coverUrl = jdbcTemplate.query(
            """
            SELECT url FROM book_image_links
            WHERE book_id = ? AND image_type = 'canonical'
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("url") : null,
            bookId
        );

        if (coverUrl == null || coverUrl.isBlank()) {
            log.debug("No cover URL found for book {}", bookId);
            return;
        }

        String source = jdbcTemplate.query(
            """
            SELECT source FROM book_external_ids
            WHERE book_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("source") : "UNKNOWN",
            bookId
        );

        log.info("Triggering S3 upload for book {} from source {}", bookId, source);

        // Trigger async S3 upload
        s3BookCoverService.uploadCoverToS3Async(coverUrl, bookId.toString(), source)
            .subscribe(
                imageDetails -> {
                    log.info("S3 upload successful for book {}: {}", bookId, imageDetails.getStorageKey());

                    // Update book_image_links with S3 path
                    coverPersistenceService.updateAfterS3Upload(
                        bookId,
                        imageDetails.getStorageKey(),
                        imageDetails.getUrlOrPath(),
                        imageDetails.getWidth(),
                        imageDetails.getHeight(),
                        imageDetails.getSource()
                    );
                },
                error -> log.error("S3 upload failed for book {}: {}", bookId, error.getMessage(), error)
            );
    }
}
```

### Fix #2: Update BookUpsertEvent with Cover Data

**File**: `BookUpsertEvent.java`

```java
public class BookUpsertEvent {
    private final String bookId;
    private final String slug;
    private final String title;
    private final boolean isNew;
    private final String context;

    // ADD THESE:
    private final Map<String, String> imageLinks;
    private final String source;

    public BookUpsertEvent(String bookId, String slug, String title, boolean isNew,
                          String context, Map<String, String> imageLinks, String source) {
        this.bookId = bookId;
        this.slug = slug;
        this.title = title;
        this.isNew = isNew;
        this.context = context;
        this.imageLinks = imageLinks;
        this.source = source;
    }

    public Map<String, String> getImageLinks() { return imageLinks; }
    public String getSource() { return source; }
}
```

### Fix #3: Update BookUpsertService to Emit Complete Events

**File**: `BookUpsertService.java:644-668`

```java
private void emitOutboxEvent(UUID bookId, String slug, String title, boolean isNew,
                             Map<String, String> imageLinks, String source) {
    try {
        String topic = "/topic/book." + bookId;

        Map<String, Object> payload = Map.of(
            "bookId", bookId.toString(),
            "slug", slug != null ? slug : "",
            "title", title != null ? title : "",
            "isNew", isNew,
            "timestamp", System.currentTimeMillis(),
            "imageLinks", imageLinks != null ? imageLinks : Map.of(),
            "source", source != null ? source : "UNKNOWN"
        );

        String payloadJson = objectMapper.writeValueAsString(payload);

        jdbcTemplate.update(
            "INSERT INTO events_outbox (topic, payload, created_at) VALUES (?, ?::jsonb, NOW())",
            topic,
            payloadJson
        );

        log.debug("Emitted outbox event for book {}", bookId);
    } catch (Exception e) {
        log.warn("Failed to emit outbox event for book {}: {}", bookId, e.getMessage());
    }
}
```

### Fix #4: Add S3 Write Logging

**File**: `S3BookCoverService.java:596-610`

Add logging when S3 writes are disabled:

```java
if (!s3WriteEnabled) {
    log.warn("S3 write disabled for book {} (key {}). Set S3_WRITE_ENABLED=true to enable uploads.",
        bookId, s3Key);
    // ... rest of existing code
}
```

---

## Testing Plan

### Unit Tests Required

1. `BookCoverUploadListenerTest` - Test event handling
2. `BookUpsertServiceTest` - Verify complete events emitted
3. `CoverPersistenceServiceTest` - Verify updateAfterS3Upload() works

### Integration Tests Required

1. End-to-end test: External API → Upsert → S3 Upload → DB Update
2. Verify `book_image_links.s3_image_path` populated after upload
3. Test async orchestration with actual S3 (or localstack)

### Production Validation

```sql
-- Check if S3 paths are being populated:
SELECT
  COUNT(*) as total_books,
  COUNT(CASE WHEN s3_image_path IS NOT NULL THEN 1 END) as with_s3,
  COUNT(CASE WHEN s3_image_path IS NULL THEN 1 END) as without_s3
FROM book_image_links
WHERE image_type = 'canonical'
AND created_at > NOW() - INTERVAL '24 hours';

-- Should see with_s3 increasing after fix deployment
```

---

## Conclusion

The PostgreSQL persistence service has **CRITICAL REGRESSIONS** preventing S3 cover uploads after book persistence. The root cause is a **missing event listener** that should trigger S3 uploads after `BookUpsertEvent` is emitted.

**Immediate Action Required**:

1. ✅ Implement `BookCoverUploadListener`
2. ✅ Update `BookUpsertEvent` with image data
3. ✅ Update `BookUpsertService` event emission
4. ✅ Add comprehensive logging
5. ✅ Deploy to production
6. ✅ Monitor `book_image_links` table for s3_image_path population

**Estimated Fix Time**: 2-4 hours
**Deployment Risk**: Low (additive changes, no breaking changes)
**Rollback Plan**: Disable `BookCoverUploadListener` via feature flag

---

**Audit Completed By**: Claude Code (AI Assistant)
**Review Status**: ⏳ Awaiting human approval
**Next Steps**: Implement fixes in priority order
