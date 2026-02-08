-- Align legacy book_ai_content schema variants with canonical column names.
-- Safe to run multiple times.

BEGIN;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'book_ai_content'
      AND column_name = 'analysis_json'
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'book_ai_content'
      AND column_name = 'content_json'
  ) THEN
    ALTER TABLE book_ai_content RENAME COLUMN analysis_json TO content_json;
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'book_ai_content'
      AND column_name = 'content_json'
  ) THEN
    RAISE EXCEPTION 'book_ai_content.content_json is required but missing';
  END IF;
END
$$;

ALTER TABLE book_ai_content
  ADD COLUMN IF NOT EXISTS takeaways jsonb,
  ADD COLUMN IF NOT EXISTS context text;

COMMENT ON COLUMN book_ai_content.content_json IS 'Canonical JSON payload returned by the AI model';
COMMENT ON COLUMN book_ai_content.reader_fit IS 'Who should read this book and why';
COMMENT ON COLUMN book_ai_content.key_themes IS 'JSON array of short key-theme strings';
COMMENT ON COLUMN book_ai_content.takeaways IS 'JSON array of specific insight/point strings';
COMMENT ON COLUMN book_ai_content.context IS 'Genre or field placement note';

COMMIT;
