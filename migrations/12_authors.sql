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
