-- ============================================================================
-- Allow reader_fit to be NULL in book_ai_content.
--
-- The AI content generation prompt now permits the model to decline
-- generating a reader_fit field when the book description lacks sufficient
-- information about the intended audience. This prevents fabricated
-- audience descriptions (hallucinations).
-- ============================================================================

ALTER TABLE book_ai_content
  ALTER COLUMN reader_fit DROP NOT NULL;

COMMENT ON COLUMN book_ai_content.reader_fit
  IS 'Who should read this book and why (nullable when description lacks audience information)';
