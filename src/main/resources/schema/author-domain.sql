-- Authors table for storing unique author names
create table if not exists authors (
  id text primary key, -- NanoID (10 chars via IdGenerator.generate())
  name text not null unique, -- JSON: volumeInfo.authors[] array elements
  constraint authors_name_non_blank_check check (btrim(name) <> ''),
  constraint authors_name_leading_character_check check (
    regexp_replace(name, '^[[:space:]]+', '') ~ '^[[:alpha:][:digit:]]'
  ),
  normalized_name text, -- For deduplication: lowercase, no accents, etc.
  birth_date date, -- From external sources when available
  death_date date, -- From external sources when available
  biography text, -- Author bio from various sources
  nationality text, -- Country/nationality when known
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- Generated search vector for author name search
  search_vector tsvector generated always as (
    to_tsvector('english', coalesce(name, ''))
  ) stored
);

create index if not exists idx_authors_name on authors(name);
create index if not exists idx_authors_normalized_name on authors(normalized_name);
create index if not exists idx_authors_search_vector on authors using gin (search_vector);

-- Table comments for authors
comment on table authors is 'Canonical author records deduplicated across all sources';
comment on column authors.name is 'Author name from volumeInfo.authors[] array';
comment on column authors.normalized_name is 'Lowercase, no accents version for deduplication';

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

-- Join table linking books to authors (many-to-many)
create table if not exists book_authors_join (
  id text primary key, -- NanoID (12 chars via IdGenerator.generateLong() - high volume)
  book_id uuid not null references books(id) on delete cascade,
  author_id text not null references authors(id) on delete cascade,
  position integer not null default 0, -- Order of author as it appears in the book
  created_at timestamptz not null default now(),
  unique(book_id, author_id)
);

create index if not exists idx_book_authors_book_id on book_authors_join(book_id);
create index if not exists idx_book_authors_author_id on book_authors_join(author_id);
create index if not exists idx_book_authors_position on book_authors_join(book_id, position);
