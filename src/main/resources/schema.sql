-- Canonical schema orchestrator.
-- Applies deterministic, deduplicated SQL modules from migrations/.
-- A durable marker lets an interrupted empty bootstrap resume both author
-- phases without mistaking an existing database for a fresh one. Existing
-- databases receive only the additive phase; migration 53 remains an explicit
-- quiesced cutover operation.

\set ON_ERROR_STOP on

SELECT pg_advisory_lock(hashtextextended('findmybook.author-contract-rollout', 0));

DO $schema_bootstrap$
DECLARE
  authors_existed_at_entry boolean := to_regclass('public.authors') IS NOT NULL;
  author_join_existed_at_entry boolean := to_regclass('public.book_authors_join') IS NOT NULL;
  bootstrap_marker_existed_at_entry boolean :=
    to_regclass('public.findmybook_schema_bootstrap_state') IS NOT NULL;
  public_schema_had_relations_at_entry boolean := EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class AS existing_relation
    JOIN pg_catalog.pg_namespace AS existing_namespace
      ON existing_namespace.oid = existing_relation.relnamespace
    WHERE existing_namespace.nspname = 'public'
  );
BEGIN
  IF NOT authors_existed_at_entry
    AND author_join_existed_at_entry
    AND NOT bootstrap_marker_existed_at_entry
  THEN
    RAISE EXCEPTION 'book_authors_join exists without authors or a bootstrap marker';
  END IF;

  IF authors_existed_at_entry
    AND NOT author_join_existed_at_entry
    AND NOT bootstrap_marker_existed_at_entry
  THEN
    RAISE EXCEPTION 'existing database is missing book_authors_join; migration 14 will not be replayed';
  END IF;

  IF NOT authors_existed_at_entry
    AND NOT bootstrap_marker_existed_at_entry
    AND public_schema_had_relations_at_entry
  THEN
    RAISE EXCEPTION 'public schema contains relations but authors is absent; refusing fresh-bootstrap classification';
  END IF;

  IF NOT authors_existed_at_entry
    AND NOT bootstrap_marker_existed_at_entry
  THEN
    CREATE TABLE public.findmybook_schema_bootstrap_state (
      bootstrap_singleton boolean PRIMARY KEY CHECK (bootstrap_singleton),
      bootstrap_phase text NOT NULL CHECK (bootstrap_phase = 'fresh-bootstrap-in-progress')
    );
    INSERT INTO public.findmybook_schema_bootstrap_state (
      bootstrap_singleton,
      bootstrap_phase
    ) VALUES (true, 'fresh-bootstrap-in-progress');
  END IF;
END
$schema_bootstrap$;

DO $schema_bootstrap$
DECLARE
  bootstrap_marker regclass := to_regclass('public.findmybook_schema_bootstrap_state');
  authors_have_rows boolean := false;
BEGIN
  IF bootstrap_marker IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1
      FROM pg_catalog.pg_class AS marker_relation
      JOIN pg_catalog.pg_namespace AS marker_namespace
        ON marker_namespace.oid = marker_relation.relnamespace
      WHERE marker_relation.oid = bootstrap_marker
        AND marker_relation.relkind = 'r'
        AND marker_namespace.nspname = 'public'
        AND marker_relation.relname = 'findmybook_schema_bootstrap_state'
    ) OR (
      SELECT array_agg(
        marker_attribute.attname || ':'
          || pg_catalog.format_type(marker_attribute.atttypid, marker_attribute.atttypmod)
          || ':' || marker_attribute.attnotnull::text
        ORDER BY marker_attribute.attnum
      )
      FROM pg_catalog.pg_attribute AS marker_attribute
      WHERE marker_attribute.attrelid = bootstrap_marker
        AND marker_attribute.attnum > 0
        AND NOT marker_attribute.attisdropped
    ) IS DISTINCT FROM ARRAY[
      'bootstrap_singleton:boolean:true',
      'bootstrap_phase:text:true'
    ]::text[] THEN
      RAISE EXCEPTION 'fresh-bootstrap marker has an unexpected definition';
    END IF;

    IF (SELECT count(*) FROM public.findmybook_schema_bootstrap_state) <> 1
      OR NOT EXISTS (
        SELECT 1
        FROM public.findmybook_schema_bootstrap_state
        WHERE bootstrap_singleton
          AND bootstrap_phase = 'fresh-bootstrap-in-progress'
      ) THEN
      RAISE EXCEPTION 'fresh-bootstrap marker has unexpected state';
    END IF;

    IF to_regclass('public.authors') IS NOT NULL THEN
      EXECUTE 'SELECT EXISTS (SELECT 1 FROM public.authors)'
        INTO authors_have_rows;
      IF authors_have_rows THEN
        RAISE EXCEPTION 'fresh-bootstrap retry found author data and will not auto-contract it';
      END IF;
    END IF;
  END IF;
END
$schema_bootstrap$;

SELECT
  to_regclass('public.findmybook_schema_bootstrap_state') IS NOT NULL
    AS canonical_author_fresh_bootstrap
\gset

DO $author_contract_detection$
DECLARE
  contract_applied boolean := false;
