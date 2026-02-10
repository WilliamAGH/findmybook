-- Add grayscale detection column to book_image_links.
-- NULL means "not yet analyzed" and is treated as color everywhere.

ALTER TABLE book_image_links
ADD COLUMN IF NOT EXISTS is_grayscale BOOLEAN DEFAULT NULL;

COMMENT ON COLUMN book_image_links.is_grayscale
IS 'True when image is predominantly grayscale/B&W. NULL = not yet analyzed (treated as color).';

-- Partial index to support efficient backfill queries:
-- find S3-uploaded images that haven't been analyzed yet.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bil_grayscale_backfill
  ON book_image_links(book_id)
  WHERE s3_image_path IS NOT NULL AND is_grayscale IS NULL;
