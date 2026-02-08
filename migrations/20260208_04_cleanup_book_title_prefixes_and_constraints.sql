-- Clean book rows with leading non-alphanumeric title prefixes and enforce title guard constraints.
-- Safe to run multiple times.

BEGIN;

ALTER TABLE books DROP CONSTRAINT IF EXISTS books_title_non_blank_check;
ALTER TABLE books DROP CONSTRAINT IF EXISTS books_title_leading_character_check;

WITH cleaned_titles AS (
  SELECT
    b.id,
    b.title AS original_title,
    btrim(regexp_replace(b.title, '^[[:space:]]*[^[:alpha:][:digit:]]+', '')) AS cleaned_title
  FROM books b
),
updated_titles AS (
  UPDATE books b
  SET
    title = cleaned.cleaned_title,
    updated_at = now()
  FROM cleaned_titles cleaned
  WHERE b.id = cleaned.id
    AND b.title IS DISTINCT FROM cleaned.cleaned_title
  RETURNING b.id
)
SELECT count(*) FROM updated_titles;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM books
    WHERE btrim(title) = ''
       OR regexp_replace(title, '^[[:space:]]+', '') ~ '^[^[:alpha:][:digit:]]'
  ) THEN
    RAISE EXCEPTION 'book title cleanup left rows that violate leading-character/non-blank requirements';
  END IF;
END
$$;

ALTER TABLE books
  ADD CONSTRAINT books_title_non_blank_check CHECK (btrim(title) <> '');

ALTER TABLE books
  ADD CONSTRAINT books_title_leading_character_check CHECK (
    regexp_replace(title, '^[[:space:]]+', '') ~ '^[[:alpha:][:digit:]]'
  );

COMMIT;
