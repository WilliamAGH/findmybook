-- Unified collections table for categories, lists, and custom collections
-- Can represent: NYT bestseller lists, Google Books categories, custom user lists, etc.
create table if not exists book_collections (
  id                 text primary key, -- NanoID (8 chars for low volume)
  collection_type    text not null, -- 'CATEGORY', 'BESTSELLER_LIST', 'CUSTOM_LIST', 'TAG', etc.
  source             text not null, -- 'NYT', 'GOOGLE_BOOKS', 'USER', 'SYSTEM', etc.
  provider_list_id   text, -- External ID if from a provider
  provider_list_code text, -- External code/slug if from a provider
  display_name       text not null, -- JSON: volumeInfo.categories[] or list display name
  normalized_name    text, -- For deduplication and searching
  description        text, -- Description of the collection/category
  parent_collection_id text references book_collections(id) on delete set null, -- For hierarchical categories
  -- List-specific fields (null for categories)
  bestsellers_date   date,
  published_date     date,
  updated_frequency  text,
  -- Metadata
  is_public          boolean default true,
  is_active          boolean default true,
  sort_order         integer,
  raw_data_json      jsonb, -- Raw data from provider if applicable
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create unique index if not exists uq_book_collections_source_code_date
  on book_collections (source, provider_list_code, published_date);

create index if not exists idx_book_collections_type on book_collections(collection_type);
create index if not exists idx_book_collections_name on book_collections(display_name);
create index if not exists idx_book_collections_normalized on book_collections(normalized_name);
create unique index if not exists uq_book_collections_category
  on book_collections (collection_type, source, normalized_name)
  where collection_type = 'CATEGORY' and normalized_name is not null;
create index if not exists idx_book_collections_parent on book_collections(parent_collection_id);
create index if not exists idx_book_collections_latest
  on book_collections (source, provider_list_code, published_date desc)
  where provider_list_code is not null;


-- Table comments for book_collections
comment on table book_collections is 'Unified table for categories, bestseller lists, and custom collections';
comment on column book_collections.collection_type is 'Type: CATEGORY, BESTSELLER_LIST, CUSTOM_LIST, TAG';
comment on column book_collections.source is 'Origin: NYT, GOOGLE_BOOKS, USER, SYSTEM';
comment on column book_collections.display_name is 'Display name from volumeInfo.categories[] or list name';

