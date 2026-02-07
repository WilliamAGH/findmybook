-- Fix schema drift for Google canonical clustering functions.
-- Safe to run multiple times.

BEGIN;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    WHERE t.relname = 'work_clusters'
      AND c.conname = 'check_reasonable_member_count'
  ) THEN
    ALTER TABLE work_clusters
      ADD CONSTRAINT check_reasonable_member_count CHECK (member_count <= 100);
  END IF;
END
$$;

CREATE OR REPLACE FUNCTION cluster_single_book_by_google_id(target_book_id uuid)
RETURNS void AS $$
DECLARE
  canonical_id text;
  cluster_uuid uuid;
  book_ids uuid[];
  book_count integer;
  primary_title text;
  i integer;
  current_book uuid;
BEGIN
  SELECT google_canonical_id INTO canonical_id
  FROM book_external_ids
  WHERE book_id = target_book_id
    AND google_canonical_id IS NOT NULL
    AND source = 'GOOGLE_BOOKS'
  LIMIT 1;

  IF canonical_id IS NOT NULL THEN
    canonical_id := nullif(regexp_replace(canonical_id, '[[:cntrl:]]', '', 'g'), '');
  END IF;

  IF canonical_id IS NULL THEN
    SELECT canonical_volume_link INTO canonical_id
    FROM book_external_ids
    WHERE book_id = target_book_id
      AND canonical_volume_link IS NOT NULL
      AND source = 'GOOGLE_BOOKS'
    LIMIT 1;

    IF canonical_id IS NOT NULL THEN
      canonical_id := (regexp_match(canonical_id, '[?&]id=([^&]+)'))[1];
      canonical_id := nullif(regexp_replace(canonical_id, '[[:cntrl:]]', '', 'g'), '');
    END IF;
  END IF;

  IF canonical_id IS NULL THEN
    RETURN;
  END IF;

  SELECT
    array_agg(book_id ORDER BY has_high_res DESC, cover_area DESC, published_date DESC NULLS LAST, lower(title)) AS ordered_ids,
    count(*) AS total_books
  INTO book_ids, book_count
  FROM (
    SELECT DISTINCT
      b.id AS book_id,
      b.title,
      b.published_date,
      coalesce(
        (
          SELECT coalesce(bil.is_high_resolution, false)
          FROM book_image_links bil
          WHERE bil.book_id = b.id
          ORDER BY coalesce(bil.is_high_resolution, false) DESC,
                   coalesce((bil.width::bigint * bil.height::bigint), 0) DESC,
                   bil.created_at DESC
          LIMIT 1
        ),
        false
      ) AS has_high_res,
      coalesce(
        (
          SELECT coalesce((bil.width::bigint * bil.height::bigint), 0)
          FROM book_image_links bil
          WHERE bil.book_id = b.id
          ORDER BY coalesce(bil.is_high_resolution, false) DESC,
                   coalesce((bil.width::bigint * bil.height::bigint), 0) DESC,
                   bil.created_at DESC
          LIMIT 1
        ),
        0
      ) AS cover_area
    FROM book_external_ids bei
    JOIN books b ON b.id = bei.book_id
    WHERE bei.source = 'GOOGLE_BOOKS'
      AND (
        nullif(regexp_replace(bei.google_canonical_id, '[[:cntrl:]]', '', 'g'), '') = canonical_id
        OR (
          bei.canonical_volume_link IS NOT NULL
          AND (regexp_match(bei.canonical_volume_link, '[?&]id=([^&]+)'))[1] = canonical_id
        )
      )
  ) ranked;

  IF book_count IS NULL OR book_count < 2 THEN
    RETURN;
  END IF;

  SELECT coalesce(nullif(title, ''), 'Untitled Book') INTO primary_title
  FROM books
  WHERE id = book_ids[1];

  IF primary_title IS NULL OR primary_title = '' THEN
    primary_title := 'Untitled Book';
  END IF;

  SELECT id INTO cluster_uuid
  FROM work_clusters
  WHERE google_canonical_id = canonical_id;

  IF cluster_uuid IS NULL THEN
    INSERT INTO work_clusters (google_canonical_id, canonical_title, confidence_score, cluster_method, member_count)
    VALUES (canonical_id, primary_title, 0.85, 'GOOGLE_CANONICAL', book_count)
    RETURNING id INTO cluster_uuid;
  ELSE
    UPDATE work_clusters
    SET canonical_title = coalesce(primary_title, work_clusters.canonical_title),
        member_count = book_count,
        updated_at = now()
    WHERE id = cluster_uuid;
  END IF;

  FOR i IN 1..array_length(book_ids, 1) LOOP
    current_book := book_ids[i];

    INSERT INTO work_cluster_members (cluster_id, book_id, is_primary, confidence, join_reason)
    VALUES (cluster_uuid, current_book, (i = 1), 0.85, 'GOOGLE_CANONICAL')
    ON CONFLICT (cluster_id, book_id) DO UPDATE SET
      is_primary = EXCLUDED.is_primary,
      confidence = EXCLUDED.confidence,
      join_reason = EXCLUDED.join_reason,
      joined_at = now();
  END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION cluster_books_by_google_canonical()
