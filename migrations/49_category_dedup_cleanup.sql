-- Migration 49: Category deduplication cleanup
--
-- Problem: Categories had inconsistent normalized_name values because an older
-- migration used spaces (e.g., "history  social history") while the Java
-- CategoryNormalizer uses hyphens ("history-social-history"). The unique
-- constraint on (collection_type, source, normalized_name) treated these as
-- different entries, creating ~2,800 duplicate category rows.
--
-- This migration:
-- 1. Merges old-norm duplicates (space-separated) into new-norm counterparts (hyphenated).
-- 2. Merges ASCII-norm duplicates (stripped accents) into Unicode-norm counterparts (preserved accents).
-- 3. Re-normalizes all remaining categories to the canonical hyphen-based scheme
--    matching CategoryNormalizer.normalizeForDatabase() (Unicode-aware).
-- 4. Purges garbage categories (Dewey Decimal, MARC codes, numeric-only).
-- 5. Cleans up orphaned join entries.
-- 6. Adds a CHECK constraint to prevent empty/garbage display names in the future.
--
-- Safe to re-run: all operations are idempotent (ON CONFLICT DO NOTHING, etc.)

BEGIN;

-- ============================================================
-- STEP 1: Merge old-norm duplicates into new-norm counterparts
-- (Space-separated vs Hyphen-separated)
-- ============================================================

-- Move join entries
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

-- Delete orphaned joins (if any remained from conflict skip)
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

-- Delete duplicate categories
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
-- STEP 2: Merge ASCII-norm duplicates into Unicode-norm counterparts
-- (e.g. "litt-rature" vs "littérature")
-- ============================================================

INSERT INTO book_collections_join (id, collection_id, book_id, created_at, updated_at)
SELECT
    SUBSTR(MD5(RANDOM()::TEXT), 1, 12),
    unicode_ver.id,
    bcj.book_id,
    bcj.created_at,
    NOW()
FROM book_collections ascii_ver
JOIN book_collections unicode_ver
    ON LOWER(ascii_ver.display_name) = LOWER(unicode_ver.display_name)
    AND ascii_ver.collection_type = 'CATEGORY'
    AND unicode_ver.collection_type = 'CATEGORY'
    AND ascii_ver.id != unicode_ver.id
    -- ASCII version has dashes/alnum only where Unicode version has other chars
    AND ascii_ver.normalized_name ~ '^[a-z0-9-]+$'
    AND unicode_ver.normalized_name ~ '[^a-z0-9-]'
JOIN book_collections_join bcj ON bcj.collection_id = ascii_ver.id
ON CONFLICT (collection_id, book_id) DO NOTHING;

DELETE FROM book_collections_join
WHERE collection_id IN (
    SELECT ascii_ver.id
    FROM book_collections ascii_ver
    JOIN book_collections unicode_ver
        ON LOWER(ascii_ver.display_name) = LOWER(unicode_ver.display_name)
        AND ascii_ver.collection_type = 'CATEGORY'
        AND unicode_ver.collection_type = 'CATEGORY'
        AND ascii_ver.id != unicode_ver.id
        AND ascii_ver.normalized_name ~ '^[a-z0-9-]+$'
        AND unicode_ver.normalized_name ~ '[^a-z0-9-]'
);

DELETE FROM book_collections
WHERE id IN (
    SELECT ascii_ver.id
    FROM book_collections ascii_ver
    JOIN book_collections unicode_ver
        ON LOWER(ascii_ver.display_name) = LOWER(unicode_ver.display_name)
        AND ascii_ver.collection_type = 'CATEGORY'
        AND unicode_ver.collection_type = 'CATEGORY'
        AND ascii_ver.id != unicode_ver.id
        AND ascii_ver.normalized_name ~ '^[a-z0-9-]+$'
        AND unicode_ver.normalized_name ~ '[^a-z0-9-]'
);

-- ============================================================
-- STEP 3: Handle remaining categories needing re-normalization
-- ============================================================

-- Merge if re-normalization would cause a conflict
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
        REGEXP_REPLACE(LOWER(old.display_name), '[^[:alnum:]]+', '-', 'g'),
        '^-+|-+$', '', 'g'
    )
    AND existing.id != old.id
JOIN book_collections_join bcj ON bcj.collection_id = old.id
WHERE old.collection_type = 'CATEGORY'
  AND old.normalized_name != REGEXP_REPLACE(
      REGEXP_REPLACE(LOWER(old.display_name), '[^[:alnum:]]+', '-', 'g'),
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
            REGEXP_REPLACE(LOWER(old.display_name), '[^[:alnum:]]+', '-', 'g'),
            '^-+|-+$', '', 'g'
        )
        AND existing.id != old.id
    WHERE old.collection_type = 'CATEGORY'
      AND old.normalized_name != REGEXP_REPLACE(
          REGEXP_REPLACE(LOWER(old.display_name), '[^[:alnum:]]+', '-', 'g'),
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
            REGEXP_REPLACE(LOWER(old.display_name), '[^[:alnum:]]+', '-', 'g'),
            '^-+|-+$', '', 'g'
        )
        AND existing.id != old.id
    WHERE old.collection_type = 'CATEGORY'
      AND old.normalized_name != REGEXP_REPLACE(
          REGEXP_REPLACE(LOWER(old.display_name), '[^[:alnum:]]+', '-', 'g'),
          '^-+|-+$', '', 'g'
      )
);

-- Re-normalize remaining using Unicode-preserving regex ([^[:alnum:]] matches symbols/punctuation but keeps letters/digits)
UPDATE book_collections
SET normalized_name = REGEXP_REPLACE(
    REGEXP_REPLACE(LOWER(display_name), '[^[:alnum:]]+', '-', 'g'),
    '^-+|-+$', '', 'g'
),
updated_at = NOW()
WHERE collection_type = 'CATEGORY'
  AND normalized_name != REGEXP_REPLACE(
      REGEXP_REPLACE(LOWER(display_name), '[^[:alnum:]]+', '-', 'g'),
      '^-+|-+$', '', 'g'
  );

-- ============================================================
-- STEP 4: Purge garbage categories
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
-- STEP 5: Clean up orphaned join entries
-- ============================================================

DELETE FROM book_collections_join bcj
WHERE NOT EXISTS (
    SELECT 1 FROM book_collections bc WHERE bc.id = bcj.collection_id
);

-- ============================================================
-- STEP 6: Add safeguard constraints
-- ============================================================

-- Ensure categories always have a meaningful display name
ALTER TABLE book_collections DROP CONSTRAINT IF EXISTS chk_category_name_valid;
ALTER TABLE book_collections ADD CONSTRAINT chk_category_name_valid
    CHECK (collection_type != 'CATEGORY' OR LENGTH(TRIM(display_name)) >= 2);

COMMIT;
