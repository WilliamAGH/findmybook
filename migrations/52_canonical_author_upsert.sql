-- MIGRATION PREREQUISITE: schema through 51, pgcrypto, and the Unicode-aware
-- "und-x-icu" collation.
-- HUMAN-ONLY: apply these exact reviewed bytes directly to each target database.
-- DEPLOYMENT ORDER: apply and verify 52, quiesce every author write, drain all
-- prior writers, deploy the new callers while writes remain quiesced, then
-- apply migration 53 before author writes resume. Old direct-DML writers do
-- not acquire this procedure's advisory locks, so mixed-version author traffic
-- is not a safe cutover mode.
-- ROLLBACK: prior binaries remain schema-compatible with 52 while writes stay
-- quiesced; do not resume mixed old/new author traffic.
-- Canonical cross-language author persistence.
-- Runtime and migration callers pass raw names only. PostgreSQL owns author
-- display cleanup, normalized keys, deduplication, lock order, IDs, and joins.
-- This additive callable surface is safe for currently deployed binaries; apply
-- it before deploying callers that invoke public.upsert_book_authors.

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE OR REPLACE FUNCTION public.canonical_author_name(raw_author_name text)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
  canonical_name text;
  name_part text;
  lower_part text;
  canonical_parts text[] := ARRAY[]::text[];
BEGIN
  IF raw_author_name IS NULL THEN
    RETURN NULL;
  END IF;

  canonical_name := normalize(raw_author_name, NFC);
  canonical_name := translate(canonical_name, E'\t\n\r\f\v', '     ');
  canonical_name := regexp_replace(
    canonical_name COLLATE "und-x-icu", '[[:space:]]+', ' ', 'g');

  IF canonical_name COLLATE "und-x-icu" ~
    E'[\\u0001-\\u001F\\u007F-\\u009F\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]'
  THEN
    RETURN NULL;
  END IF;

  canonical_name := btrim(canonical_name);
  canonical_name := btrim(regexp_replace(canonical_name, '^["''“”‘’‚‛«»‹›]+', '', 'g'));
  canonical_name := btrim(regexp_replace(
    canonical_name COLLATE "und-x-icu",
    '^[^[:alnum:]]+',
    '',
    'g'
  ));
  canonical_name := btrim(regexp_replace(canonical_name, '["''“”‘’‚‛«»‹›]+$', '', 'g'));

  IF canonical_name ~ E'\\]\\.$' THEN
    canonical_name := btrim(regexp_replace(canonical_name, E'\\.$', ''));
  END IF;
  canonical_name := btrim(regexp_replace(canonical_name, E'[,;:·\\]]+$', ''));

  IF canonical_name = '' THEN
    RETURN NULL;
  END IF;

  IF NOT (
    (canonical_name = upper(canonical_name COLLATE "und-x-icu")
      AND canonical_name <> lower(canonical_name COLLATE "und-x-icu"))
    OR (canonical_name = lower(canonical_name COLLATE "und-x-icu")
      AND canonical_name <> upper(canonical_name COLLATE "und-x-icu"))
  ) THEN
    RETURN canonical_name;
  END IF;

  FOREACH name_part IN ARRAY regexp_split_to_array(canonical_name, E'\\s+')
  LOOP
    lower_part := lower(name_part COLLATE "und-x-icu");
    canonical_parts := array_append(
      canonical_parts,
      CASE
        WHEN lower_part LIKE 'mc%' AND char_length(name_part) > 2
          THEN 'Mc' || upper(substr(name_part, 3, 1) COLLATE "und-x-icu")
            || lower(substr(name_part, 4) COLLATE "und-x-icu")
        WHEN lower_part LIKE 'mac%' AND char_length(name_part) > 3
          THEN 'Mac' || upper(substr(name_part, 4, 1) COLLATE "und-x-icu")
            || lower(substr(name_part, 5) COLLATE "und-x-icu")
        WHEN lower_part LIKE 'o''%' AND char_length(name_part) > 2
          THEN 'O''' || upper(substr(name_part, 3, 1) COLLATE "und-x-icu")
            || lower(substr(name_part, 4) COLLATE "und-x-icu")
        WHEN lower_part IN ('von', 'van', 'de', 'del', 'della', 'di')
          THEN lower_part
        ELSE upper(substr(name_part, 1, 1) COLLATE "und-x-icu")
          || lower(substr(name_part, 2) COLLATE "und-x-icu")
      END
    );
  END LOOP;

  RETURN normalize(array_to_string(canonical_parts, ' '), NFC);
