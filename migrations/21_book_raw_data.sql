-- Store the full raw JSON response for debugging/reprocessing
-- Also serves as contributing sources tracking (presence of a record = contributed data)
create table if not exists book_raw_data (
  id text primary key, -- NanoID (10 chars)
  book_id uuid not null references books(id) on delete cascade,
  raw_json_response jsonb not null, -- Complete API response as JSONB
  source text not null, -- 'GOOGLE_BOOKS', 'OPEN_LIBRARY', etc.
  fetched_at timestamptz not null default now(), -- When we fetched this data
  contributed_at timestamptz not null default now(), -- When this source contributed to the book
  created_at timestamptz not null default now(),
  unique(book_id, source)
);

create index if not exists idx_book_raw_data_book_id on book_raw_data(book_id);
create index if not exists idx_book_raw_data_source on book_raw_data(source);

-- Table comments for book_raw_data
comment on table book_raw_data is 'Raw JSON responses from providers for debugging/reprocessing';
comment on column book_raw_data.raw_json_response is 'Complete API response as JSONB';
comment on column book_raw_data.source is 'Provider that supplied this data';
comment on column book_raw_data.contributed_at is 'When this source contributed to the book record';