BEGIN
  IF to_regprocedure('public.canonical_author_contract_is_applied()') IS NOT NULL THEN
    EXECUTE 'SELECT public.canonical_author_contract_is_applied()'
      INTO contract_applied;
    IF to_regclass('public.uq_authors_normalized_name') IS NOT NULL
      AND NOT contract_applied
    THEN
      RAISE EXCEPTION 'canonical author contract index has an unexpected definition';
    END IF;
    IF contract_applied
      AND (
        to_regprocedure('public.normalize_author_name(text)') IS NOT NULL
        OR to_regprocedure('public.merge_duplicate_authors()') IS NOT NULL
      )
    THEN
      RAISE EXCEPTION 'canonical author contract has retired legacy functions present';
    END IF;
  ELSIF to_regclass('public.uq_authors_normalized_name') IS NOT NULL THEN
    RAISE EXCEPTION 'canonical author contract index exists without its exact validator';
  END IF;

  PERFORM set_config(
    'findmybook.canonical_author_contract_applied',
    contract_applied::text,
    false
  );
END
$author_contract_detection$;

SELECT current_setting('findmybook.canonical_author_contract_applied')
  AS canonical_author_contract_applied
\gset

\ir ../../../migrations/00_extensions.sql
\ir ../../../migrations/10_books.sql
\ir ../../../migrations/11_book_external_ids.sql
\ir ../../../migrations/12_authors.sql
\ir ../../../migrations/13_author_external_ids.sql
\if :canonical_author_fresh_bootstrap
\echo 'Fresh bootstrap marker active; migration 14 is safe to converge because authors are empty.'
\ir ../../../migrations/14_book_authors_join.sql
\else
\echo 'Existing database detected; destructive historical migration 14 will not replay.'
\endif
\ir ../../../migrations/15_work_clusters.sql
\ir ../../../migrations/16_work_cluster_members.sql
\ir ../../../migrations/17_book_work_editions_view.sql
\ir ../../../migrations/18_book_tags.sql
\ir ../../../migrations/19_book_tag_equivalents.sql
\ir ../../../migrations/20_book_tag_assignments.sql
\ir ../../../migrations/21_book_raw_data.sql
\ir ../../../migrations/22_book_dimensions.sql
\ir ../../../migrations/23_book_image_links.sql
\ir ../../../migrations/24_book_collections.sql
\ir ../../../migrations/25_book_collections_join.sql
\ir ../../../migrations/26_book_search_view.sql
\ir ../../../migrations/27_search_functions.sql
\ir ../../../migrations/28_slug_functions.sql
\ir ../../../migrations/29_book_recommendations.sql
\ir ../../../migrations/30_events_outbox.sql
\ir ../../../migrations/31_book_slug_redirect.sql
\if :canonical_author_contract_applied
\echo 'Canonical author contract already applied; migration 32 remains retired.'
\else
\ir ../../../migrations/32_author_normalization_functions.sql
\endif
\ir ../../../migrations/33_slug_migration_helpers.sql
\ir ../../../migrations/34_work_clustering_isbn_functions.sql
\ir ../../../migrations/35_work_clustering_google_single_function.sql
\ir ../../../migrations/36_work_clustering_primary_trigger_and_editions.sql
\ir ../../../migrations/37_work_clustering_google_batch_and_stats.sql
\ir ../../../migrations/38_work_clustering_data_hygiene.sql
\ir ../../../migrations/39_title_author_clustering_functions.sql
\ir ../../../migrations/40_book_ai_content.sql
\ir ../../../migrations/41_book_ai_content_nullable_reader_fit.sql
\ir ../../../migrations/42_book_image_links_grayscale.sql
\ir ../../../migrations/43_cover_image_priority_function.sql
\ir ../../../migrations/44_recent_book_views.sql
\ir ../../../migrations/45_page_view_events.sql
\ir ../../../migrations/46_propagate_grayscale_to_siblings.sql
\ir ../../../migrations/47_book_seo_metadata.sql
\ir ../../../migrations/48_book_ai_content_quality_guards.sql
\ir ../../../migrations/49_category_dedup_cleanup.sql
\ir ../../../migrations/50_book_similarity_embeddings.sql
\ir ../../../migrations/51_book_similarity_hybrid_contract.sql
\ir ../../../migrations/52_canonical_author_upsert.sql
\if :canonical_author_contract_applied
DO $retired_slug_contract_verification$
BEGIN
  IF to_regprocedure('public.ensure_unique_slug(text)') IS NOT NULL
    OR to_regprocedure('public.generate_slug(text,text)') IS NOT NULL
  THEN
    RAISE EXCEPTION 'canonical author contract has retired slug functions present';
  END IF;
END
$retired_slug_contract_verification$;
\endif
\if :canonical_author_fresh_bootstrap
\ir ../../../migrations/53_contract_canonical_author_identity.sql
DO $author_contract_verification$
BEGIN
  IF NOT public.canonical_author_contract_is_applied()
    OR to_regprocedure('public.normalize_author_name(text)') IS NOT NULL
    OR to_regprocedure('public.merge_duplicate_authors()') IS NOT NULL
    OR to_regprocedure('public.ensure_unique_slug(text)') IS NOT NULL
    OR to_regprocedure('public.generate_slug(text,text)') IS NOT NULL
  THEN
    RAISE EXCEPTION 'fresh bootstrap did not establish the canonical author and slug contracts';
  END IF;
END
$author_contract_verification$;
DROP TABLE public.findmybook_schema_bootstrap_state;
\else
\echo 'Migration 53 deferred: quiesce writes, drain old writers, deploy new callers while quiesced, then run make db-contract-author-identity.'
\endif

SELECT pg_advisory_unlock(hashtextextended('findmybook.author-contract-rollout', 0));
