-- External IDs and metadata for authors from various providers
create table if not exists author_external_ids (
  id text primary key, -- NanoID (10 chars via IdGenerator.generate())
  author_id text not null references authors(id) on delete cascade,
  source text not null, -- 'GOOGLE_BOOKS', 'OPEN_LIBRARY', 'GOODREADS', 'VIAF', 'ISNI', 'ORCID', 'WIKIDATA', etc.
  external_id text not null,
  -- Provider-specific metadata
  profile_url text,
  image_url text,
  follower_count integer,
  book_count integer,
  average_rating numeric,
  ratings_count integer,
  -- Additional metadata
  verified boolean,
  last_updated timestamptz,
  created_at timestamptz not null default now(),
  unique(source, external_id)
);

create index if not exists idx_author_external_ids_author_id on author_external_ids(author_id);
create index if not exists idx_author_external_ids_external_id on author_external_ids(external_id);
create index if not exists idx_author_external_ids_source_id on author_external_ids(source, external_id);
