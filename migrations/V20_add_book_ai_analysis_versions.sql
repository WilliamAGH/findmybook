-- ============================================================================
-- BOOK AI ANALYSIS VERSIONING MIGRATION
-- ============================================================================

-- Table: book_ai_analysis_versions
-- Stores versioned AI-generated "Reader Fit Snapshot" content per book.
-- Includes canonical JSON payload, parsed summary/reader_fit/themes, and model metadata.

create table if not exists book_ai_analysis_versions (
  id text primary key, -- NanoID (12 chars via IdGenerator.generateLong())
  book_id uuid not null references books(id) on delete cascade,
  version_number integer not null check (version_number > 0),
  is_current boolean not null default false,
  analysis_json jsonb not null,
  summary text not null,
  reader_fit text not null,
  key_themes jsonb not null,
  model text not null,
  provider text not null,
  prompt_hash text,
  created_at timestamptz not null default now(),
  unique (book_id, version_number)
);

-- Partial index to enforce only one current version per book
create unique index if not exists uq_book_ai_analysis_versions_current
  on book_ai_analysis_versions(book_id)
  where is_current;

-- Index for history lookup
create index if not exists idx_book_ai_analysis_versions_book_created
  on book_ai_analysis_versions(book_id, created_at desc);

-- Comments
comment on table book_ai_analysis_versions is 'Versioned AI-generated book analysis snapshots with single current version per book';
comment on column book_ai_analysis_versions.analysis_json is 'Canonical JSON payload returned by the AI model';
comment on column book_ai_analysis_versions.reader_fit is 'Who should read this book and why';
comment on column book_ai_analysis_versions.key_themes is 'JSON array of short key-theme strings';
