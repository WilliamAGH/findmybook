-- Persist aggregate page-view events outside of book detail tracking.
-- This keeps homepage traffic analytics separate from recent_book_views.

CREATE TABLE IF NOT EXISTS page_view_events (
  id BIGSERIAL PRIMARY KEY,
  page_key TEXT NOT NULL,
  viewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  source TEXT,
  CONSTRAINT check_page_view_events_page_key_not_blank
    CHECK (length(btrim(page_key)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_page_view_events_page_key_viewed_at
  ON page_view_events(page_key, viewed_at DESC);

CREATE INDEX IF NOT EXISTS idx_page_view_events_viewed_at
  ON page_view_events(viewed_at DESC);

COMMENT ON TABLE page_view_events IS
  'Append-only page-view events for non-book surfaces (for example: homepage).';

COMMENT ON COLUMN page_view_events.page_key IS
  'Stable route key for the viewed page.';

COMMENT ON COLUMN page_view_events.viewed_at IS
  'Timestamp when the page view happened.';

COMMENT ON COLUMN page_view_events.source IS
  'Optional origin label for the page view event (for example: api).';
