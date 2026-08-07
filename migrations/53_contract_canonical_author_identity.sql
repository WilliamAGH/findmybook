-- !!! DEPLOYMENT PREREQUISITE: REQUIRED FOR AN EXISTING DATABASE - DO NOT RUN !!!
-- HUMAN-ONLY: apply these exact reviewed bytes only after migration 52 is live,
-- every author write is quiesced, all prior writers are drained, and every Java
-- and Node caller is deployed while writes remain quiesced. Fresh empty schema
-- bootstrap is the only deployment-prerequisite exception.
-- MAINTENANCE WINDOW: before BEGIN, stop and drain every runtime, job, migration,
-- and manual writer that can mutate public.authors, public.book_authors_join, or
-- public.author_external_ids; keep them quiesced until COMMIT or ROLLBACK. These
-- blocking locks protect full-table rewrites, so draining only old writers is not
-- sufficient.
-- Purpose: merge legacy author identities, enforce canonical-key uniqueness,
-- and retire the superseded manual normalization and merge functions.

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

SELECT pg_advisory_xact_lock(
  hashtextextended('findmybook.author-contract-rollout', 0)
);

LOCK TABLE public.authors IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.book_authors_join IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.author_external_ids IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE canonical_author_upgrade ON COMMIT DROP AS
WITH projected_authors AS (
  SELECT
    authors.*,
    public.canonical_author_name(
      regexp_replace(authors.name, '^[[:space:]]+', '')
    ) AS canonical_name
  FROM public.authors
),
valid_authors AS (
  SELECT
    projected_authors.*,
    public.canonical_author_normalized_name(projected_authors.canonical_name)
      AS canonical_normalized_name
  FROM projected_authors
  WHERE projected_authors.canonical_name IS NOT NULL
),
ranked_authors AS (
  SELECT
    valid_authors.*,
    first_value(valid_authors.id) OVER (
      PARTITION BY valid_authors.canonical_normalized_name
      ORDER BY
        (valid_authors.name = valid_authors.canonical_name) DESC,
        valid_authors.created_at NULLS FIRST,
        valid_authors.id
    ) AS canonical_author_id
  FROM valid_authors
)
SELECT * FROM ranked_authors;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.authors AS authors
    LEFT JOIN canonical_author_upgrade AS upgrade ON upgrade.id = authors.id
    WHERE upgrade.id IS NULL
  ) THEN
    RAISE EXCEPTION 'author canonicalization produced an empty identity';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM canonical_author_upgrade AS upgrade
    GROUP BY upgrade.canonical_author_id
    HAVING count(DISTINCT upgrade.birth_date) FILTER (
        WHERE upgrade.birth_date IS NOT NULL
      ) > 1
      OR count(DISTINCT upgrade.death_date) FILTER (
        WHERE upgrade.death_date IS NOT NULL
      ) > 1
      OR count(DISTINCT upgrade.biography) FILTER (
        WHERE upgrade.biography IS NOT NULL
      ) > 1
      OR count(DISTINCT upgrade.nationality) FILTER (
        WHERE upgrade.nationality IS NOT NULL
      ) > 1
  ) THEN
    RAISE EXCEPTION 'canonical author identity has multiple distinct non-null personal metadata values';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM canonical_author_upgrade AS upgrade
    JOIN public.author_external_ids AS external_ids
      ON external_ids.author_id = upgrade.id
    GROUP BY upgrade.canonical_author_id, external_ids.source
    HAVING count(DISTINCT external_ids.external_id) > 1
  ) THEN
    RAISE EXCEPTION 'canonical author identity has conflicting external IDs for the same source';
  END IF;
END
$$;

CREATE TEMP TABLE canonical_author_merge ON COMMIT DROP AS
SELECT
  survivor.id AS canonical_author_id,
  survivor.canonical_name,
  survivor.canonical_normalized_name,
  min(group_member.birth_date) FILTER (
    WHERE group_member.birth_date IS NOT NULL
  ) AS birth_date,
  min(group_member.death_date) FILTER (
    WHERE group_member.death_date IS NOT NULL
  ) AS death_date,
  min(group_member.biography) FILTER (
    WHERE group_member.biography IS NOT NULL
  ) AS biography,
  min(group_member.nationality) FILTER (
    WHERE group_member.nationality IS NOT NULL
  ) AS nationality
FROM canonical_author_upgrade AS survivor
JOIN canonical_author_upgrade AS group_member
  ON group_member.canonical_author_id = survivor.id
