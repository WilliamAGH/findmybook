-- Add versioned AI content storage for book detail pages.
-- Safe to run multiple times.

BEGIN;

CREATE TABLE IF NOT EXISTS book_ai_content (
  id text PRIMARY KEY,
  book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  version_number integer NOT NULL CHECK (version_number > 0),
  is_current boolean NOT NULL DEFAULT false,
  analysis_json jsonb NOT NULL,
  summary text NOT NULL,
  reader_fit text NOT NULL,
  key_themes jsonb NOT NULL,
  model text NOT NULL,
  provider text NOT NULL,
  prompt_hash text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (book_id, version_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_book_ai_content_current
  ON book_ai_content(book_id)
  WHERE is_current;

CREATE INDEX IF NOT EXISTS idx_book_ai_content_book_created
  ON book_ai_content(book_id, created_at DESC);

COMMENT ON TABLE book_ai_content IS 'Versioned AI-generated book analysis snapshots with single current version per book';
COMMENT ON COLUMN book_ai_content.analysis_json IS 'Canonical JSON payload returned by the AI model';
COMMENT ON COLUMN book_ai_content.reader_fit IS 'Who should read this book and why';
COMMENT ON COLUMN book_ai_content.key_themes IS 'JSON array of short key-theme strings';

COMMIT;
