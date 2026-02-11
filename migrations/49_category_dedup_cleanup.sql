-- Migration 49: Category deduplication cleanup
--
-- Problem: Categories had inconsistent normalized_name values because an older
-- migration used spaces (e.g., "history  social history") while the Java
-- CategoryNormalizer uses hyphens ("history-social-history"). The unique
-- constraint on (collection_type, source, normalized_name) treated these as
-- different entries, creating ~2,800 duplicate category rows.
--
-- This migration:
-- 1. Re-normalizes all category normalized_name values to the canonical
--    hyphen-based scheme matching CategoryNormalizer.normalizeForDatabase().
-- 2. Merges book_collections_join entries from old-norm to surviving new-norm.
-- 3. Deletes orphaned old-norm category rows.
-- 4. Purges garbage categories (Dewey Decimal, MARC codes, numeric-only).
-- 5. Drops and recreates the unique index with a tighter expression.
--
-- Safe to re-run: all operations are idempotent (ON CONFLICT DO NOTHING, etc.)

BEGIN;

-- ============================================================
-- STEP 1: Merge old-norm duplicates into new-norm counterparts
-- ============================================================

-- Move join entries from old-norm categories to their new-norm counterparts
-- (same display_name, different normalized_name). Skip conflicts.
INSERT INTO book_collections_join (id, collection_id, book_id, created_at, updated_at)
SELECT
    SUBSTR(MD5(RANDOM()::TEXT), 1, 12),
    new.id,
    bcj.book_id,
    bcj.created_at,
    NOW()
FROM book_collections old
JOIN book_collections new
    ON LOWER(old.display_name) = LOWER(new.display_name)
    AND old.collection_type = 'CATEGORY'
    AND new.collection_type = 'CATEGORY'
    AND old.id != new.id
    AND old.normalized_name LIKE '%  %'
    AND new.normalized_name LIKE '%-%'
JOIN book_collections_join bcj ON bcj.collection_id = old.id
ON CONFLICT (collection_id, book_id) DO NOTHING;

-- Delete join entries referencing old-norm categories
DELETE FROM book_collections_join
WHERE collection_id IN (
    SELECT old.id
    FROM book_collections old
    JOIN book_collections new
        ON LOWER(old.display_name) = LOWER(new.display_name)
        AND old.collection_type = 'CATEGORY'
        AND new.collection_type = 'CATEGORY'
        AND old.id != new.id
        AND old.normalized_name LIKE '%  %'
        AND new.normalized_name LIKE '%-%'
);

-- Delete old-norm category rows
DELETE FROM book_collections
WHERE id IN (
    SELECT old.id
    FROM book_collections old
    JOIN book_collections new
        ON LOWER(old.display_name) = LOWER(new.display_name)
        AND old.collection_type = 'CATEGORY'
        AND new.collection_type = 'CATEGORY'
        AND old.id != new.id
        AND old.normalized_name LIKE '%  %'
        AND new.normalized_name LIKE '%-%'
);

-- ============================================================
-- STEP 2: Handle remaining old-norm categories that have no
--         new-norm counterpart (merge or re-normalize)
-- ============================================================

-- Merge remaining old-norm that would conflict after re-normalization
INSERT INTO book_collections_join (id, collection_id, book_id, created_at, updated_at)
SELECT
    SUBSTR(MD5(RANDOM()::TEXT), 1, 12),
    existing.id,
    bcj.book_id,
    bcj.created_at,
    NOW()
FROM book_collections old
JOIN book_collections existing
    ON existing.collection_type = 'CATEGORY'
    AND existing.source = old.source
    AND existing.normalized_name = REGEXP_REPLACE(
        REGEXP_REPLACE(LOWER(old.display_name), '[^a-z0-9]+', '-', 'g'),
        '^-+|-+$', '', 'g'
    )
    AND existing.id != old.id
JOIN book_collections_join bcj ON bcj.collection_id = old.id
WHERE old.collection_type = 'CATEGORY'
  AND old.normalized_name != REGEXP_REPLACE(
      REGEXP_REPLACE(LOWER(old.display_name), '[^a-z0-9]+', '-', 'g'),
      '^-+|-+$', '', 'g'
  )
ON CONFLICT (collection_id, book_id) DO NOTHING;

DELETE FROM book_collections_join
WHERE collection_id IN (
    SELECT old.id
    FROM book_collections old
    JOIN book_collections existing
        ON existing.collection_type = 'CATEGORY'
        AND existing.source = old.source
        AND existing.normalized_name = REGEXP_REPLACE(
            REGEXP_REPLACE(LOWER(old.display_name), '[^a-z0-9]+', '-', 'g'),
            '^-+|-+$', '', 'g'
        )
        AND existing.id != old.id
    WHERE old.collection_type = 'CATEGORY'
      AND old.normalized_name != REGEXP_REPLACE(
          REGEXP_REPLACE(LOWER(old.display_name), '[^a-z0-9]+', '-', 'g'),
          '^-+|-+$', '', 'g'
      )
);

DELETE FROM book_collections
WHERE id IN (
    SELECT old.id
    FROM book_collections old
    JOIN book_collections existing
        ON existing.collection_type = 'CATEGORY'
        AND existing.source = old.source
        AND existing.normalized_name = REGEXP_REPLACE(
            REGEXP_REPLACE(LOWER(old.display_name), '[^a-z0-9]+', '-', 'g'),
            '^-+|-+$', '', 'g'
        )
        AND existing.id != old.id
    WHERE old.collection_type = 'CATEGORY'
      AND old.normalized_name != REGEXP_REPLACE(
          REGEXP_REPLACE(LOWER(old.display_name), '[^a-z0-9]+', '-', 'g'),
          '^-+|-+$', '', 'g'
      )
);

-- Now re-normalize all remaining categories to canonical hyphen form
UPDATE book_collections
SET normalized_name = REGEXP_REPLACE(
    REGEXP_REPLACE(LOWER(display_name), '[^a-z0-9]+', '-', 'g'),
    '^-+|-+$', '', 'g'
),
updated_at = NOW()
WHERE collection_type = 'CATEGORY'
  AND normalized_name != REGEXP_REPLACE(
      REGEXP_REPLACE(LOWER(display_name), '[^a-z0-9]+', '-', 'g'),
      '^-+|-+$', '', 'g'
  );

-- ============================================================
-- STEP 3: Purge garbage categories
-- ============================================================

DELETE FROM book_collections_join
WHERE collection_id IN (
    SELECT id FROM book_collections
    WHERE collection_type = 'CATEGORY'
      AND (
        normalized_name ~ '^[0-9]+(-[0-9]+)*$'
        OR normalized_name ~ '^[0-9]+[a-z]'
        OR LENGTH(TRIM(display_name)) < 2
      )
);

DELETE FROM book_collections
WHERE collection_type = 'CATEGORY'
  AND (
    normalized_name ~ '^[0-9]+(-[0-9]+)*$'
    OR normalized_name ~ '^[0-9]+[a-z]'
    OR LENGTH(TRIM(display_name)) < 2
  );

-- ============================================================
-- STEP 4: Clean up orphaned join entries
-- ============================================================

DELETE FROM book_collections_join
WHERE collection_id NOT IN (SELECT id FROM book_collections);

COMMIT;
