-- ============================================================================
-- BOOK AI CONTENT
-- Stores versioned AI-generated content per book.
-- ============================================================================

create table if not exists book_ai_content (
  id text primary key, -- NanoID (12 chars via IdGenerator.generateLong())
  book_id uuid not null references books(id) on delete cascade,
  version_number integer not null check (version_number > 0),
  is_current boolean not null default false,
  content_json jsonb not null,
  summary text not null,
  reader_fit text not null,
  key_themes jsonb not null,
  takeaways jsonb,
  context text,
  model text not null,
  provider text not null,
  prompt_hash text,
  created_at timestamptz not null default now(),
  unique (book_id, version_number)
);

create unique index if not exists uq_book_ai_content_current
  on book_ai_content(book_id)
  where is_current;

create index if not exists idx_book_ai_content_book_created
  on book_ai_content(book_id, created_at desc);

alter table book_ai_content
  add column if not exists takeaways jsonb,
  add column if not exists context text;

comment on table book_ai_content is 'Versioned AI-generated book content with single current version per book';
comment on column book_ai_content.content_json is 'Canonical JSON payload returned by the AI model';
comment on column book_ai_content.reader_fit is 'Who should read this book and why';
comment on column book_ai_content.key_themes is 'JSON array of short key-theme strings';
comment on column book_ai_content.takeaways is 'JSON array of specific insight/point strings';
comment on column book_ai_content.context is 'Genre or field placement note';
