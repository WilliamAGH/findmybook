-- Propagate existing is_grayscale=true values to sibling rows for the same book.
--
-- When a cover was uploaded to S3 and analyzed as grayscale, only the canonical
-- row received is_grayscale=true. The original external-URL rows (extraLarge,
-- large, medium, etc.) retained is_grayscale=NULL, which the priority function
-- treats as "color" — creating a priority inversion where unanalyzed rows beat
-- the explicitly-grayscale S3 row.
--
-- This migration propagates true to all NULL siblings. It is idempotent.

UPDATE book_image_links bil
SET is_grayscale = true, updated_at = NOW()
WHERE bil.is_grayscale IS NULL
  AND EXISTS (
      SELECT 1 FROM book_image_links sibling
      WHERE sibling.book_id = bil.book_id
        AND sibling.is_grayscale = true
  );
