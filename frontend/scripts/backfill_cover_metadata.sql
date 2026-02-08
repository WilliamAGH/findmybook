-- Backfill canonical cover metadata in book_image_links.
-- 1. Populate estimated dimensions for Google Books image types when missing.
UPDATE book_image_links
SET width = CASE lower(image_type)
        WHEN 'extralarge' THEN 800
        WHEN 'large' THEN 600
        WHEN 'medium' THEN 400
        WHEN 'small' THEN 300
        WHEN 'thumbnail' THEN 128
        WHEN 'smallthumbnail' THEN 64
        ELSE width
    END,
    height = CASE lower(image_type)
        WHEN 'extralarge' THEN 1200
        WHEN 'large' THEN 900
        WHEN 'medium' THEN 600
        WHEN 'small' THEN 450
        WHEN 'thumbnail' THEN 192
        WHEN 'smallthumbnail' THEN 96
        ELSE height
    END
WHERE source = 'GOOGLE_BOOKS'
  AND (width IS NULL OR height IS NULL);

-- 2. Provide conservative defaults for legacy external rows missing dimensions.
UPDATE book_image_links
SET width = COALESCE(width, 360),
    height = COALESCE(height, 540)
WHERE source IN ('EXTERNAL', 'LOCAL_CACHE')
  AND (width IS NULL OR height IS NULL);

-- 3. Assume canonical S3 rows should be marked high resolution when dimensions present.
UPDATE book_image_links
SET width = COALESCE(width, 720),
    height = COALESCE(height, 1080),
    is_high_resolution = TRUE
WHERE source = 'S3'
  AND (width IS NULL OR height IS NULL OR is_high_resolution IS DISTINCT FROM TRUE);

-- 4. Recompute high-resolution flag across all rows using current threshold.
UPDATE book_image_links
SET is_high_resolution = (
        COALESCE(width, 0) * COALESCE(height, 0)
    ) >= 320000
WHERE width IS NOT NULL
  AND height IS NOT NULL;
UPDATE book_image_links
SET is_high_resolution = (
        width IS NOT NULL
        AND height IS NOT NULL
        AND (width * height) >= 320000
    );

-- 5. Optional: clean up download errors older than 30 days (commented out by default).
-- UPDATE book_image_links
-- SET download_error = NULL
-- WHERE download_error IS NOT NULL
--   AND updated_at < NOW() - INTERVAL '30 days';
