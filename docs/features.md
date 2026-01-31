# Features & Operations

## UML Diagram
See [UML README](../src/main/resources/uml/README.md).

## Sitemap Generation
Trigger manual sitemap update:
```bash
curl -X POST http://localhost:8095/admin/trigger-sitemap-update
```

## S3 to Postgres Backfill
The app includes CLI flags to backfill S3 JSON into Postgres.

### Books
Backfill individual book JSONs (S3 prefix defaults to `books/v1/`):
```bash
./gradlew bootRun --args="--migrate.s3.books --migrate.prefix=books/v1/ --migrate.max=0 --migrate.skip=0"
```

### Lists
Backfill list JSONs (e.g., NYT lists):
```bash
./gradlew bootRun --args="--migrate.s3.lists --migrate.lists.provider=NYT --migrate.lists.prefix=lists/nyt/ --migrate.lists.max=0 --migrate.lists.skip=0"
```

**Notes:**
- `--migrate.max` ≤ 0 means no limit.
- Enrichment is idempotent.