WHERE survivor.id = survivor.canonical_author_id
GROUP BY
  survivor.id,
  survivor.canonical_name,
  survivor.canonical_normalized_name;

CREATE TEMP TABLE canonical_book_author_upgrade ON COMMIT DROP AS
SELECT
  relations.id,
  upgrade.canonical_author_id,
  first_value(relations.id) OVER (
    PARTITION BY relations.book_id, upgrade.canonical_author_id
    ORDER BY relations.position, relations.created_at NULLS LAST, relations.id
  ) AS canonical_relation_id,
  min(relations.position) OVER (
    PARTITION BY relations.book_id, upgrade.canonical_author_id
  ) AS canonical_position
FROM public.book_authors_join AS relations
JOIN canonical_author_upgrade AS upgrade ON upgrade.id = relations.author_id;

DELETE FROM public.book_authors_join AS relations
USING canonical_book_author_upgrade AS upgrade
WHERE relations.id = upgrade.id
  AND upgrade.id <> upgrade.canonical_relation_id;

UPDATE public.book_authors_join AS relations
SET
  author_id = upgrade.canonical_author_id,
  position = upgrade.canonical_position,
  updated_at = now()
FROM canonical_book_author_upgrade AS upgrade
WHERE relations.id = upgrade.canonical_relation_id
  AND relations.id = upgrade.id
  AND (
    relations.author_id IS DISTINCT FROM upgrade.canonical_author_id
    OR relations.position IS DISTINCT FROM upgrade.canonical_position
  );

UPDATE public.author_external_ids AS external_ids
SET author_id = upgrade.canonical_author_id
FROM canonical_author_upgrade AS upgrade
WHERE external_ids.author_id = upgrade.id
  AND external_ids.author_id IS DISTINCT FROM upgrade.canonical_author_id;

DELETE FROM public.authors AS authors
USING canonical_author_upgrade AS upgrade
WHERE authors.id = upgrade.id
  AND upgrade.id <> upgrade.canonical_author_id;

UPDATE public.authors AS authors
SET
  name = merge.canonical_name,
  normalized_name = merge.canonical_normalized_name,
  birth_date = merge.birth_date,
  death_date = merge.death_date,
  biography = merge.biography,
  nationality = merge.nationality,
  updated_at = now()
FROM canonical_author_merge AS merge
WHERE authors.id = merge.canonical_author_id
  AND (
    authors.name IS DISTINCT FROM merge.canonical_name
    OR authors.normalized_name IS DISTINCT FROM merge.canonical_normalized_name
    OR authors.birth_date IS DISTINCT FROM merge.birth_date
    OR authors.death_date IS DISTINCT FROM merge.death_date
    OR authors.biography IS DISTINCT FROM merge.biography
    OR authors.nationality IS DISTINCT FROM merge.nationality
  );

-- Tightening the legacy leading-name constraint belongs to this quiesced
-- contract phase. Migration 52 must remain safe for rows that satisfy the old
-- constraint, including names whose leading whitespace is a tab.
ALTER TABLE public.authors
  DROP CONSTRAINT IF EXISTS authors_name_leading_character_check;

ALTER TABLE public.authors
  ADD CONSTRAINT authors_name_leading_character_check CHECK (
    left(btrim(name), 1) COLLATE "und-x-icu" ~ '^[[:alnum:]]$'
  ) NOT VALID;

ALTER TABLE public.authors
  VALIDATE CONSTRAINT authors_name_leading_character_check;

DROP FUNCTION IF EXISTS public.merge_duplicate_authors();

DO $$
DECLARE
  existing_relation regclass := to_regclass('public.uq_authors_normalized_name');
BEGIN
  IF existing_relation IS NULL THEN
    EXECUTE 'CREATE UNIQUE INDEX uq_authors_normalized_name '
      || 'ON public.authors USING btree (normalized_name) '
      || 'WHERE normalized_name IS NOT NULL';
  ELSIF NOT public.canonical_author_contract_is_applied() THEN
    RAISE EXCEPTION 'public.uq_authors_normalized_name has an unexpected definition';
  END IF;
END
$$;

DO $$
BEGIN
  IF NOT public.canonical_author_contract_is_applied() THEN
    RAISE EXCEPTION 'canonical author identity index contract was not established';
  END IF;
END
$$;

DROP FUNCTION IF EXISTS public.normalize_author_name(text);
DROP FUNCTION IF EXISTS public.ensure_unique_slug(text);
DROP FUNCTION IF EXISTS public.generate_slug(text, text);

COMMIT;
