-- Join table linking books to authors (many-to-many)
create table if not exists book_authors_join (
  id text primary key, -- NanoID (12 chars via IdGenerator.generateLong() - high volume)
  book_id uuid not null references books(id) on delete cascade,
  author_id text not null references authors(id) on delete cascade,
  position integer not null default 0, -- Order of author as it appears in the book
  created_at timestamptz not null default now(),
  unique(book_id, author_id)
);

create index if not exists idx_book_authors_book_id on book_authors_join(book_id);
create index if not exists idx_book_authors_author_id on book_authors_join(author_id);
create index if not exists idx_book_authors_position on book_authors_join(book_id, position);

-- ---------------------------------------------------------------------------
-- Legacy data cleanup consolidated into canonical author join migration.
-- ---------------------------------------------------------------------------
-- Clean author rows with leading non-alphanumeric prefixes and enforce guard constraints.
-- Safe to run multiple times.

BEGIN;

ALTER TABLE authors DROP CONSTRAINT IF EXISTS authors_name_non_blank_check;
ALTER TABLE authors DROP CONSTRAINT IF EXISTS authors_name_leading_character_check;
ALTER TABLE book_authors_join DROP CONSTRAINT IF EXISTS book_authors_join_book_id_author_id_key;

CREATE TEMP TABLE tmp_author_name_cleanup ON COMMIT DROP AS
WITH candidate_rows AS (
  SELECT
    a.id AS author_id,
    a.name AS original_name,
    a.created_at,
    btrim(
      regexp_replace(
        regexp_replace(a.name, '^[[:space:]]*[^[:alpha:][:digit:]]+', ''),
        '[[:space:],;:\]]+$',
        ''
      )
    ) AS cleaned_name
  FROM authors a
  WHERE regexp_replace(a.name, '^[[:space:]]+', '') ~ '^[^[:alpha:][:digit:]]'
),
valid_candidates AS (
  SELECT
    author_id,
    original_name,
    created_at,
    cleaned_name
  FROM candidate_rows
  WHERE cleaned_name <> ''
),
canonical_rows AS (
  SELECT
    vc.cleaned_name,
    (
      SELECT a2.id
      FROM authors a2
      WHERE a2.name = vc.cleaned_name
         OR a2.id IN (
            SELECT vc2.author_id
            FROM valid_candidates vc2
            WHERE vc2.cleaned_name = vc.cleaned_name
         )
      ORDER BY
        CASE WHEN a2.name = vc.cleaned_name THEN 0 ELSE 1 END,
        a2.created_at NULLS FIRST,
        a2.id
      LIMIT 1
    ) AS canonical_author_id
  FROM valid_candidates vc
  GROUP BY vc.cleaned_name
)
SELECT
  vc.author_id,
  vc.original_name,
  vc.cleaned_name,
  cr.canonical_author_id
FROM valid_candidates vc
JOIN canonical_rows cr ON cr.cleaned_name = vc.cleaned_name;

UPDATE book_authors_join baj
SET author_id = cleanup.canonical_author_id
FROM tmp_author_name_cleanup cleanup
WHERE baj.author_id = cleanup.author_id
  AND cleanup.author_id <> cleanup.canonical_author_id;

UPDATE author_external_ids aei
SET author_id = cleanup.canonical_author_id
FROM tmp_author_name_cleanup cleanup
WHERE aei.author_id = cleanup.author_id
  AND cleanup.author_id <> cleanup.canonical_author_id;

WITH ranked_book_authors AS (
  SELECT
    id,
    row_number() OVER (
      PARTITION BY book_id, author_id
      ORDER BY created_at DESC NULLS LAST, id DESC
    ) AS rn
  FROM book_authors_join
)
DELETE FROM book_authors_join baj
USING ranked_book_authors ranked
WHERE baj.id = ranked.id
  AND ranked.rn > 1;

DELETE FROM authors a
USING tmp_author_name_cleanup cleanup
WHERE a.id = cleanup.author_id
  AND cleanup.author_id <> cleanup.canonical_author_id;

UPDATE authors a
SET name = cleanup.cleaned_name,
    normalized_name = nullif(lower(regexp_replace(cleanup.cleaned_name, '[^a-z0-9\s]', '', 'g')), ''),
    updated_at = now()
FROM (
  SELECT DISTINCT canonical_author_id, cleaned_name
  FROM tmp_author_name_cleanup
) cleanup
WHERE a.id = cleanup.canonical_author_id
  AND (
    a.name IS DISTINCT FROM cleanup.cleaned_name
    OR a.normalized_name IS DISTINCT FROM nullif(lower(regexp_replace(cleanup.cleaned_name, '[^a-z0-9\s]', '', 'g')), '')
  );

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM authors
    WHERE btrim(name) = ''
       OR regexp_replace(name, '^[[:space:]]+', '') ~ '^[^[:alpha:][:digit:]]'
  ) THEN
    RAISE EXCEPTION 'author cleanup left rows that violate leading-character/non-blank requirements';
  END IF;
END
$$;

ALTER TABLE authors
  ADD CONSTRAINT authors_name_non_blank_check CHECK (btrim(name) <> '');

ALTER TABLE authors
  ADD CONSTRAINT authors_name_leading_character_check CHECK (
    regexp_replace(name, '^[[:space:]]+', '') ~ '^[[:alpha:][:digit:]]'
  );

ALTER TABLE book_authors_join
  ADD CONSTRAINT book_authors_join_book_id_author_id_key UNIQUE (book_id, author_id);

COMMIT;
