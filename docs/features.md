# Features & Operations

## UML Diagram
See [UML README](../src/main/resources/uml/README.md).

## Public Web Rendering
- Public HTML routes (`/`, `/search`, `/explore`, `/categories`, `/book/*`, `/sitemap`, `/sitemap/*`, `/404`, `/error`) are server-owned and render a server-generated SPA shell.
- The shell includes server-side SEO metadata (title, description, canonical, OpenGraph, Twitter) and hydrates the Svelte frontend.
- Book detail routes (`/book/{slug}`) emit route-specific Book JSON-LD (`@type: Book`) and OpenGraph `book:*` properties (`book:isbn`, `book:release_date`, `book:tag`) when source data is available.
- `GET /api/pages/meta` returns full route metadata for SPA transitions, including `robots`, `openGraphType`, `openGraphProperties`, and `structuredDataJson` so client-side navigation preserves crawler-facing tags.
- The shell also embeds the backend route manifest (`window.__FMB_ROUTE_MANIFEST__`), and the same contract is exposed by `GET /api/pages/routes` for SPA bootstrap.
- SPA navigation now writes typed history state for each in-app transition so the book detail back action returns to the exact prior route state (including active filters/pagination) instead of reconstructing a generic search URL.
- Search result links now include a `bookId` query hint (`/book/{slug}?bookId={id}`) so book detail pages can retry canonical API lookups by ID when slug-only lookups temporarily fail.
- Reader's Guide generation attempts backend description enrichment (Open Library + Google Books) before returning a terminal `description_too_short` stream error.
- Trailing-slash variants of page routes permanently redirect (`308`) to the canonical non-slash path with query strings preserved.
- Non-HTML crawler endpoints remain explicit and unchanged (`/book/isbn*`, `/sitemap.xml`, `/sitemap-xml/*`, `/robots.txt`).
- Static fallback HTML at `/frontend/index.html` is not served; only backend controllers provide public HTML entrypoints.

## Sitemap Generation
Trigger manual sitemap update:
```bash
curl -X POST http://localhost:8095/admin/trigger-sitemap-update
```

## S3 to Postgres Backfill
CLI migration flags were removed from application startup. The runtime now fails fast when
`--migrate.s3.books` or `--migrate.s3.lists` are provided.

Use manual SQL migration scripts and controlled data-loading steps instead of boot-time flags.

- SQL migration assets: `frontend/scripts/backfill_cover_metadata.sql`, `migrations/`, and `src/main/resources/schema.sql`.
- Schema module layout: `src/main/resources/schema.sql` is the orchestrator entrypoint and includes the ordered canonical modules under `migrations/` (`00_extensions.sql` through `40_book_ai_content.sql`).
- Data hygiene guardrails are consolidated into canonical table migrations: `migrations/10_books.sql` (book title cleanup + constraints), `migrations/14_book_authors_join.sql` (author cleanup + join dedupe + constraints), `migrations/20_book_tag_assignments.sql` (tag/assignment canonicalization), and `migrations/38_work_clustering_data_hygiene.sql` (Google clustering hygiene rebuild).
- Controlled data load: run the explicit migration tool directly:
  `node frontend/scripts/migrate-s3-to-db-v2.js --prefix books/v1/ --limit 1000`
- Verification and rollback guidance: follow `docs/troubleshooting.md` and `docs/edition_clustering.md` after each batch.

## Description Formatting Contract
- Book description transformation is backend-only.
- `/api/books/**` and `/api/books/search` expose canonical `descriptionContent.html` (sanitized) and `descriptionContent.text` (plain text).
- Frontend clients must render API-provided fields and must not run independent markdown/html transformation logic.