END;
$$;

COMMENT ON FUNCTION public.canonical_author_name(text) IS
  'Canonical display-name policy used by every author write path';

CREATE OR REPLACE FUNCTION public.canonical_author_normalized_name(author_name text)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
  normalized_name text;
BEGIN
  IF author_name IS NULL OR btrim(author_name) = '' THEN
    RETURN NULL;
  END IF;

  normalized_name := lower(normalize(author_name, NFC) COLLATE "und-x-icu");
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+jr\\.?(?:\\s|$)', ' jr', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+sr\\.?(?:\\s|$)', ' sr', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+ph\\.?d\\.?(?:\\s|$)', ' phd', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+m\\.?d\\.?(?:\\s|$)', ' md', 'gi');
  normalized_name := regexp_replace(normalized_name, E'''s(?:\\s|$)', '', 'g');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+inc\\.?(?:\\s|$)', ' inc', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+corp\\.?(?:\\s|$)', ' corp', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+ltd\\.?(?:\\s|$)', ' ltd', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+llc\\.?(?:\\s|$)', ' llc', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\[from old catalog\\]', '', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\(editor\\)', '', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\(author\\)', '', 'gi');
  normalized_name := regexp_replace(
    normalized_name COLLATE "und-x-icu",
    '[[:punct:]“”‘’‚‛«»‹›·]',
    ' ',
    'g'
  );
  normalized_name := btrim(regexp_replace(
    normalized_name COLLATE "und-x-icu", E'\\s+', ' ', 'g'));

  RETURN normalize(NULLIF(normalized_name, ''), NFC);
END;
$$;

COMMENT ON FUNCTION public.canonical_author_normalized_name(text) IS
  'Unicode-preserving canonical author identity key';

CREATE OR REPLACE FUNCTION public.canonical_author_contract_is_applied()
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class AS index_relation
    JOIN pg_catalog.pg_index AS index_state
      ON index_state.indexrelid = index_relation.oid
    JOIN pg_catalog.pg_class AS indexed_relation
      ON indexed_relation.oid = index_state.indrelid
    JOIN pg_catalog.pg_namespace AS indexed_namespace
      ON indexed_namespace.oid = indexed_relation.relnamespace
    JOIN pg_catalog.pg_am AS access_method
      ON access_method.oid = index_relation.relam
    JOIN pg_catalog.pg_attribute AS normalized_name_attribute
      ON normalized_name_attribute.attrelid = indexed_relation.oid
      AND normalized_name_attribute.attname = 'normalized_name'
      AND NOT normalized_name_attribute.attisdropped
    JOIN pg_catalog.pg_opclass AS operator_class
      ON operator_class.oid = index_state.indclass[0]
    JOIN pg_catalog.pg_namespace AS operator_namespace
      ON operator_namespace.oid = operator_class.opcnamespace
    WHERE index_relation.oid = to_regclass('public.uq_authors_normalized_name')
      AND index_relation.relkind = 'i'
      AND indexed_namespace.nspname = 'public'
      AND indexed_relation.relname = 'authors'
      AND access_method.amname = 'btree'
      AND index_state.indisunique
      AND NOT index_state.indisprimary
      AND NOT index_state.indisexclusion
      AND index_state.indimmediate
      AND index_state.indisvalid
      AND index_state.indisready
      AND index_state.indislive
      AND NOT index_state.indnullsnotdistinct
      AND index_state.indnkeyatts = 1
      AND index_state.indnatts = 1
      AND index_state.indexprs IS NULL
      AND index_state.indkey[0] = normalized_name_attribute.attnum
      AND index_state.indcollation[0] = normalized_name_attribute.attcollation
      AND index_state.indoption[0] = 0
      AND operator_namespace.nspname = 'pg_catalog'
      AND operator_class.opcname = 'text_ops'
      AND pg_catalog.pg_get_expr(index_state.indpred, index_state.indrelid)
        = '(normalized_name IS NOT NULL)'
  )
$$;

COMMENT ON FUNCTION public.canonical_author_contract_is_applied() IS
  'Validates the exact canonical normalized-name unique partial index contract';

CREATE OR REPLACE FUNCTION public.canonical_author_candidates(raw_author_names text[])
RETURNS TABLE(name text, normalized_name text, "position" integer)
LANGUAGE sql
IMMUTABLE
AS $$
  WITH raw_authors AS (
    SELECT
      source_ordinal,
      public.canonical_author_name(raw_name) AS name
    FROM unnest(raw_author_names) WITH ORDINALITY AS input(raw_name, source_ordinal)
  ),
  valid_authors AS (
    SELECT
      source_ordinal,
      name,
      public.canonical_author_normalized_name(name) AS normalized_name
    FROM raw_authors
    WHERE name IS NOT NULL
  ),
  deduplicated_authors AS (
    SELECT DISTINCT ON (normalized_name)
      source_ordinal,
      name,
      normalized_name
    FROM valid_authors
    WHERE normalized_name IS NOT NULL
    ORDER BY normalized_name, source_ordinal
  )
  SELECT
    name,
    normalized_name,
    (row_number() OVER (ORDER BY source_ordinal) - 1)::integer AS position
  FROM deduplicated_authors
  ORDER BY source_ordinal
$$;

COMMENT ON FUNCTION public.canonical_author_candidates(text[]) IS
  'Projects raw author names into the one canonical identity and source-position contract';

CREATE OR REPLACE FUNCTION public.generate_author_relation_id(id_length integer)
RETURNS text
LANGUAGE plpgsql
VOLATILE
STRICT
AS $$
DECLARE
  alphabet constant text := '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
  generated_id text := '';
  random_byte integer;
BEGIN
  IF id_length < 1 OR id_length > 64 THEN
    RAISE EXCEPTION 'author relation ID length must be between 1 and 64';
  END IF;

  WHILE char_length(generated_id) < id_length
  LOOP
    random_byte := get_byte(gen_random_bytes(1), 0);
    IF random_byte < 248 THEN
      generated_id := generated_id || substr(alphabet, (random_byte % 62) + 1, 1);
    END IF;
  END LOOP;

  RETURN generated_id;
END;
$$;

COMMENT ON FUNCTION public.generate_author_relation_id(integer) IS
  'Generates unbiased Base62 IDs for canonical author and book-author rows';

CREATE OR REPLACE PROCEDURE public.upsert_book_authors(
  target_book_id uuid,
  raw_author_names text[]
)
LANGUAGE plpgsql
AS $$
DECLARE
  maximum_author_count constant integer := 64;
  maximum_author_name_characters constant integer := 512;
  maximum_author_name_bytes constant integer := 2048;
  candidate record;
  resolved_author_id text;
  resolved_display_name text;
  resolved_author_ids text[] := ARRAY[]::text[];
  resolved_positions integer[] := ARRAY[]::integer[];
  resolved_relation record;
  canonical_contract_applied boolean := public.canonical_author_contract_is_applied();
BEGIN
  IF target_book_id IS NULL THEN
    RAISE EXCEPTION 'target_book_id is required';
  END IF;

  IF raw_author_names IS NULL OR cardinality(raw_author_names) = 0 THEN
    RETURN;
  END IF;

  IF cardinality(raw_author_names) > maximum_author_count THEN
    RAISE EXCEPTION USING
      ERRCODE = '22023',
      MESSAGE = format('author count must not exceed %s', maximum_author_count);
  END IF;

  IF EXISTS (
    SELECT 1
    FROM unnest(raw_author_names) AS raw_author_name
    WHERE raw_author_name IS NOT NULL
      AND (
        char_length(raw_author_name) > maximum_author_name_characters
        OR octet_length(raw_author_name) > maximum_author_name_bytes
      )
  ) THEN
    RAISE EXCEPTION USING
      ERRCODE = '22023',
      MESSAGE = format(
        'author names must not exceed %s characters or %s bytes',
        maximum_author_name_characters,
        maximum_author_name_bytes
      );
  END IF;

  FOR candidate IN
    SELECT candidates.normalized_name
    FROM public.canonical_author_candidates(raw_author_names) AS candidates
    ORDER BY candidates.normalized_name
  LOOP
    PERFORM pg_advisory_xact_lock(
      hashtextextended('findmybook.author:' || candidate.normalized_name, 0)
    );
  END LOOP;

  IF canonical_contract_applied THEN
    PERFORM 1
    FROM public.authors AS existing_authors
    JOIN public.canonical_author_candidates(raw_author_names) AS candidates
      ON existing_authors.normalized_name = candidates.normalized_name
    ORDER BY candidates.normalized_name, existing_authors.name, existing_authors.id
    FOR UPDATE OF existing_authors;
  ELSE
    PERFORM 1
    FROM public.authors AS existing_authors
    JOIN public.canonical_author_candidates(raw_author_names) AS candidates
      ON public.canonical_author_normalized_name(
        public.canonical_author_name(existing_authors.name)
      ) = candidates.normalized_name
    ORDER BY candidates.normalized_name, existing_authors.name, existing_authors.id
    FOR UPDATE OF existing_authors;
  END IF;

  FOR candidate IN
    SELECT candidates.name, candidates.normalized_name, candidates.position
    FROM public.canonical_author_candidates(raw_author_names) AS candidates
    ORDER BY candidates.normalized_name, candidates.name
  LOOP
    IF canonical_contract_applied THEN
      SELECT existing_authors.id, existing_authors.name
      INTO resolved_author_id, resolved_display_name
      FROM public.authors AS existing_authors
      WHERE existing_authors.normalized_name = candidate.normalized_name
      ORDER BY
        CASE WHEN existing_authors.name = candidate.name THEN 0 ELSE 1 END,
        existing_authors.created_at NULLS FIRST,
        existing_authors.id
      LIMIT 1
      FOR UPDATE OF existing_authors;
    ELSE
      SELECT existing_authors.id, existing_authors.name
      INTO resolved_author_id, resolved_display_name
      FROM public.authors AS existing_authors
      WHERE public.canonical_author_normalized_name(
        public.canonical_author_name(existing_authors.name)
      ) = candidate.normalized_name
      ORDER BY
        CASE WHEN existing_authors.name = candidate.name THEN 0 ELSE 1 END,
        existing_authors.created_at NULLS FIRST,
        existing_authors.id
      LIMIT 1
      FOR UPDATE OF existing_authors;
    END IF;

    IF resolved_author_id IS NULL THEN
      INSERT INTO public.authors (id, name, normalized_name, created_at, updated_at)
      VALUES (
        public.generate_author_relation_id(10),
        candidate.name,
        candidate.normalized_name,
        now(),
        now()
      )
      ON CONFLICT (name) DO UPDATE SET
        normalized_name = EXCLUDED.normalized_name,
        updated_at = now()
      RETURNING public.authors.id INTO resolved_author_id;
    ELSE
      -- Canonical candidate spellings outrank legacy raw spellings. Ties use
      -- the C collation's locale-independent byte rank, so arrival order
      -- cannot select a different display name.
      resolved_display_name := CASE
        WHEN public.canonical_author_name(resolved_display_name)
          IS DISTINCT FROM resolved_display_name
        THEN candidate.name
        ELSE LEAST(
          resolved_display_name COLLATE "C",
          candidate.name COLLATE "C"
        )
      END;

      UPDATE public.authors
      SET
        name = resolved_display_name,
        normalized_name = candidate.normalized_name,
        updated_at = now()
      WHERE public.authors.id = resolved_author_id
        AND (
          public.authors.name IS DISTINCT FROM resolved_display_name
          OR public.authors.normalized_name IS DISTINCT FROM candidate.normalized_name
        );

      IF NOT FOUND THEN
        UPDATE public.authors
        SET updated_at = now()
        WHERE public.authors.id = resolved_author_id;

        IF NOT FOUND THEN
          RAISE EXCEPTION 'resolved author row disappeared';
        END IF;
      END IF;
    END IF;

    resolved_author_ids := array_append(resolved_author_ids, resolved_author_id);
    resolved_positions := array_append(resolved_positions, candidate.position);
  END LOOP;

  FOR resolved_relation IN
    SELECT
      resolved.author_id,
      min(resolved.position) AS position
    FROM unnest(resolved_author_ids, resolved_positions)
      AS resolved(author_id, position)
    GROUP BY resolved.author_id
    ORDER BY resolved.author_id
  LOOP
    INSERT INTO public.book_authors_join (
      id,
      book_id,
      author_id,
      position,
      created_at,
      updated_at
    )
    VALUES (
      public.generate_author_relation_id(12),
      target_book_id,
      resolved_relation.author_id,
      resolved_relation.position,
      now(),
      now()
    )
    ON CONFLICT (book_id, author_id) DO UPDATE SET
      position = EXCLUDED.position,
      updated_at = now();
  END LOOP;
END;
$$;

COMMENT ON PROCEDURE public.upsert_book_authors(uuid, text[]) IS
  'Canonical ordered author and book-author upsert with deterministic database-owned display-name ranking';

COMMIT;
