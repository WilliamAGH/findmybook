# Features & Operations

## UML Diagram
See [UML README](../src/main/resources/uml/README.md).

## Sitemap Generation
Trigger manual sitemap update:
```bash
curl -X POST http://localhost:8095/admin/trigger-sitemap-update
```

## S3 to Postgres Backfill
CLI migration flags were removed from application startup. The runtime now fails fast when
`--migrate.s3.books` or `--migrate.s3.lists` are provided.

Use manual SQL migration scripts and controlled data-loading steps instead of boot-time flags.

- SQL migration assets: `frontend/scripts/backfill_cover_metadata.sql` and `src/main/resources/schema.sql` (slug population helpers around `generate_all_book_slugs`).
- Controlled data load: run the explicit migration tool directly:
  `node frontend/scripts/migrate-s3-to-db-v2.js --prefix books/v1/ --limit 1000`
- Verification and rollback guidance: follow `docs/troubleshooting.md` and `docs/edition_clustering.md` after each batch.

## Description Formatting Contract
- Book description transformation is backend-only.
- `/api/books/**` and `/api/books/search` expose canonical `descriptionContent.html` (sanitized) and `descriptionContent.text` (plain text).
- Frontend clients must render API-provided fields and must not run independent markdown/html transformation logic.
