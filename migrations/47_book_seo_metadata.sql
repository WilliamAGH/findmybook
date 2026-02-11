-- ============================================================================
-- BOOK SEO METADATA
-- Stores versioned AI-generated SEO title/description per book.
-- ============================================================================

create table if not exists book_seo_metadata (
  id text primary key, -- NanoID (12 chars via IdGenerator.generateLong())
  book_id uuid not null references books(id) on delete cascade,
  version_number integer not null check (version_number > 0),
  is_current boolean not null default false,
  seo_title text not null check (char_length(trim(seo_title)) > 0),
  seo_description text not null check (char_length(trim(seo_description)) > 0),
  model text not null,
  provider text not null,
  prompt_hash text,
  created_at timestamptz not null default now(),
  unique (book_id, version_number)
);

create unique index if not exists uq_book_seo_metadata_current
  on book_seo_metadata(book_id)
  where is_current;

create index if not exists idx_book_seo_metadata_book_created
  on book_seo_metadata(book_id, created_at desc);

comment on table book_seo_metadata is
  'Versioned AI-generated SEO metadata with single current version per book';
comment on column book_seo_metadata.seo_title is
  'Canonical SEO title candidate used for title and social metadata surfaces';
comment on column book_seo_metadata.seo_description is
  'Canonical SEO description candidate used for meta and social descriptions';
