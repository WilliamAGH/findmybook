create table if not exists book_tag_assignments (
  id text primary key, -- NanoID (12 chars)
  book_id uuid not null references books(id) on delete cascade,
  tag_id text not null references book_tags(id) on delete cascade,
  source text not null, -- 'SEARCH_QUERY', 'PROVIDER', 'CURATION', etc.
  confidence numeric, -- optional 0..1 confidence score
  metadata jsonb, -- optional extra data (e.g., original phrase, provider payload snippet)
  created_at timestamptz not null default now(),
  unique (book_id, tag_id)
);


create index if not exists idx_book_tag_assignments_book_id on book_tag_assignments(book_id);
create index if not exists idx_book_tag_assignments_tag_id on book_tag_assignments(tag_id);

comment on table book_tag_assignments is 'Assignments linking canonical books to tags with source + optional metadata.';
comment on column book_tag_assignments.source is 'Most recent origin of the tag assignment (SEARCH_QUERY, PROVIDER, CURATION, etc).';
comment on column book_tag_assignments.metadata is 'Additional context, e.g., original query text or provider payload snippet.';

-- ---------------------------------------------------------------------------
-- Legacy deduplication consolidated into canonical tag assignment migration.
-- ---------------------------------------------------------------------------
-- Deduplicate tags + tag assignments and enforce canonical constraints.
-- Safe to run multiple times.

BEGIN;

-- Allow temporary duplicate states during cleanup/repoint operations.
ALTER TABLE book_tag_assignments DROP CONSTRAINT IF EXISTS book_tag_assignments_book_id_tag_id_source_key;
ALTER TABLE book_tag_assignments DROP CONSTRAINT IF EXISTS book_tag_assignments_book_id_tag_id_key;
ALTER TABLE book_tag_equivalents DROP CONSTRAINT IF EXISTS book_tag_equivalents_canonical_tag_id_equivalent_tag_id_key;

CREATE TEMP TABLE tmp_book_tag_canonical_map ON COMMIT DROP AS
SELECT
  bt.id AS tag_id,
  first_value(bt.id) OVER (
    PARTITION BY lower(regexp_replace(btrim(bt.key), '[[:space:]]+', '_', 'g'))
    ORDER BY bt.created_at NULLS FIRST, bt.id
  ) AS canonical_tag_id,
  lower(regexp_replace(btrim(bt.key), '[[:space:]]+', '_', 'g')) AS canonical_key
FROM book_tags bt;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM tmp_book_tag_canonical_map
    WHERE canonical_key IS NULL OR canonical_key = ''
  ) THEN
    RAISE EXCEPTION 'book_tags contains blank/invalid keys that cannot be canonicalized';
  END IF;
END
$$;

-- Repoint all references to canonical tag IDs.
UPDATE book_tag_assignments bta
SET tag_id = map.canonical_tag_id
FROM tmp_book_tag_canonical_map map
WHERE bta.tag_id = map.tag_id
  AND map.tag_id <> map.canonical_tag_id;

UPDATE book_tag_equivalents bte
SET canonical_tag_id = map.canonical_tag_id
FROM tmp_book_tag_canonical_map map
WHERE bte.canonical_tag_id = map.tag_id
  AND map.tag_id <> map.canonical_tag_id;

UPDATE book_tag_equivalents bte
SET equivalent_tag_id = map.canonical_tag_id
FROM tmp_book_tag_canonical_map map
WHERE bte.equivalent_tag_id = map.tag_id
  AND map.tag_id <> map.canonical_tag_id;

-- Remove duplicate assignments after repointing.
WITH ranked AS (
  SELECT
    id,
    row_number() OVER (
      PARTITION BY book_id, tag_id, source
      ORDER BY created_at DESC NULLS LAST, id DESC
    ) AS rn
  FROM book_tag_assignments
)
DELETE FROM book_tag_assignments bta
USING ranked
WHERE bta.id = ranked.id
  AND ranked.rn > 1;

-- Enforce one assignment per book+tag regardless of source.
WITH ranked AS (
  SELECT
    id,
    row_number() OVER (
      PARTITION BY book_id, tag_id
      ORDER BY created_at DESC NULLS LAST, id DESC
    ) AS rn
  FROM book_tag_assignments
)
DELETE FROM book_tag_assignments bta
USING ranked
WHERE bta.id = ranked.id
  AND ranked.rn > 1;

-- Remove equivalent self-links and duplicate pairs after repointing.
DELETE FROM book_tag_equivalents
WHERE canonical_tag_id = equivalent_tag_id;

WITH ranked AS (
  SELECT
    id,
    row_number() OVER (
      PARTITION BY canonical_tag_id, equivalent_tag_id
      ORDER BY created_at DESC NULLS LAST, id DESC
    ) AS rn
  FROM book_tag_equivalents
)
DELETE FROM book_tag_equivalents bte
USING ranked
WHERE bte.id = ranked.id
  AND ranked.rn > 1;

-- Remove non-canonical duplicate tag rows.
DELETE FROM book_tags bt
USING tmp_book_tag_canonical_map map
WHERE bt.id = map.tag_id
  AND map.tag_id <> map.canonical_tag_id;

-- Normalize remaining canonical keys.
UPDATE book_tags bt
SET key = map.canonical_key,
    updated_at = now()
FROM tmp_book_tag_canonical_map map
WHERE bt.id = map.canonical_tag_id
  AND bt.key IS DISTINCT FROM map.canonical_key;

-- Normalize sources so source constraints are stable and predictable.
UPDATE book_tag_assignments
SET source = btrim(source)
WHERE source IS DISTINCT FROM btrim(source);

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM book_tag_assignments
    WHERE source IS NULL OR source = ''
  ) THEN
    RAISE EXCEPTION 'book_tag_assignments contains blank sources';
  END IF;
END
$$;

-- Re-add uniqueness constraints after cleanup.
ALTER TABLE book_tag_equivalents
  ADD CONSTRAINT book_tag_equivalents_canonical_tag_id_equivalent_tag_id_key
  UNIQUE (canonical_tag_id, equivalent_tag_id);

ALTER TABLE book_tag_assignments
  ADD CONSTRAINT book_tag_assignments_book_id_tag_id_key
  UNIQUE (book_id, tag_id);

-- Enforce canonical key/source format going forward.
ALTER TABLE book_tags DROP CONSTRAINT IF EXISTS book_tags_key_canonical_check;
ALTER TABLE book_tags
  ADD CONSTRAINT book_tags_key_canonical_check CHECK (
    key = lower(regexp_replace(btrim(key), '[[:space:]]+', '_', 'g'))
    AND key <> ''
  );

ALTER TABLE book_tag_assignments DROP CONSTRAINT IF EXISTS book_tag_assignments_source_non_blank_check;
ALTER TABLE book_tag_assignments
  ADD CONSTRAINT book_tag_assignments_source_non_blank_check CHECK (
    source = btrim(source)
    AND source <> ''
  );

COMMENT ON COLUMN book_tag_assignments.source IS
  'Most recent origin of the tag assignment (SEARCH_QUERY, PROVIDER, CURATION, etc).';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM book_tags
    GROUP BY lower(regexp_replace(btrim(key), '[[:space:]]+', '_', 'g'))
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'book_tags still has duplicate canonical keys after cleanup';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM book_tag_assignments
    GROUP BY book_id, tag_id
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'book_tag_assignments still has duplicate rows per book_id+tag_id';
  END IF;
END
$$;

COMMIT;
