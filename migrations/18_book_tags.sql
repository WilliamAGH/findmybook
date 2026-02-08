-- Tags / qualifiers --------------------------------------------------------

create table if not exists book_tags (
  id text primary key, -- NanoID (10 chars)
  key text not null check (
    key = lower(regexp_replace(btrim(key), '[[:space:]]+', '_', 'g'))
    and key <> ''
  ), -- canonical tag key (e.g., 'nyt_bestseller')
  display_name text, -- human friendly label (e.g., 'NYT Bestseller')
  tag_type text not null default 'QUALIFIER', -- QUALIFIER, PROVIDER_TAG, USER_TAG, etc.
  description text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (key)
);

comment on table book_tags is 'Canonical tags/qualifiers applied to books (e.g., NYT bestseller, award winner).';
comment on column book_tags.key is 'Stable key used across services (snake_case).';
comment on column book_tags.tag_type is 'Tag classification: QUALIFIER, PROVIDER_TAG, USER_TAG, etc.';

