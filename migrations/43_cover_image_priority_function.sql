-- Centralized cover image priority scoring.
-- Replaces duplicated inline CASE expressions across optimized_book_queries.sql
-- and BookQueryCoverNormalizer.java.
--
-- Grayscale penalty (+100) ensures color covers always rank above grayscale
-- within a single book, while grayscale still beats "no cover" entirely.

CREATE OR REPLACE FUNCTION cover_image_priority(
    p_s3_image_path   TEXT,
    p_is_high_resolution BOOLEAN,
    p_url             TEXT,
    p_width           INTEGER,
    p_height          INTEGER,
    p_image_type      TEXT,
    p_is_grayscale    BOOLEAN
) RETURNS INTEGER LANGUAGE sql IMMUTABLE AS $$
  SELECT
    CASE WHEN COALESCE(p_is_grayscale, false) THEN 100 ELSE 0 END
    +
    CASE
      WHEN p_s3_image_path IS NOT NULL AND COALESCE(p_is_high_resolution, false) THEN 0
      WHEN p_s3_image_path IS NOT NULL THEN 1
      WHEN p_url LIKE '%edge=curl%' THEN 5
      WHEN COALESCE(p_is_high_resolution, false) THEN 10
      WHEN p_width >= 320 AND p_height >= 320 THEN 11
      WHEN p_image_type = 'extraLarge' THEN 12
      WHEN p_image_type = 'large' THEN 13
      WHEN p_image_type = 'medium' THEN 14
      WHEN p_image_type = 'small' THEN 15
      WHEN p_image_type = 'thumbnail' THEN 16
      ELSE 20
    END
$$;

COMMENT ON FUNCTION cover_image_priority IS
  'Returns an integer priority for cover selection: lower is better. '
  'Grayscale covers receive a +100 penalty so color covers always win.';

-- Simplified variant for fallback subqueries (no S3 path or edge=curl checks).
CREATE OR REPLACE FUNCTION cover_fallback_priority(
    p_is_high_resolution BOOLEAN,
    p_width              INTEGER,
    p_height             INTEGER,
    p_image_type         TEXT,
    p_is_grayscale       BOOLEAN
) RETURNS INTEGER LANGUAGE sql IMMUTABLE AS $$
  SELECT
    CASE WHEN COALESCE(p_is_grayscale, false) THEN 100 ELSE 0 END
    +
    CASE
      WHEN COALESCE(p_is_high_resolution, false) THEN 0
      WHEN p_width >= 320 AND p_height >= 320 THEN 1
      WHEN p_image_type = 'extraLarge' THEN 2
      WHEN p_image_type = 'large' THEN 3
      WHEN p_image_type = 'medium' THEN 4
      ELSE 5
    END
$$;

COMMENT ON FUNCTION cover_fallback_priority IS
  'Simplified cover priority for fallback subqueries without S3/edge=curl logic.';
