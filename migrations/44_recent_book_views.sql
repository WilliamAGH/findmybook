-- Persist per-book detail page views used for recently viewed shelves
-- and rolling popularity analytics.

CREATE TABLE IF NOT EXISTS recent_book_views (
  id BIGSERIAL PRIMARY KEY,
  book_id TEXT NOT NULL,
  viewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  source TEXT
);

CREATE INDEX IF NOT EXISTS idx_recent_book_views_book_id_viewed_at
  ON recent_book_views(book_id, viewed_at DESC);

CREATE INDEX IF NOT EXISTS idx_recent_book_views_viewed_at
  ON recent_book_views(viewed_at DESC);

CREATE INDEX IF NOT EXISTS idx_recent_book_views_viewed_at_book_id
  ON recent_book_views(viewed_at DESC, book_id);

-- Remove legacy duplicate index; idx_recent_book_views_book_id_viewed_at is canonical.
DROP INDEX IF EXISTS idx_recent_book_views_book_time;

COMMENT ON TABLE recent_book_views IS
  'Append-only per-book detail page view events.';

COMMENT ON COLUMN recent_book_views.book_id IS
  'Canonical book identifier (UUID text) for the viewed detail page.';

COMMENT ON COLUMN recent_book_views.viewed_at IS
  'Timestamp when the book detail view happened.';

COMMENT ON COLUMN recent_book_views.source IS
  'Optional origin label for the view event (for example: web, api).';
