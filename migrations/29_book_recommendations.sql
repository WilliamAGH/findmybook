-- ============================================================================
-- RECOMMENDATIONS CACHE
-- ============================================================================

-- Cache for book-to-book recommendations
create table if not exists book_recommendations (
  id text primary key, -- NanoID (10 chars via IdGenerator.generate())
  source_book_id uuid not null references books(id) on delete cascade,
  recommended_book_id uuid not null references books(id) on delete cascade,
  source text not null, -- 'GOOGLE_SIMILAR', 'SAME_AUTHOR', 'SAME_CATEGORY', 'AI_GENERATED'
  score float check (score >= 0 and score <= 1), -- Relevance score 0.0-1.0
  reason text, -- Why this was recommended
  generated_at timestamptz not null default now(),
  expires_at timestamptz default (now() + interval '30 days'),
  created_at timestamptz not null default now(),
  unique(source_book_id, recommended_book_id, source)
);

create index if not exists idx_book_recommendations_source on book_recommendations(source_book_id);
create index if not exists idx_book_recommendations_target on book_recommendations(recommended_book_id);
create index if not exists idx_book_recommendations_expires on book_recommendations(expires_at) where expires_at is not null;

comment on table book_recommendations is 'Cached book-to-book recommendations from various sources';
comment on column book_recommendations.source is 'Origin of recommendation: GOOGLE_SIMILAR, SAME_AUTHOR, SAME_CATEGORY, AI_GENERATED';
comment on column book_recommendations.score is 'Relevance score from 0.0 (weak) to 1.0 (strong)';
comment on column book_recommendations.expires_at is 'When to refresh this recommendation';
