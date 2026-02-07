-- Fix data hygiene for Google canonical clustering and rebuild valid Google clusters.
-- Safe to run multiple times.

BEGIN;

-- 1) Remove control characters from stored Google canonical IDs.
UPDATE book_external_ids
SET google_canonical_id = nullif(regexp_replace(google_canonical_id, '[[:cntrl:]]', '', 'g'), ''),
    last_updated = now()
WHERE source = 'GOOGLE_BOOKS'
  AND google_canonical_id IS NOT NULL
  AND google_canonical_id IS DISTINCT FROM nullif(regexp_replace(google_canonical_id, '[[:cntrl:]]', '', 'g'), '');

-- 2) Null out known placeholder/backreference-like canonical IDs.
UPDATE book_external_ids
SET google_canonical_id = NULL,
    last_updated = now()
WHERE source = 'GOOGLE_BOOKS'
  AND google_canonical_id IS NOT NULL
  AND (
    google_canonical_id ~ '^[\\]+1$'
    OR google_canonical_id = ''
  );

-- 3) Backfill canonical IDs from canonical_volume_link when possible.
WITH extracted_ids AS (
  SELECT
    id,
    nullif((regexp_match(canonical_volume_link, '[?&]id=([^&]+)'))[1], '') AS extracted_canonical_id
  FROM book_external_ids
  WHERE source = 'GOOGLE_BOOKS'
    AND canonical_volume_link IS NOT NULL
)
UPDATE book_external_ids target
SET google_canonical_id = extracted.extracted_canonical_id,
    last_updated = now()
FROM extracted_ids extracted
WHERE target.id = extracted.id
  AND extracted.extracted_canonical_id IS NOT NULL
  AND (
    target.google_canonical_id IS NULL
    OR target.google_canonical_id <> extracted.extracted_canonical_id
  );

-- 4) Remove invalid GOOGLE_CANONICAL clusters and memberships.
WITH invalid_clusters AS (
  SELECT id
  FROM work_clusters
  WHERE cluster_method = 'GOOGLE_CANONICAL'
    AND (
      google_canonical_id IS NULL
      OR google_canonical_id = ''
      OR google_canonical_id ~ '[[:cntrl:]]'
      OR google_canonical_id ~ '^[\\]+1$'
    )
)
DELETE FROM work_cluster_members members
USING invalid_clusters clusters
WHERE members.cluster_id = clusters.id;

WITH invalid_clusters AS (
  SELECT id
  FROM work_clusters
  WHERE cluster_method = 'GOOGLE_CANONICAL'
    AND (
      google_canonical_id IS NULL
      OR google_canonical_id = ''
      OR google_canonical_id ~ '[[:cntrl:]]'
      OR google_canonical_id ~ '^[\\]+1$'
    )
)
DELETE FROM work_clusters clusters
USING invalid_clusters invalid
WHERE clusters.id = invalid.id;

-- 5) Repair member_count for all clusters.
WITH cluster_member_counts AS (
  SELECT cluster_id, count(*)::integer AS member_total
  FROM work_cluster_members
  GROUP BY cluster_id
)
UPDATE work_clusters clusters
SET member_count = counts.member_total,
    updated_at = now()
FROM cluster_member_counts counts
WHERE clusters.id = counts.cluster_id
  AND clusters.member_count IS DISTINCT FROM counts.member_total;

UPDATE work_clusters
SET member_count = 0,
    updated_at = now()
WHERE member_count <> 0
  AND NOT EXISTS (
    SELECT 1
    FROM work_cluster_members members
    WHERE members.cluster_id = work_clusters.id
  );

-- 6) Rebuild Google canonical clusters with the corrected function.
SELECT *
FROM cluster_books_by_google_canonical();

-- 7) Validate the CHECK constraint deferred as NOT VALID in migration 01.
ALTER TABLE work_clusters VALIDATE CONSTRAINT check_reasonable_member_count;

COMMIT;