RETURNS TABLE (clusters_created integer, books_clustered integer) AS $$
DECLARE
  rec record;
  cluster_uuid uuid;
  clusters_count integer := 0;
  books_count integer := 0;
  primary_title text;
BEGIN
  FOR rec IN
    SELECT
      canonical_id,
      array_agg(book_id ORDER BY has_high_res DESC, cover_area DESC, published_date DESC NULLS LAST, lower(title)) AS book_ids,
      count(*) AS book_count
    FROM (
      SELECT DISTINCT
        coalesce(
          nullif(regexp_replace(bei.google_canonical_id, '[[:cntrl:]]', '', 'g'), ''),
          (regexp_match(bei.canonical_volume_link, '[?&]id=([^&]+)'))[1]
        ) AS canonical_id,
        b.id AS book_id,
        b.title,
        b.published_date,
        coalesce(
          (
            SELECT coalesce(bil.is_high_resolution, false)
            FROM book_image_links bil
            WHERE bil.book_id = b.id
            ORDER BY coalesce(bil.is_high_resolution, false) DESC,
                     coalesce((bil.width::bigint * bil.height::bigint), 0) DESC,
                     bil.created_at DESC
            LIMIT 1
          ),
          false
        ) AS has_high_res,
        coalesce(
          (
            SELECT coalesce((bil.width::bigint * bil.height::bigint), 0)
            FROM book_image_links bil
            WHERE bil.book_id = b.id
            ORDER BY coalesce(bil.is_high_resolution, false) DESC,
                     coalesce((bil.width::bigint * bil.height::bigint), 0) DESC,
                     bil.created_at DESC
            LIMIT 1
          ),
          0
        ) AS cover_area
      FROM book_external_ids bei
      JOIN books b ON b.id = bei.book_id
      WHERE bei.source = 'GOOGLE_BOOKS'
        AND (
          bei.google_canonical_id IS NOT NULL
          OR bei.canonical_volume_link ~ '[?&]id='
        )
    ) candidate
    WHERE canonical_id IS NOT NULL
      AND canonical_id <> ''
      AND canonical_id !~ '[[:cntrl:]]'
    GROUP BY canonical_id
    HAVING count(*) > 1
  LOOP
    IF rec.book_ids IS NULL OR array_length(rec.book_ids, 1) = 0 THEN
      CONTINUE;
    END IF;

    SELECT coalesce(nullif(title, ''), 'Untitled Book') INTO primary_title
    FROM books
    WHERE id = rec.book_ids[1];

    IF primary_title IS NULL OR primary_title = '' THEN
      primary_title := 'Untitled Book';
    END IF;

    SELECT id INTO cluster_uuid
    FROM work_clusters
    WHERE google_canonical_id = rec.canonical_id;

    IF cluster_uuid IS NULL THEN
      INSERT INTO work_clusters (google_canonical_id, canonical_title, confidence_score, cluster_method, member_count)
      VALUES (rec.canonical_id, primary_title, 0.85, 'GOOGLE_CANONICAL', rec.book_count)
      RETURNING id INTO cluster_uuid;

      clusters_count := clusters_count + 1;
    ELSE
      UPDATE work_clusters
      SET canonical_title = coalesce(primary_title, work_clusters.canonical_title),
          member_count = rec.book_count,
          updated_at = now()
      WHERE id = cluster_uuid;
    END IF;

    FOR i IN 1..array_length(rec.book_ids, 1) LOOP
      INSERT INTO work_cluster_members (cluster_id, book_id, is_primary, confidence, join_reason)
      VALUES (cluster_uuid, rec.book_ids[i], (i = 1), 0.85, 'GOOGLE_CANONICAL')
      ON CONFLICT (cluster_id, book_id) DO UPDATE SET
        is_primary = EXCLUDED.is_primary,
        confidence = EXCLUDED.confidence,
        join_reason = EXCLUDED.join_reason,
        joined_at = now();

      books_count := books_count + 1;
    END LOOP;
  END LOOP;

  RETURN QUERY SELECT clusters_count, books_count;
END;
$$ LANGUAGE plpgsql;

COMMIT;
